#!/usr/bin/env python3
"""Run TranslateGemma through a pinned external llama.cpp server."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import platform
import statistics
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any

LANGUAGES = {
    "en": "English",
    "ja": "Japanese",
    "zh": "Chinese",
    "zh-Hans": "Chinese",
}
TRAILING_MARKERS = ("<end_of_turn>", "[end of text]", "<|endoftext|>")
DEFAULT_LLAMA_TAG = "b10181"
DEFAULT_LLAMA_COMMIT = "caa596ab3"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(16 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_prompt(text: str, source_code: str, target_code: str) -> str:
    source_name = LANGUAGES[source_code]
    target_name = LANGUAGES[target_code]
    return (
        f"<start_of_turn>user\nYou are a professional {source_name} ({source_code}) to "
        f"{target_name} ({target_code}) translator. Your goal is to accurately convey the "
        f"meaning and nuances of the original {source_name} text while adhering to "
        f"{target_name} grammar, vocabulary, and cultural sensitivities.\n"
        f"Produce only the {target_name} translation, without any additional explanations "
        f"or commentary. Please translate the following {source_name} text into "
        f"{target_name}:\n\n\n{text.strip()}<end_of_turn>\n<start_of_turn>model\n"
    )


def clean_generation(value: str) -> str:
    value = value.strip()
    changed = True
    while changed:
        changed = False
        for marker in TRAILING_MARKERS:
            if value.endswith(marker):
                value = value[: -len(marker)].rstrip()
                changed = True
    return value


def pipeline_parts(case: dict[str, Any]) -> list[str]:
    parts = case.get("translation_pipeline", {}).get("parts")
    return [str(part) for part in parts] if parts else [str(case["source_text"])]


def request_json(
    url: str,
    payload: dict[str, Any],
    timeout: float,
) -> tuple[dict[str, Any], int, int]:
    request_body = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=request_body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response_body = response.read()
            return (
                json.loads(response_body.decode("utf-8")),
                len(request_body),
                len(response_body),
            )
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {error.code}: {body}") from error


def translate(
    base_url: str,
    text: str,
    source_code: str,
    target_code: str,
    timeout: float,
    max_tokens: int,
) -> tuple[str, float, dict[str, Any]]:
    payload = {
        "prompt": build_prompt(text, source_code, target_code),
        "n_predict": max_tokens,
        "temperature": 0.0,
        "top_k": 1,
        "top_p": 1.0,
        "seed": 42,
        "repeat_penalty": 1.0,
        "stop": ["<end_of_turn>", "<eos>"],
        "stream": False,
        "cache_prompt": False,
    }
    started = time.perf_counter_ns()
    result, request_bytes, response_bytes = request_json(
        f"{base_url.rstrip('/')}/completion",
        payload,
        timeout,
    )
    latency_ms = (time.perf_counter_ns() - started) / 1_000_000
    details = {
        key: result.get(key)
        for key in (
            "tokens_cached",
            "tokens_evaluated",
            "tokens_predicted",
            "truncated",
            "stopped_eos",
            "stopped_limit",
            "stopped_word",
            "stopping_word",
            "timings",
        )
    }
    details["network_body_bytes"] = {
        "request": request_bytes,
        "response": response_bytes,
        "scope": "HTTP bodies only; headers, TLS, and transport overhead excluded",
    }
    return clean_generation(str(result["content"])), latency_ms, details


def median(values: list[float]) -> float:
    return float(statistics.median(values))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline_json", type=Path)
    parser.add_argument("--server-url", default="http://127.0.0.1:18088")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--expected-model-sha256", default="")
    parser.add_argument("--model-repo", default="google/translategemma-4b-it")
    parser.add_argument("--model-revision", required=True)
    parser.add_argument("--quantization", default="Q4_K_M")
    parser.add_argument("--llama-tag", default=DEFAULT_LLAMA_TAG)
    parser.add_argument("--llama-commit", default=DEFAULT_LLAMA_COMMIT)
    parser.add_argument(
        "--runtime-scope",
        default=(
            "Windows x86_64 GPU cloud prescreen through an external "
            "llama.cpp server; Android latency is outside this run."
        ),
    )
    parser.add_argument("--source-language", choices=("en", "ja"), required=True)
    parser.add_argument("--target-language", choices=("zh", "zh-Hans"), default="zh")
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--max-tokens", type=int, default=256)
    parser.add_argument("--request-timeout", type=float, default=300.0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.repetitions < 1:
        parser.error("--repetitions must be at least 1")
    if not args.model.is_file():
        parser.error(f"missing model: {args.model}")
    model_sha256 = sha256_file(args.model)
    if (
        args.expected_model_sha256
        and model_sha256.lower() != args.expected_model_sha256.lower()
    ):
        parser.error(
            f"model SHA-256 is {model_sha256}, "
            f"expected {args.expected_model_sha256}"
        )

    baseline = json.loads(args.baseline_json.read_text(encoding="utf-8"))
    health_url = f"{args.server_url.rstrip('/')}/health"
    with urllib.request.urlopen(health_url, timeout=5) as response:
        health = json.loads(response.read().decode("utf-8"))
    if health.get("status") != "ok":
        raise RuntimeError(f"server unhealthy: {health}")

    warmup_text = (
        "Warm-up sentence."
        if args.source_language == "en"
        else "これはウォームアップ用の文です。"
    )
    _, warmup_ms, warmup_details = translate(
        args.server_url,
        warmup_text,
        args.source_language,
        args.target_language,
        args.request_timeout,
        args.max_tokens,
    )

    output_cases: list[dict[str, Any]] = []
    for index, baseline_case in enumerate(baseline["cases"], start=1):
        raw_outputs: list[str] = []
        raw_latencies: list[float] = []
        raw_details: list[dict[str, Any]] = []
        for _ in range(args.repetitions):
            output, latency, details = translate(
                args.server_url,
                str(baseline_case["source_text"]),
                args.source_language,
                args.target_language,
                args.request_timeout,
                args.max_tokens,
            )
            raw_outputs.append(output)
            raw_latencies.append(latency)
            raw_details.append(details)

        parts = pipeline_parts(baseline_case)
        pipeline_runs: list[list[str]] = []
        pipeline_latencies: list[float] = []
        pipeline_details: list[list[dict[str, Any]]] = []
        for _ in range(args.repetitions):
            part_outputs: list[str] = []
            part_details: list[dict[str, Any]] = []
            total_latency = 0.0
            for part in parts:
                output, latency, details = translate(
                    args.server_url,
                    part,
                    args.source_language,
                    args.target_language,
                    args.request_timeout,
                    args.max_tokens,
                )
                part_outputs.append(output)
                part_details.append(details)
                total_latency += latency
            pipeline_runs.append(part_outputs)
            pipeline_latencies.append(total_latency)
            pipeline_details.append(part_details)

        raw_selected = Counter(raw_outputs).most_common(1)[0][0]
        pipeline_tuples = [tuple(run) for run in pipeline_runs]
        pipeline_selected = list(Counter(pipeline_tuples).most_common(1)[0][0])
        output_case = {
            key: copy.deepcopy(value)
            for key, value in baseline_case.items()
            if key not in {"translation_raw", "translation_pipeline", "end_to_end"}
        }
        output_case["translation_raw"] = {
            "output_text": raw_selected,
            "latencies_ms": raw_latencies,
            "median_latency_ms": median(raw_latencies),
            "repetition_outputs": raw_outputs,
            "outputs_consistent_across_repetitions": len(set(raw_outputs)) == 1,
            "request_details": raw_details,
        }
        output_case["translation_pipeline"] = {
            "parts": parts,
            "part_outputs": pipeline_selected,
            "output_text": " ".join(pipeline_selected),
            "latencies_ms": pipeline_latencies,
            "median_latency_ms": median(pipeline_latencies),
            "repetition_outputs": pipeline_runs,
            "outputs_consistent_across_repetitions": (
                len(set(pipeline_tuples)) == 1
            ),
            "request_details": pipeline_details,
        }
        output_cases.append(output_case)
        print(
            f"[{index}/{len(baseline['cases'])}] "
            f"{baseline_case['id']}: {raw_selected}",
            flush=True,
        )

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
            "ocr": baseline["engines"]["ocr"],
            "translation": (
                f"{args.model_repo} {args.quantization} / "
                f"llama.cpp {args.llama_tag} raw completion"
            ),
            "source_language": args.source_language,
            "target_language": args.target_language,
        },
        "method": {
            "fixture_suite": baseline.get("method", {}).get("fixture_suite"),
            "model_repo": args.model_repo,
            "model_revision": args.model_revision,
            "model_file": {
                "path": str(args.model.resolve()),
                "bytes": args.model.stat().st_size,
                "sha256": model_sha256,
                "quantization": args.quantization,
            },
            "llama_cpp": {
                "tag": args.llama_tag,
                "commit": args.llama_commit,
            },
            "official_prompt_template": build_prompt(
                "{source_text}",
                args.source_language,
                args.target_language,
            ),
            "decoding": {
                "temperature": 0.0,
                "top_k": 1,
                "top_p": 1.0,
                "repeat_penalty": 1.0,
                "seed": 42,
                "max_tokens": args.max_tokens,
            },
            "repetitions": args.repetitions,
            "warmup_ms": warmup_ms,
            "warmup_details": warmup_details,
            "server_url": args.server_url,
            "network_measurement": (
                "Per-request UTF-8 HTTP body bytes; headers, TLS, and "
                "transport overhead are excluded."
            ),
            "runtime_scope": args.runtime_scope,
        },
        "cases": output_cases,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {args.output.resolve()}")


if __name__ == "__main__":
    main()
