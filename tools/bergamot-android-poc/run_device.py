#!/usr/bin/env python3
"""Run one or more pinned Bergamot stages on Android and emit benchmark JSON."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import shlex
import shutil
import statistics
import subprocess
import time
from collections import defaultdict
from pathlib import Path
from typing import Any


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


def verified_model_stage(
    model_directory: Path,
    config_override: str | None,
) -> dict[str, Any]:
    manifest_path = model_directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if int(manifest.get("schema_version", 0)) != 2:
        raise RuntimeError(
            f"{manifest_path} is not a v2 model manifest; rerun the fetcher"
        )

    runtime = manifest["runtime"]
    config_name = config_override or str(runtime["config"])
    runtime_asset_names = list(
        dict.fromkeys(
            (
                str(runtime["model"]),
                *(str(name) for name in runtime["vocabs"]),
                str(runtime["shortlist"]),
            )
        )
    )
    runtime_file_names = [*runtime_asset_names, config_name]
    expected = {entry["name"]: entry for entry in manifest["files"]}
    files: list[dict[str, Any]] = []
    for name in runtime_file_names:
        path = model_directory / name
        if name not in expected:
            raise RuntimeError(f"{name} is absent from {manifest_path}")
        if not path.is_file():
            raise RuntimeError(f"Runtime file is missing: {path}")
        entry = expected[name]
        actual_size = path.stat().st_size
        actual_sha256 = sha256(path)
        if actual_size != int(entry["size_bytes"]):
            raise RuntimeError(f"Size mismatch for {path}")
        if actual_sha256.lower() != str(entry["sha256"]).lower():
            raise RuntimeError(f"SHA-256 mismatch for {path}")
        files.append(
            {
                "name": name,
                "size_bytes": actual_size,
                "sha256": actual_sha256,
            }
        )

    return {
        "directory": model_directory,
        "manifest_path": manifest_path,
        "pair": str(manifest["pair"]),
        "source_language": str(manifest["source_language"]),
        "target_language": str(manifest["target_language"]),
        "architecture": str(manifest["architecture"]),
        "release_status": str(manifest["release_status"]),
        "model_snapshot": str(manifest["model_snapshot"]),
        "config_name": config_name,
        "runtime_asset_names": runtime_asset_names,
        "files": files,
    }


def verify_model_chain(
    baseline: dict[str, Any],
    stages: list[dict[str, Any]],
) -> list[str]:
    engines = baseline["engines"]
    source_language = str(engines["source_language"])
    target_language = str(engines["target_language"])
    if stages[0]["source_language"] != source_language:
        raise RuntimeError(
            "First model source does not match benchmark source: "
            f"{stages[0]['source_language']} != {source_language}"
        )

    route = [source_language]
    for index, stage in enumerate(stages):
        if route[-1] != stage["source_language"]:
            raise RuntimeError(
                f"Model chain breaks before stage {index}: "
                f"{route[-1]} != {stage['source_language']}"
            )
        route.append(stage["target_language"])
    if route[-1] != target_language:
        raise RuntimeError(
            "Last model target does not match benchmark target: "
            f"{route[-1]} != {target_language}"
        )
    return route


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


def stable_intermediate_outputs(
    records: list[dict[str, Any]],
) -> list[list[str]]:
    outputs = [
        [
            [str(value) for value in stage]
            for stage in record.get("intermediate_outputs", [])
        ]
        for record in records
    ]
    if any(output != outputs[0] for output in outputs[1:]):
        raise RuntimeError(
            "Bergamot intermediate output changed between repetitions"
        )
    return outputs[0]


def verify_remote_hashes(
    output: str,
    expected: dict[str, str],
) -> list[str]:
    actual: dict[str, str] = {}
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    for line in lines:
        fields = line.split(maxsplit=1)
        if len(fields) != 2:
            raise RuntimeError(f"Invalid remote sha256sum output: {line}")
        digest, path = fields
        if path in actual:
            raise RuntimeError(f"Duplicate remote SHA-256 path: {path}")
        actual[path] = digest.lower()

    missing = sorted(set(expected) - set(actual))
    unexpected = sorted(set(actual) - set(expected))
    mismatched = sorted(
        path
        for path in set(expected) & set(actual)
        if expected[path].lower() != actual[path]
    )
    if missing or unexpected or mismatched:
        raise RuntimeError(
            "Remote SHA-256 verification failed: "
            f"missing={missing}, unexpected={unexpected}, "
            f"mismatched={mismatched}"
        )
    return lines


def assemble_candidate(
    baseline: dict[str, Any],
    *,
    measurements: dict[str, list[dict[str, Any]]],
    runtime_meta: dict[str, Any],
    runtime_summary: dict[str, Any],
    binary: Path,
    stages: list[dict[str, Any]],
    route: list[str],
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
    pairs = " + ".join(stage["pair"] for stage in stages)
    candidate["engines"]["translation"] = (
        f"Mozilla Firefox Translations {pairs} base-memory int8Alpha / "
        f"Bergamot {runtime_meta['bergamot_version']} / Android ARM64 Ruy"
    )

    model_stages = []
    model_assets_bytes = 0
    for index, stage in enumerate(stages):
        asset_names = set(stage["runtime_asset_names"])
        model_assets_bytes += sum(
            int(entry["size_bytes"])
            for entry in stage["files"]
            if entry["name"] in asset_names
        )
        model_stages.append(
            {
                "index": index,
                "pair": stage["pair"],
                "source_language": stage["source_language"],
                "target_language": stage["target_language"],
                "architecture": stage["architecture"],
                "release_status": stage["release_status"],
                "model_snapshot": stage["model_snapshot"],
                "directory": str(stage["directory"].resolve()),
                "manifest": str(stage["manifest_path"].resolve()),
                "config_name": stage["config_name"],
                "files": stage["files"],
            }
        )

    candidate["method"] = {
        "translation_only": True,
        "translation_repetitions": runtime_meta["repetitions"],
        "latency_clock": "std::chrono::steady_clock in native process",
        "translation_route": route,
        "runtime": runtime_meta,
        "runtime_summary": runtime_summary,
        "binary": {
            "path": str(binary.resolve()),
            "size_bytes": binary.stat().st_size,
            "sha256": sha256(binary),
        },
        "model_stages": model_stages,
        "model_assets_bytes": model_assets_bytes,
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
        raw_intermediate = stable_intermediate_outputs(raw_records)
        pipeline_intermediate = stable_intermediate_outputs(pipeline_records)
        if len(raw_outputs) != 1:
            raise RuntimeError(f"Raw group has multiple outputs: {identifier}")

        raw_latencies = [
            float(record["latency_ms"]) for record in raw_records
        ]
        pipeline_latencies = [
            float(record["latency_ms"]) for record in pipeline_records
        ]
        raw_result = {
            "output_text": raw_outputs[0],
            "latencies_ms": raw_latencies,
            "median_latency_ms": statistics.median(raw_latencies),
        }
        pipeline_result = {
            "parts": case["translation_pipeline"]["parts"],
            "part_outputs": pipeline_outputs,
            "output_text": " ".join(pipeline_outputs),
            "latencies_ms": pipeline_latencies,
            "median_latency_ms": statistics.median(pipeline_latencies),
        }
        if raw_intermediate:
            raw_result["pivot_outputs"] = raw_intermediate[0]
            raw_result["intermediate_outputs"] = raw_intermediate
        if pipeline_intermediate:
            pipeline_result["pivot_outputs"] = pipeline_intermediate[0]
            pipeline_result["intermediate_outputs"] = pipeline_intermediate
        case["translation_raw"] = raw_result
        case["translation_pipeline"] = pipeline_result
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
    parser.add_argument(
        "--model-dir",
        type=Path,
        action="append",
        required=True,
        help="Pinned model directory; repeat in translation order",
    )
    parser.add_argument(
        "--config-name",
        action="append",
        help="Optional manifest-listed config; repeat once per model directory",
    )
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
    if args.config_name and len(args.config_name) != len(args.model_dir):
        parser.error("Repeat --config-name once per --model-dir, or omit it")

    baseline_path = args.baseline_json.resolve()
    binary = args.binary.resolve()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))

    config_overrides = args.config_name or [None] * len(args.model_dir)
    stages = [
        verified_model_stage(directory.resolve(), config_override)
        for directory, config_override in zip(
            args.model_dir,
            config_overrides,
        )
    ]
    route = verify_model_chain(baseline, stages)

    adb = locate_adb(args.adb)
    devices = adb_text(adb, "devices")
    if "\tdevice" not in devices:
        raise RuntimeError(f"No ready Android device:\n{devices}")

    artifact_prefix = output.stem
    groups_path = output.with_name(f"{artifact_prefix}-groups.tsv")
    raw_output_path = output.with_name(f"{artifact_prefix}-runtime.ndjson")
    stderr_path = output.with_name(f"{artifact_prefix}-runtime.stderr.txt")
    before_path = output.with_name(f"{artifact_prefix}-device-before.txt")
    after_path = output.with_name(f"{artifact_prefix}-device-after.txt")
    write_groups(baseline, groups_path)
    before_path.write_text(device_state(adb), encoding="utf-8")

    remote = args.remote_dir.rstrip("/")
    adb_text(adb, "shell", f"mkdir -p {shlex.quote(remote)}")
    remote_binary = f"{remote}/bergamot-android-benchmark"
    remote_groups = f"{remote}/groups.tsv"
    adb_text(adb, "push", str(binary), remote_binary)
    adb_text(adb, "shell", f"chmod 755 {shlex.quote(remote_binary)}")
    adb_text(adb, "push", str(groups_path), remote_groups)

    remote_files = [remote_binary]
    expected_remote_hashes = {remote_binary: sha256(binary)}
    remote_configs = []
    for index, stage in enumerate(stages):
        remote_stage = f"{remote}/model-{index}-{stage['pair']}"
        adb_text(adb, "shell", f"mkdir -p {shlex.quote(remote_stage)}")
        for entry in stage["files"]:
            name = str(entry["name"])
            remote_path = f"{remote_stage}/{name}"
            adb_text(
                adb,
                "push",
                str(stage["directory"] / name),
                remote_path,
            )
            remote_files.append(remote_path)
            expected_remote_hashes[remote_path] = str(entry["sha256"]).lower()
        remote_configs.append(f"{remote_stage}/{stage['config_name']}")

    remote_hash_output = adb_text(
        adb,
        "shell",
        "toybox sha256sum "
        + " ".join(shlex.quote(path) for path in remote_files),
    )
    remote_hashes = verify_remote_hashes(
        remote_hash_output,
        expected_remote_hashes,
    )

    warmup_text = (
        "これはウォームアップ用の文です。"
        if route[0] == "ja"
        else "This is a warm-up sentence."
    )
    command_arguments = [
        remote_binary,
        *(
            argument
            for config_path in remote_configs
            for argument in ("--config", config_path)
        ),
        "--input",
        remote_groups,
        "--repetitions",
        str(args.repetitions),
        "--workers",
        str(args.workers),
        "--service",
        args.service,
        "--warmup-text",
        warmup_text,
    ]
    command = " ".join(shlex.quote(value) for value in command_arguments)
    completed = run(
        [adb, "shell", command],
        timeout=1_800,
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
    if int(runtime_meta.get("stages", 0)) != len(stages):
        raise RuntimeError("Native runner stage count does not match model chain")
    candidate = assemble_candidate(
        baseline,
        measurements=measurements,
        runtime_meta=runtime_meta,
        runtime_summary=runtime_summary,
        binary=binary,
        stages=stages,
        route=route,
        device=device_metadata(adb),
        baseline_path=baseline_path,
        raw_output_path=raw_output_path,
    )
    candidate["method"]["remote_sha256"] = remote_hashes
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
                "route": route,
                "runtime": runtime_meta,
                "summary": runtime_summary,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
