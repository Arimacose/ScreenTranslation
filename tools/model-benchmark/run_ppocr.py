#!/usr/bin/env python3
"""Run a PP-OCRv6 tier on the exact PNGs emitted by the Android baseline."""

from __future__ import annotations

import argparse
import copy
import json
import platform
import statistics
import time
from pathlib import Path
from typing import Any

import numpy as np
import paddle
import paddleocr
import psutil
from paddleocr import PaddleOCR


def unwrap_result(result: Any) -> dict[str, Any]:
    if isinstance(result, dict):
        payload = result
    else:
        payload = getattr(result, "json", None)
        if callable(payload):
            payload = payload()
        if payload is None:
            try:
                payload = dict(result)
            except (TypeError, ValueError) as error:
                raise TypeError(f"Unsupported PaddleOCR result: {type(result)!r}") from error
    if "res" in payload and isinstance(payload["res"], dict):
        payload = payload["res"]
    return payload


def ordered_lines(payload: dict[str, Any]) -> tuple[list[str], list[float]]:
    texts = [str(value) for value in payload.get("rec_texts", [])]
    scores = [float(value) for value in payload.get("rec_scores", [])]
    polygons = payload.get("rec_polys")
    if polygons is None or len(polygons) != len(texts):
        return texts, scores

    items = []
    for index, (text, score, polygon) in enumerate(zip(texts, scores, polygons)):
        points = np.asarray(polygon, dtype=float)
        items.append(
            (
                float(points[:, 1].mean()),
                float(points[:, 0].mean()),
                index,
                text,
                score,
            )
        )
    items.sort()
    return [item[3] for item in items], [item[4] for item in items]


def predict(
    engine: PaddleOCR,
    image_path: Path,
) -> tuple[list[str], list[float], float]:
    started = time.perf_counter_ns()
    results = list(engine.predict(str(image_path)))
    latency_ms = (time.perf_counter_ns() - started) / 1_000_000
    if len(results) != 1:
        raise RuntimeError(f"Expected one result for {image_path}, got {len(results)}")
    payload = unwrap_result(results[0])
    lines, scores = ordered_lines(payload)
    return lines, scores, latency_ms


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline_json", type=Path)
    parser.add_argument("--tier", choices=("tiny", "small", "medium"), default="small")
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    baseline = json.loads(args.baseline_json.read_text(encoding="utf-8"))
    image_directory = args.baseline_json.parent
    detection_model = f"PP-OCRv6_{args.tier}_det"
    recognition_model = f"PP-OCRv6_{args.tier}_rec"
    initialization_started = time.perf_counter_ns()
    engine = PaddleOCR(
        text_detection_model_name=detection_model,
        text_recognition_model_name=recognition_model,
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        lang="en",
        ocr_version="PP-OCRv6",
        device=args.device,
        enable_mkldnn=False,
    )
    initialization_ms = (time.perf_counter_ns() - initialization_started) / 1_000_000

    first_image = image_directory / baseline["cases"][0]["render"]["image_file"]
    predict(engine, first_image)

    output_cases = []
    for baseline_case in baseline["cases"]:
        image_path = image_directory / baseline_case["render"]["image_file"]
        latencies: list[float] = []
        lines: list[str] = []
        scores: list[float] = []
        for _ in range(args.repetitions):
            lines, scores, latency_ms = predict(engine, image_path)
            latencies.append(latency_ms)

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
        output_case["ocr"] = {
            "output_text": "\n".join(lines),
            "blocks": lines,
            "confidence_scores": scores,
            "latencies_ms": latencies,
            "median_latency_ms": statistics.median(latencies),
        }
        output_cases.append(output_case)

    process = psutil.Process()
    result = {
        "schema_version": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "device": {
            "kind": "host_preintegration",
            "platform": platform.platform(),
            "processor": platform.processor(),
            "machine": platform.machine(),
        },
        "engines": {
            "ocr": f"PaddleOCR {paddleocr.__version__} / {detection_model} + {recognition_model}",
            "paddle": paddle.__version__,
            "source_language": "en",
        },
        "method": {
            "input_contract": "Exact PNG files emitted by Android ML Kit baseline",
            "repetitions": args.repetitions,
            "device": args.device,
            "enable_mkldnn": False,
            "initialization_ms": initialization_ms,
            "process_rss_bytes_after_run": process.memory_info().rss,
            "latency_warning": (
                "Host latency is not comparable to Xiaomi 15 Pro latency; "
                "quality metrics are directly comparable because PNGs are identical."
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
