#!/usr/bin/env python3
"""Collect repeatable Android capture endurance evidence without OCR text."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import re
import statistics
import subprocess
import time
from pathlib import Path
from typing import Any, Iterable


PERF_PREFIX = "SCREEN_TRANSLATION_PERF_V1 "
SERVICE_CLASS = ".service.ScreenTranslationService"
VIRTUAL_DISPLAY_NAME = "ScreenTranslationCapture"


def run(
    command: list[str],
    *,
    timeout: float = 30.0,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        command,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        check=False,
    )
    if check and completed.returncode != 0:
        raise RuntimeError(
            f"Command failed with exit {completed.returncode}: {command!r}\n{completed.stdout}"
        )
    return completed


class Adb:
    def __init__(self, executable: Path, serial: str) -> None:
        self.executable = str(executable)
        self.serial = serial

    def call(
        self,
        *arguments: str,
        timeout: float = 30.0,
        check: bool = True,
    ) -> str:
        return run(
            [self.executable, "-s", self.serial, *arguments],
            timeout=timeout,
            check=check,
        ).stdout.replace("\r\n", "\n")

    def shell(
        self,
        *arguments: str,
        timeout: float = 30.0,
        check: bool = True,
    ) -> str:
        return self.call("shell", *arguments, timeout=timeout, check=check)


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def single_int(pattern: str, text: str) -> int | None:
    match = re.search(pattern, text, re.MULTILINE)
    return int(match.group(1)) if match else None


def single_float(pattern: str, text: str) -> float | None:
    match = re.search(pattern, text, re.MULTILINE)
    return float(match.group(1)) if match else None


def parse_proc_status(text: str) -> dict[str, int | None]:
    def kib(name: str) -> int | None:
        return single_int(rf"^{re.escape(name)}:\s+(\d+)\s+kB$", text)

    return {
        "vm_rss_kib": kib("VmRSS"),
        "vm_hwm_kib": kib("VmHWM"),
        "vm_size_kib": kib("VmSize"),
        "threads": single_int(r"^Threads:\s+(\d+)$", text),
    }


def parse_proc_stat(text: str) -> dict[str, int | None]:
    closing = text.rfind(")")
    if closing < 0:
        return {"utime_ticks": None, "stime_ticks": None, "cpu_ticks": None}
    fields = text[closing + 2 :].split()
    if len(fields) < 13:
        return {"utime_ticks": None, "stime_ticks": None, "cpu_ticks": None}
    utime = int(fields[11])
    stime = int(fields[12])
    return {"utime_ticks": utime, "stime_ticks": stime, "cpu_ticks": utime + stime}


def parse_meminfo(text: str) -> dict[str, int | None]:
    result: dict[str, int | None] = {
        "total_pss_kib": None,
        "total_rss_kib": None,
        "total_swap_pss_kib": None,
        "java_heap_kib": None,
        "native_heap_kib": None,
        "graphics_kib": None,
    }
    combined = re.search(
        r"TOTAL PSS:\s*(\d+)\s+TOTAL RSS:\s*(\d+)(?:\s+TOTAL SWAP PSS:\s*(\d+))?",
        text,
    )
    if combined:
        result["total_pss_kib"] = int(combined.group(1))
        result["total_rss_kib"] = int(combined.group(2))
        if combined.group(3):
            result["total_swap_pss_kib"] = int(combined.group(3))
    else:
        total_line = re.search(r"^\s*TOTAL\s+(\d+).*$", text, re.MULTILINE)
        if total_line:
            result["total_pss_kib"] = int(total_line.group(1))
    for label, key in (
        ("Java Heap", "java_heap_kib"),
        ("Native Heap", "native_heap_kib"),
        ("Graphics", "graphics_kib"),
    ):
        value = single_int(rf"^\s*{re.escape(label)}:\s+(\d+)$", text)
        if value is not None:
            result[key] = value
    return result


def parse_battery(text: str) -> dict[str, int | bool | None]:
    def boolean(name: str) -> bool | None:
        match = re.search(rf"^\s*{re.escape(name)}:\s*(true|false)$", text, re.MULTILINE)
        return match.group(1) == "true" if match else None

    return {
        "ac_powered": boolean("AC powered"),
        "usb_powered": boolean("USB powered"),
        "wireless_powered": boolean("Wireless powered"),
        "status": single_int(r"^\s*status:\s*(\d+)$", text),
        "level_percent": single_int(r"^\s*level:\s*(\d+)$", text),
        "voltage_mv": single_int(r"^\s*voltage:\s*(\d+)$", text),
        "temperature_tenths_c": single_int(r"^\s*temperature:\s*(\d+)$", text),
        "charge_counter_uah": single_int(r"^\s*Charge counter:\s*(-?\d+)$", text),
    }


def parse_thermal(text: str) -> dict[str, Any]:
    temperatures: dict[str, dict[str, float | int]] = {}
    for match in re.finditer(
        r"Temperature\{mValue=([-+0-9.]+), mType=(\d+), mName=([^,}]+), mStatus=(\d+)\}",
        text,
    ):
        temperatures[match.group(3)] = {
            "celsius": float(match.group(1)),
            "type": int(match.group(2)),
            "status": int(match.group(4)),
        }
    return {
        "status": single_int(r"^Thermal Status:\s*(\d+)$", text),
        "skin": temperatures.get("skin"),
        "battery": temperatures.get("battery"),
        "maximum_cpu_c": max(
            (value["celsius"] for name, value in temperatures.items() if name.startswith("CPU")),
            default=None,
        ),
        "maximum_gpu_c": max(
            (value["celsius"] for name, value in temperatures.items() if name.startswith("GPU")),
            default=None,
        ),
    }


def parse_perf_snapshot(text: str) -> dict[str, Any] | None:
    for line in text.splitlines():
        at = line.find(PERF_PREFIX)
        if at >= 0:
            payload = line[at + len(PERF_PREFIX) :].strip()
            if payload == "null":
                return None
            try:
                return json.loads(payload)
            except json.JSONDecodeError:
                return None
    return None


def read_private_proc(adb: Adb, pid: int, leaf: str) -> str:
    output = adb.shell("cat", f"/proc/{pid}/{leaf}", check=False)
    if output.strip() and "Permission denied" not in output and "No such file" not in output:
        return output
    return adb.shell("su", "0", "cat", f"/proc/{pid}/{leaf}", check=False)


def collect_sample(adb: Adb, package: str, elapsed_seconds: float) -> dict[str, Any]:
    pid_text = adb.shell("pidof", package, check=False).strip()
    pid = int(pid_text.split()[0]) if pid_text else None
    status = parse_proc_status(read_private_proc(adb, pid, "status")) if pid else {}
    process_stat = parse_proc_stat(read_private_proc(adb, pid, "stat")) if pid else {}
    meminfo = parse_meminfo(adb.shell("dumpsys", "meminfo", "--local", package, timeout=45.0))
    battery = parse_battery(adb.shell("dumpsys", "battery"))
    thermal = parse_thermal(adb.shell("dumpsys", "thermalservice"))
    service_component = f"{package}/{SERVICE_CLASS}"
    service_dump = adb.shell(
        "dumpsys", "activity", "service", service_component, timeout=45.0, check=False
    )
    services = adb.shell("dumpsys", "activity", "services", package, timeout=45.0)
    projection = adb.shell("dumpsys", "media_projection", timeout=45.0)
    displays = adb.shell("dumpsys", "display", timeout=45.0)
    return {
        "timestamp_utc": utc_now(),
        "elapsed_seconds": round(elapsed_seconds, 3),
        "pid": pid,
        "proc": {**status, **process_stat},
        "meminfo": meminfo,
        "battery": battery,
        "thermal": thermal,
        "service_present": "ScreenTranslationService" in services,
        "projection_present": package in projection,
        "virtual_display_present": VIRTUAL_DISPLAY_NAME in displays,
        "performance": parse_perf_snapshot(service_dump),
    }


def finite_values(samples: Iterable[dict[str, Any]], path: tuple[str, ...]) -> list[float]:
    values: list[float] = []
    for sample in samples:
        current: Any = sample
        for key in path:
            if not isinstance(current, dict):
                current = None
                break
            current = current.get(key)
        if isinstance(current, (int, float)) and math.isfinite(float(current)):
            values.append(float(current))
    return values


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * fraction) - 1))
    return ordered[index]


def series_summary(values: list[float]) -> dict[str, float | int | None]:
    if not values:
        return {"count": 0, "minimum": None, "median": None, "p95": None, "maximum": None}
    return {
        "count": len(values),
        "minimum": min(values),
        "median": statistics.median(values),
        "p95": percentile(values, 0.95),
        "maximum": max(values),
        "first": values[0],
        "last": values[-1],
        "delta": values[-1] - values[0],
    }


def performance_delta(samples: list[dict[str, Any]]) -> dict[str, Any] | None:
    snapshots = [sample.get("performance") for sample in samples if sample.get("performance")]
    if not snapshots:
        return None
    first = snapshots[0]
    last = snapshots[-1]
    if first.get("session_id") != last.get("session_id"):
        return {
            "session_id_consistent": False,
            "first_session_id": first.get("session_id"),
            "last_session_id": last.get("session_id"),
        }
    counters = (
        "frames_available",
        "frames_admitted",
        "frames_rejected",
        "signature_scans",
        "naturally_changed_frames",
        "natural_dirty_tiles",
        "scheduled_dirty_tiles",
        "bitmap_materializations",
        "bitmap_materialized_bytes",
        "bitmap_skips",
        "bitmap_skipped_bytes",
        "ocr_calls",
        "ocr_successes",
        "ocr_failures",
        "region_ocr_calls",
        "full_frame_ocr_calls",
        "tile_ocr_calls",
        "ocr_input_pixels",
        "translation_calls",
        "translation_successes",
        "translation_failures",
        "translation_cache_hits",
        "translations_published",
        "lifecycle_resets",
        "disabled_transitions",
        "enabled_transitions",
        "processing_errors",
    )
    deltas: dict[str, int | None] = {}
    for key in counters:
        left = first.get(key)
        right = last.get(key)
        deltas[key] = right - left if isinstance(left, int) and isinstance(right, int) else None
    return {
        "session_id_consistent": True,
        "session_id": last.get("session_id"),
        "capture_mode": last.get("capture_mode"),
        "base_interval_ms": last.get("base_interval_ms"),
        "collector_window_deltas": deltas,
        "final_snapshot": last,
    }


def summarize(
    samples: list[dict[str, Any]],
    *,
    duration_requested_seconds: float,
    interval_requested_seconds: float,
    clock_ticks_per_second: int | None,
    logcat: str,
) -> dict[str, Any]:
    pids = [sample["pid"] for sample in samples if sample.get("pid")]
    elapsed = samples[-1]["elapsed_seconds"] - samples[0]["elapsed_seconds"] if len(samples) > 1 else 0
    pss = finite_values(samples, ("meminfo", "total_pss_kib"))
    rss = finite_values(samples, ("proc", "vm_rss_kib"))
    hwm = finite_values(samples, ("proc", "vm_hwm_kib"))
    battery_level = finite_values(samples, ("battery", "level_percent"))
    charge_counter = finite_values(samples, ("battery", "charge_counter_uah"))
    battery_temp = finite_values(samples, ("battery", "temperature_tenths_c"))
    skin_temp = finite_values(samples, ("thermal", "skin", "celsius"))
    thermal_status = finite_values(samples, ("thermal", "status"))
    cpu_ticks = finite_values(samples, ("proc", "cpu_ticks"))
    cpu_single_core_percent = None
    if len(cpu_ticks) >= 2 and elapsed > 0 and clock_ticks_per_second:
        cpu_single_core_percent = (
            (cpu_ticks[-1] - cpu_ticks[0]) / clock_ticks_per_second / elapsed * 100.0
        )
    crash_patterns = {
        "fatal_exception": r"FATAL EXCEPTION",
        "anr": r"\bANR in\b|Input dispatching timed out",
        "native_fatal_signal": r"Fatal signal \d+",
        "out_of_memory": r"OutOfMemoryError|lowmemorykiller.*screen",
    }
    failures = {
        name: len(re.findall(pattern, logcat, re.IGNORECASE))
        for name, pattern in crash_patterns.items()
    }
    performance = performance_delta(samples)
    memory_growth_limit_kib = 64 * 1024
    pss_growth = pss[-1] - pss[0] if len(pss) >= 2 else None
    facts = {
        "requested_duration_seconds": duration_requested_seconds,
        "observed_duration_seconds": elapsed,
        "requested_sample_interval_seconds": interval_requested_seconds,
        "sample_count": len(samples),
        "unique_pids": sorted(set(pids)),
        "pid_changes": sum(1 for left, right in zip(pids, pids[1:]) if left != right),
        "service_present_samples": sum(bool(sample["service_present"]) for sample in samples),
        "projection_present_samples": sum(bool(sample["projection_present"]) for sample in samples),
        "virtual_display_present_samples": sum(
            bool(sample["virtual_display_present"]) for sample in samples
        ),
        "pss_kib": series_summary(pss),
        "rss_kib": series_summary(rss),
        "vm_hwm_kib": series_summary(hwm),
        "battery_level_percent": series_summary(battery_level),
        "charge_counter_uah": series_summary(charge_counter),
        "battery_temperature_c": series_summary([value / 10.0 for value in battery_temp]),
        "skin_temperature_c": series_summary(skin_temp),
        "thermal_status": series_summary(thermal_status),
        "process_cpu_single_core_percent": cpu_single_core_percent,
        "clock_ticks_per_second": clock_ticks_per_second,
        "log_failures": failures,
        "performance": performance,
    }
    telemetry_deltas = (performance or {}).get("collector_window_deltas", {})
    acceptance = {
        "duration_met": elapsed >= max(0.0, duration_requested_seconds - interval_requested_seconds),
        "single_process": len(set(pids)) == 1 and len(pids) == len(samples),
        "service_continuous": facts["service_present_samples"] == len(samples),
        "projection_continuous": facts["projection_present_samples"] == len(samples),
        "virtual_display_continuous": facts["virtual_display_present_samples"] == len(samples),
        "thermal_status_at_most_1": bool(thermal_status) and max(thermal_status) <= 1,
        "pss_end_growth_at_most_64_mib": pss_growth is not None
        and pss_growth <= memory_growth_limit_kib,
        "no_crash_anr_oom": all(count == 0 for count in failures.values()),
        "telemetry_session_consistent": bool(performance)
        and performance.get("session_id_consistent") is True,
        "telemetry_processing_errors_zero": telemetry_deltas.get("processing_errors") == 0,
        "telemetry_ocr_failures_zero": telemetry_deltas.get("ocr_failures") == 0,
    }
    return {"schema_version": 1, "facts": facts, "acceptance": acceptance}


def package_metadata(adb: Adb, package: str) -> dict[str, Any]:
    dumpsys = adb.shell("dumpsys", "package", package, timeout=45.0)
    path_output = adb.shell("pm", "path", package).strip()
    apk_path = path_output.split("package:", 1)[1] if path_output.startswith("package:") else None
    apk_sha = None
    if apk_path:
        hash_output = adb.shell("sha256sum", apk_path).strip()
        if re.match(r"^[0-9a-fA-F]{64}\s", hash_output):
            apk_sha = hash_output.split()[0].lower()
    return {
        "package": package,
        "version_name": re.search(r"versionName=([^\s]+)", dumpsys).group(1),
        "version_code": int(re.search(r"versionCode=(\d+)", dumpsys).group(1)),
        "apk_path": apk_path,
        "apk_sha256": apk_sha,
    }


def collect(args: argparse.Namespace) -> None:
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    adb = Adb(args.adb.resolve(), args.serial)
    if adb.call("get-state").strip() != "device":
        raise RuntimeError(f"ADB device {args.serial} is not in device state")
    if args.clear_logcat:
        adb.call("logcat", "-c")
    metadata = {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "mode": args.mode,
        "source_sha": args.source_sha,
        "serial": args.serial,
        "device": {
            "model": adb.shell("getprop", "ro.product.model").strip(),
            "device": adb.shell("getprop", "ro.product.device").strip(),
            "android": adb.shell("getprop", "ro.build.version.release").strip(),
            "sdk": int(adb.shell("getprop", "ro.build.version.sdk").strip()),
            "rom": adb.shell("getprop", "ro.mi.os.version.name").strip(),
            "rom_build": adb.shell("getprop", "ro.build.version.incremental").strip(),
            "security_patch": adb.shell("getprop", "ro.build.version.security_patch").strip(),
        },
        "app": package_metadata(adb, args.package),
        "fixture": package_metadata(adb, args.fixture_package)
        if args.fixture_package
        else None,
        "collector": {
            "duration_seconds": args.duration_seconds,
            "interval_seconds": args.interval_seconds,
            "script_sha256": sha256(Path(__file__).resolve()),
        },
    }
    if args.expected_apk_sha256 and metadata["app"]["apk_sha256"] != args.expected_apk_sha256:
        raise RuntimeError(
            "Installed APK hash mismatch: "
            f"{metadata['app']['apk_sha256']} != {args.expected_apk_sha256}"
        )
    (output / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    deadline = time.monotonic() + args.wait_for_service_seconds
    while time.monotonic() < deadline:
        services = adb.shell("dumpsys", "activity", "services", args.package, timeout=45.0)
        if "ScreenTranslationService" in services:
            break
        print("waiting_for_capture_service", flush=True)
        time.sleep(2.0)
    else:
        raise RuntimeError("Capture service did not appear before the wait deadline")

    samples: list[dict[str, Any]] = []
    samples_path = output / "samples.jsonl"
    started = time.monotonic()
    next_sample = started
    sample_index = 0
    with samples_path.open("w", encoding="utf-8", newline="\n") as stream:
        while True:
            now = time.monotonic()
            if now < next_sample:
                time.sleep(next_sample - now)
            elapsed = time.monotonic() - started
            sample = collect_sample(adb, args.package, elapsed)
            sample["sample_index"] = sample_index
            samples.append(sample)
            stream.write(json.dumps(sample, ensure_ascii=False, separators=(",", ":")) + "\n")
            stream.flush()
            perf = sample.get("performance") or {}
            skin = ((sample.get("thermal") or {}).get("skin") or {}).get("celsius")
            print(
                "sample={:03d} elapsed={:7.1f}s pid={} pss={}KiB rss={}KiB hwm={}KiB "
                "skin={}C thermal={} ocr={} interval={}ms".format(
                    sample_index,
                    elapsed,
                    sample.get("pid"),
                    sample["meminfo"].get("total_pss_kib"),
                    sample["proc"].get("vm_rss_kib"),
                    sample["proc"].get("vm_hwm_kib"),
                    skin,
                    sample["thermal"].get("status"),
                    perf.get("ocr_calls"),
                    perf.get("current_interval_ms"),
                ),
                flush=True,
            )
            if elapsed >= args.duration_seconds:
                break
            sample_index += 1
            next_sample = started + sample_index * args.interval_seconds

    logcat = adb.call("logcat", "-d", "-v", "threadtime", timeout=60.0, check=False)
    (output / "logcat.txt").write_text(logcat, encoding="utf-8", newline="\n")
    clock_ticks_text = adb.shell("getconf", "CLK_TCK", check=False).strip()
    clock_ticks = int(clock_ticks_text) if clock_ticks_text.isdigit() else None
    summary = summarize(
        samples,
        duration_requested_seconds=args.duration_seconds,
        interval_requested_seconds=args.interval_seconds,
        clock_ticks_per_second=clock_ticks,
        logcat=logcat,
    )
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    manifest = {}
    for path in sorted(output.iterdir()):
        if path.is_file() and path.name != "manifest-sha256.json":
            manifest[path.name] = {"bytes": path.stat().st_size, "sha256": sha256(path)}
    (output / "manifest-sha256.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--adb", type=Path, required=True)
    result.add_argument("--serial", required=True)
    result.add_argument("--package", required=True)
    result.add_argument("--fixture-package")
    result.add_argument("--mode", choices=("region", "full_screen_incremental"), required=True)
    result.add_argument("--duration-seconds", type=float, default=900.0)
    result.add_argument("--interval-seconds", type=float, default=15.0)
    result.add_argument("--wait-for-service-seconds", type=float, default=120.0)
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--source-sha")
    result.add_argument("--expected-apk-sha256")
    result.add_argument("--clear-logcat", action="store_true")
    return result


def main() -> None:
    args = parser().parse_args()
    if args.duration_seconds <= 0 or args.interval_seconds <= 0:
        raise SystemExit("duration and interval must be positive")
    collect(args)


if __name__ == "__main__":
    main()
