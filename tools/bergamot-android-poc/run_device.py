#!/usr/bin/env python3
"""Run the pinned Bergamot candidate on Android and emit benchmark JSON."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import shutil
import statistics
import subprocess
import time
from collections import defaultdict
from pathlib import Path
from typing import Any


RUNTIME_ASSETS = (
    "model.enzh.intgemm.alphas.bin",
    "srcvocab.enzh.spm",
    "trgvocab.enzh.spm",
    "lex.50.50.enzh.s2t.bin",
)
DEFAULT_CONFIG = "decoder.bergamot-beam4.yml"
DEFAULT_REMOTE = "/data/local/tmp/screentranslation-bergamot-poc-9271618"
CHUNK_SIZE = 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(CHUNK_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def run(
    arguments: list[str],
    *,
    timeout: int = 120,
    capture: bool = True,
) -> subprocess.CompletedProcess[bytes]:
    completed = subprocess.run(
        arguments,
        check=False,
        capture_output=capture,
        timeout=timeout,
    )
    if completed.returncode != 0:
        stdout = completed.stdout.decode("utf-8", errors="replace")
        stderr = completed.stderr.decode("utf-8", errors="replace")
        raise RuntimeError(
            f"Command failed ({completed.returncode}): {arguments}\n"
            f"stdout:\n{stdout}\nstderr:\n{stderr}"
        )
    return completed


def adb_text(adb: str, *arguments: str, timeout: int = 120) -> str:
    completed = run([adb, *arguments], timeout=timeout)
    return completed.stdout.decode("utf-8", errors="replace").strip()


def locate_adb(explicit: str | None) -> str:
    if explicit:
        return explicit
    discovered = shutil.which("adb")
    if discovered:
        return discovered
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        candidate = Path(local_app_data) / "Android" / "platform-tools" / "adb.exe"
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError("ADB was not found; pass --adb")


def verified_model_files(
    model_directory: Path,
    config_name: str,
) -> list[dict[str, Any]]:
    manifest_path = model_directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected = {entry["name"]: entry for entry in manifest["files"]}
    files = [*RUNTIME_ASSETS, config_name]
    metadata: list[dict[str, Any]] = []
    for name in files:
        path = model_directory / name
        if name not in expected:
            raise RuntimeError(f"{name} is absent from {manifest_path}")
        entry = expected[name]
        actual_size = path.stat().st_size
        actual_sha256 = sha256(path)
        if actual_size != int(entry["size_bytes"]):
            raise RuntimeError(f"Size mismatch for {path}")
        if actual_sha256.lower() != str(entry["sha256"]).lower():
            raise RuntimeError(f"SHA-256 mismatch for {path}")
        metadata.append(
            {
                "name": name,
                "size_bytes": actual_size,
                "sha256": actual_sha256,
            }
        )
    return metadata


def normalize_tsv_text(value: str) -> str:
    return " ".join(value.replace("\t", " ").splitlines()).strip()


def write_groups(baseline: dict[str, Any], path: Path) -> None:
    lines: list[str] = []
    for case in baseline["cases"]:
        identifier = str(case["id"])
        lines.append(
            f"raw:{identifier}\t{normalize_tsv_text(case['source_text'])}"
        )
        pipeline = case.get("translation_pipeline", {})
        parts = pipeline.get("parts") or [case["source_text"]]
        for part in parts:
            lines.append(
                f"pipeline:{identifier}\t{normalize_tsv_text(str(part))}"
            )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def parse_runtime(
    output: str,
    repetitions: int,
) -> tuple[dict[str, Any], dict[str, list[dict[str, Any]]], dict[str, Any]]:
    meta: dict[str, Any] | None = None
    summary: dict[str, Any] | None = None
    measurements: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for line_number, line in enumerate(output.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            raise RuntimeError(
                f"Invalid runner NDJSON at line {line_number}: {line}"
            ) from error
        kind = record.get("kind")
        if kind == "meta":
            meta = record
        elif kind == "measurement":
            measurements[str(record["group_id"])].append(record)
        elif kind == "summary":
            summary = record
        elif kind == "error":
            raise RuntimeError(f"Device runner error: {record.get('message')}")
        else:
            raise RuntimeError(f"Unknown runner record: {record}")

    if meta is None or summary is None:
        raise RuntimeError("Runner output is missing meta or summary")
    for group_id, records in measurements.items():
        if len(records) != repetitions:
            raise RuntimeError(
                f"{group_id} has {len(records)} measurements, expected {repetitions}"
            )
        expected_repetitions = list(range(repetitions))
        actual_repetitions = sorted(int(record["repetition"]) for record in records)
        if actual_repetitions != expected_repetitions:
            raise RuntimeError(f"Unexpected repetitions for {group_id}")
    return meta, measurements, summary


def stable_outputs(records: list[dict[str, Any]]) -> list[str]:
    outputs = [[str(value) for value in record["outputs"]] for record in records]
    if any(output != outputs[0] for output in outputs[1:]):
        raise RuntimeError("Bergamot output changed between repetitions")
    return outputs[0]


def assemble_candidate(
    baseline: dict[str, Any],
    *,
    measurements: dict[str, list[dict[str, Any]]],
    runtime_meta: dict[str, Any],
    runtime_summary: dict[str, Any],
    binary: Path,
    model_files: list[dict[str, Any]],
    device: dict[str, Any],
    baseline_path: Path,
    raw_output_path: Path,
) -> dict[str, Any]:
    candidate = copy.deepcopy(baseline)
    candidate["generated_at"] = time.strftime(
        "%Y-%m-%dT%H:%M:%SZ",
        time.gmtime(),
    )
    candidate["device"] = device
    candidate["engines"]["translation"] = (
        "Mozilla Firefox Translations en-zh base-memory int8Alpha / "
        f"Bergamot {runtime_meta['bergamot_version']} / Android ARM64 Ruy"
    )
    candidate["method"] = {
        "translation_only": True,
        "translation_repetitions": runtime_meta["repetitions"],
        "latency_clock": "std::chrono::steady_clock in native process",
        "runtime": runtime_meta,
        "runtime_summary": runtime_summary,
        "binary": {
            "path": str(binary.resolve()),
            "size_bytes": binary.stat().st_size,
            "sha256": sha256(binary),
        },
        "model_files": model_files,
        "model_assets_bytes": sum(
            int(entry["size_bytes"])
            for entry in model_files
            if entry["name"] in RUNTIME_ASSETS
        ),
        "baseline_json": str(baseline_path.resolve()),
        "raw_ndjson": str(raw_output_path.resolve()),
        "pipeline_batching": (
            "All ClauseSplitter parts for one fixture are queued together and "
            "responses are reassembled in input order."
        ),
    }

    for case in candidate["cases"]:
        identifier = str(case["id"])
        raw_records = measurements[f"raw:{identifier}"]
        pipeline_records = measurements[f"pipeline:{identifier}"]
        if not raw_records or not pipeline_records:
            raise RuntimeError(f"Runner output is missing case {identifier}")

        raw_outputs = stable_outputs(raw_records)
        pipeline_outputs = stable_outputs(pipeline_records)
        if len(raw_outputs) != 1:
            raise RuntimeError(f"Raw group has multiple outputs: {identifier}")

        raw_latencies = [
            float(record["latency_ms"]) for record in raw_records
        ]
        pipeline_latencies = [
            float(record["latency_ms"]) for record in pipeline_records
        ]
        case["translation_raw"] = {
            "output_text": raw_outputs[0],
            "latencies_ms": raw_latencies,
            "median_latency_ms": statistics.median(raw_latencies),
        }
        case["translation_pipeline"] = {
            "parts": case["translation_pipeline"]["parts"],
            "part_outputs": pipeline_outputs,
            "output_text": " ".join(pipeline_outputs),
            "latencies_ms": pipeline_latencies,
            "median_latency_ms": statistics.median(pipeline_latencies),
        }
        case.pop("end_to_end", None)
    return candidate


def device_metadata(adb: str) -> dict[str, Any]:
    def prop(name: str) -> str:
        return adb_text(adb, "shell", "getprop", name)

    hyperos_incremental = prop("ro.mi.os.version.incremental")
    return {
        "kind": "android_arm64_native",
        "manufacturer": prop("ro.product.manufacturer"),
        "model": prop("ro.product.model"),
        "product": prop("ro.product.name"),
        "android": prop("ro.build.version.release"),
        "sdk": int(prop("ro.build.version.sdk")),
        "display_build": prop("ro.build.display.id"),
        "hyperos_version": (
            hyperos_incremental or prop("ro.mi.os.version.name")
        ),
        "abi": prop("ro.product.cpu.abi"),
        "serial": adb_text(adb, "get-serialno"),
    }


def device_state(adb: str) -> str:
    commands = (
        "date -Iseconds",
        "uptime",
        "dumpsys battery | grep -E 'level:|temperature:|status:'",
        "dumpsys thermalservice | grep -E 'Thermal Status|mValue='",
        "cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || true",
    )
    return "\n".join(
        f"$ {command}\n{adb_text(adb, 'shell', command)}"
        for command in commands
    ) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline_json", type=Path)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--config-name", default=DEFAULT_CONFIG)
    parser.add_argument("--adb")
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument(
        "--service",
        choices=("blocking", "async"),
        default="blocking",
    )
    parser.add_argument("--remote-dir", default=DEFAULT_REMOTE)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.repetitions < 1 or args.workers < 1:
        parser.error("--repetitions and --workers must be positive")
    if args.service == "blocking" and args.workers != 1:
        parser.error("--workers must be 1 when --service is blocking")
    baseline_path = args.baseline_json.resolve()
    binary = args.binary.resolve()
    model_directory = args.model_dir.resolve()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    model_files = verified_model_files(model_directory, args.config_name)
    adb = locate_adb(args.adb)
    devices = adb_text(adb, "devices")
    if "\tdevice" not in devices:
        raise RuntimeError(f"No ready Android device:\n{devices}")

    groups_path = output.with_name("bergamot-groups.tsv")
    raw_output_path = output.with_name("bergamot-runtime.ndjson")
    stderr_path = output.with_name("bergamot-runtime.stderr.txt")
    before_path = output.with_name("device-state-before.txt")
    after_path = output.with_name("device-state-after.txt")
    write_groups(baseline, groups_path)
    before_path.write_text(device_state(adb), encoding="utf-8")

    remote = args.remote_dir.rstrip("/")
    remote_model = f"{remote}/model"
    adb_text(adb, "shell", f"mkdir -p {remote_model}")
    adb_text(adb, "push", str(binary), f"{remote}/bergamot-android-benchmark")
    adb_text(adb, "shell", f"chmod 755 {remote}/bergamot-android-benchmark")
    adb_text(adb, "push", str(groups_path), f"{remote}/groups.tsv")
    for entry in model_files:
        name = str(entry["name"])
        adb_text(adb, "push", str(model_directory / name), f"{remote_model}/{name}")

    remote_hashes = adb_text(
        adb,
        "shell",
        "toybox sha256sum "
        + " ".join(
            (
                f"{remote}/bergamot-android-benchmark",
                *(f"{remote_model}/{entry['name']}" for entry in model_files),
            )
        ),
    )

    command = (
        f"cd {remote_model} && ../bergamot-android-benchmark "
        f"--config {args.config_name} --input ../groups.tsv "
        f"--repetitions {args.repetitions} --workers {args.workers} "
        f"--service {args.service}"
    )
    completed = run(
        [adb, "shell", command],
        timeout=900,
    )
    raw_output = completed.stdout.decode("utf-8", errors="strict")
    stderr = completed.stderr.decode("utf-8", errors="replace")
    raw_output_path.write_text(raw_output, encoding="utf-8", newline="\n")
    stderr_path.write_text(stderr, encoding="utf-8", newline="\n")
    after_path.write_text(device_state(adb), encoding="utf-8")

    runtime_meta, measurements, runtime_summary = parse_runtime(
        raw_output,
        args.repetitions,
    )
    candidate = assemble_candidate(
        baseline,
        measurements=measurements,
        runtime_meta=runtime_meta,
        runtime_summary=runtime_summary,
        binary=binary,
        model_files=model_files,
        device=device_metadata(adb),
        baseline_path=baseline_path,
        raw_output_path=raw_output_path,
    )
    candidate["method"]["remote_sha256"] = remote_hashes.splitlines()
    output.write_text(
        json.dumps(candidate, ensure_ascii=False, indent=2),
        encoding="utf-8",
        newline="\n",
    )

    print(
        json.dumps(
            {
                "output": str(output),
                "sha256": sha256(output),
                "cases": len(candidate["cases"]),
                "runtime": runtime_meta,
                "summary": runtime_summary,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
