#!/usr/bin/env python3
"""Run the OPUS-MT candidate through Bergamot's persistent Python binding."""

from __future__ import annotations

import argparse
import copy
import importlib
import json
import platform
import re
import statistics
import sys
import time
from pathlib import Path
from typing import Any

try:
    import resource
except ModuleNotFoundError:
    resource = None


TARGET_PREFIX = ">>cmn_Hans<<"
MAX_UNSPLIT_LENGTH = 90
MIN_CLAUSE_LENGTH = 20
MARKERS = [
    "; ",
    ", in which case ",
    ", which means ",
    ", although ",
    " although ",
    ", because ",
    " because ",
    ", whereas ",
    " whereas ",
    ", unless ",
    " unless ",
    ", which ",
    ", who ",
    ", where ",
    ", while ",
    ", and ",
    ", but ",
    ", so ",
    ", or ",
    ", yet ",
    " and ",
    " but ",
    " so ",
    " yet ",
]


def normalize_whitespace(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def find_cut(text: str) -> tuple[int, int] | None:
    best: tuple[int, int] | None = None
    lowered = text.lower()
    for marker in MARKERS:
        at = lowered.find(marker.lower(), MIN_CLAUSE_LENGTH)
        if at < 0:
            continue
        skip = 2 if marker.startswith(",") else 1
        right_start = at + skip
        if len(text) - right_start < MIN_CLAUSE_LENGTH:
            continue
        if best is None or at < best[0]:
            best = (at, right_start)
    return best


def split_clause(text: str) -> list[str]:
    trimmed = text.strip()
    if len(trimmed) <= MAX_UNSPLIT_LENGTH:
        return [trimmed]
    pieces: list[str] = []
    rest = trimmed
    while len(rest) > MAX_UNSPLIT_LENGTH:
        cut = find_cut(rest)
        if cut is None:
            break
        pieces.append(rest[: cut[0]].strip())
        rest = rest[cut[1] :].strip()
    if rest:
        pieces.append(rest)
    return pieces if len(pieces) >= 2 else [trimmed]


class Translator:
    def __init__(
        self,
        *,
        binding_dir: Path,
        config: Path,
        workers: int,
        target_prefix: str,
    ) -> None:
        sys.path.insert(0, str(binding_dir.resolve()))
        self.bergamot = importlib.import_module("_bergamot")
        self.target_prefix = target_prefix
        started = time.perf_counter_ns()
        self.service = self.bergamot.Service(
            self.bergamot.ServiceConfig(workers, 0, "off")
        )
        self.model = self.service.modelFromConfigPath(str(config.resolve()))
        self.initialization_ms = (time.perf_counter_ns() - started) / 1_000_000
        self.options = self.bergamot.ResponseOptions(
            False,
            False,
            False,
            False,
            self.bergamot.ConcatStrategy.SPACE,
        )

    @property
    def version(self) -> str:
        return str(self.bergamot.__version__)

    def translate(self, texts: list[str]) -> tuple[list[str], float]:
        inputs = self.bergamot.VectorString()
        for text in texts:
            inputs.append(
                f"{self.target_prefix} {text}"
                if self.target_prefix
                else text
            )
        started = time.perf_counter_ns()
        responses = self.service.translate(self.model, inputs, self.options)
        latency_ms = (time.perf_counter_ns() - started) / 1_000_000
        return (
            [response.target.text.strip() for response in responses],
            latency_ms,
        )


def translate_plan(
    translator: Translator,
    blocks: list[str],
) -> tuple[str, list[str], list[str], float]:
    clauses_by_block = [split_clause(block) for block in blocks]
    parts = [part for block in clauses_by_block for part in block]
    outputs, latency_ms = translator.translate(parts)
    offset = 0
    reassembled_blocks = []
    for clauses in clauses_by_block:
        count = len(clauses)
        reassembled_blocks.append(" ".join(outputs[offset : offset + count]))
        offset += count
    return "\n".join(reassembled_blocks), parts, outputs, latency_ms


def pipeline_parts(case: dict[str, Any]) -> list[str]:
    pipeline = case.get("translation_pipeline")
    if isinstance(pipeline, dict):
        parts = pipeline.get("parts")
        if isinstance(parts, list) and all(isinstance(part, str) for part in parts):
            return list(parts)
    return split_clause(str(case["source_text"]))


def file_metadata(paths: list[Path]) -> list[dict[str, Any]]:
    return [
        {
            "path": str(path.resolve()),
            "size_bytes": path.stat().st_size,
        }
        for path in paths
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline_json", type=Path)
    parser.add_argument("--ocr-json", type=Path, required=True)
    parser.add_argument("--binding-dir", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--model-files", type=Path, nargs="+", required=True)
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--target-prefix", default=TARGET_PREFIX)
    parser.add_argument(
        "--engine-label",
        help="Exact model/runtime label stored in the result JSON.",
    )
    parser.add_argument("--bergamot-commit", default="unknown")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    baseline = json.loads(args.baseline_json.read_text(encoding="utf-8"))
    ocr_candidate = json.loads(args.ocr_json.read_text(encoding="utf-8"))
    candidate_by_id = {case["id"]: case for case in ocr_candidate["cases"]}

    translator = Translator(
        binding_dir=args.binding_dir,
        config=args.config,
        workers=args.workers,
        target_prefix=args.target_prefix,
    )
    _, warmup_ms = translator.translate(["Warm-up sentence."])
    output_cases = []

    for baseline_case in baseline["cases"]:
        candidate_case = candidate_by_id[baseline_case["id"]]
        source = baseline_case["source_text"]

        raw_latencies: list[float] = []
        raw_output = ""
        for _ in range(args.repetitions):
            outputs, latency_ms = translator.translate([source])
            raw_output = outputs[0]
            raw_latencies.append(latency_ms)

        gold_parts = pipeline_parts(baseline_case)
        pipeline_latencies: list[float] = []
        pipeline_outputs: list[str] = []
        for _ in range(args.repetitions):
            pipeline_outputs, latency_ms = translator.translate(gold_parts)
            pipeline_latencies.append(latency_ms)
        pipeline_output = " ".join(pipeline_outputs)

        ocr_text = candidate_case["ocr"]["output_text"]
        normalized_ocr = normalize_whitespace(ocr_text)
        end_output, end_parts, end_part_outputs, end_translation_ms = (
            translate_plan(translator, [normalized_ocr])
        )
        ocr_median_ms = statistics.median(
            float(value) for value in candidate_case["ocr"]["latencies_ms"]
        )

        output_case = {
            key: copy.deepcopy(baseline_case[key])
            for key in (
                "id",
                "source_text",
                "reference_translation",
                "translation_scored",
                "render",
            )
        }
        output_case["ocr"] = copy.deepcopy(candidate_case["ocr"])
        output_case["translation_raw"] = {
            "output_text": raw_output,
            "latencies_ms": raw_latencies,
            "median_latency_ms": statistics.median(raw_latencies),
        }
        output_case["translation_pipeline"] = {
            "parts": gold_parts,
            "part_outputs": pipeline_outputs,
            "output_text": pipeline_output,
            "latencies_ms": pipeline_latencies,
            "median_latency_ms": statistics.median(pipeline_latencies),
        }
        output_case["end_to_end"] = {
            "ocr_text": ocr_text,
            "ocr_blocks": copy.deepcopy(candidate_case["ocr"]["blocks"]),
            "translation_parts": end_parts,
            "translation_part_outputs": end_part_outputs,
            "output_text": end_output,
            "latency_ms": ocr_median_ms + end_translation_ms,
            "latency_estimated_from_separate_ocr_and_translation_runs": True,
        }
        output_cases.append(output_case)

    peak_rss_bytes = (
        resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024
        if resource is not None
        else None
    )
    result: dict[str, Any] = {
        "schema_version": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "device": {
            "kind": "wsl_host_preintegration",
            "platform": platform.platform(),
            "processor": platform.processor(),
            "machine": platform.machine(),
        },
        "engines": {
            "ocr": ocr_candidate["engines"]["ocr"],
            "translation": args.engine_label or (
                f"OPUS-MT en-zho intgemm8 / Bergamot {translator.version}"
            ),
            "source_language": "en",
            "target_language": "zh-Hans",
            "target_prefix": args.target_prefix,
        },
        "method": {
            "bergamot_commit": args.bergamot_commit,
            "configuration": str(args.config.resolve()),
            "workers": args.workers,
            "repetitions": args.repetitions,
            "initialization_ms": translator.initialization_ms,
            "warmup_ms": warmup_ms,
            "process_peak_rss_bytes": peak_rss_bytes,
            "model_files": file_metadata(args.model_files),
            "runtime_scope": (
                "Native x86_64 WSL compatibility and quality benchmark. "
                "Android arm64 latency and thermal behavior are a separate gate."
            ),
        },
        "cases": output_cases,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
