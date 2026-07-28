#!/usr/bin/env python3
"""Score ScreenTranslation model-benchmark JSON without mixing pipeline layers."""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import unicodedata
from pathlib import Path
from typing import Any, Iterable

from sacrebleu.metrics import BLEU, CHRF


BLEU_ZH = BLEU(tokenize="zh", effective_order=True)
CHRF_PP = CHRF(word_order=2)


CRITICAL_CHECKS: dict[str, list[dict[str, Any]]] = {
    "issue18_long_compound": [
        {
            "name": "screen text does not leave the device",
            "any_regex": [
                r"(绝不|不会|从不|未曾|永远不(?:会|要)?).{0,8}离开.{0,8}(手机|电话|设备)",
                r"(手机|设备).*之外",
            ],
        },
        {
            "name": "model continues working offline",
            "all_regex": [
                r"(没有|无).*网络",
                r"(继续|仍然|保持).*(工作|运行|有效)",
            ],
            "forbid_regex": [r"(不会|不再|停止).{0,8}(继续|保持).{0,4}工作"],
        },
    ],
    "notification_recovery": [
        {
            "name": "capture service survives",
            "any_regex": [r"(保持|仍然|依然).{0,8}(运行|存活|活着|有效)"],
        },
        {
            "name": "translation resumes",
            "all_regex": [r"恢复", r"(翻译|转换)"],
        },
    ],
    "numbers_and_symbols": [
        {
            "name": "identifiers and quantities preserved",
            "all_literals": ["XT-2048", "2026-07-31", "09:45"],
            "all_compact_literals": ["1,249.50"],
            "any_regex": [r"(£|英镑)"],
        },
    ],
    "offline_status": [
        {
            "name": "offline state and quantities preserved",
            "all_literals": ["1.5"],
            "all_compact_literals": ["10/10"],
            "any_regex": [r"(离线|脱机|OFFLINE)"],
        },
        {
            "name": "worker remains active",
            "any_regex": [r"(仍|继续|保持).{0,8}(运行|工作)"],
        },
    ],
    "version_amount_date": [
        {
            "name": "version, amount, and date preserved",
            "all_literals": ["v0.1.0", "37", "2026-07-31"],
            "all_compact_literals": ["12,345.67"],
            "any_regex": [r"(¥|日元|人民币)"],
        },
    ],
}


def normalize_text(value: str, *, casefold: bool = False) -> str:
    normalized = unicodedata.normalize("NFKC", value)
    normalized = re.sub(r"\s+", " ", normalized).strip()
    return normalized.casefold() if casefold else normalized


def compact_literal_text(value: str) -> str:
    """Ignore presentation-only spaces and comma variants in fixed tokens."""
    return re.sub(r"[\s,，]+", "", normalize_text(value))


def tokenize_words(value: str) -> list[str]:
    return re.findall(r"\w+|[^\w\s]", normalize_text(value, casefold=True))


def edit_distance(left: list[Any] | str, right: list[Any] | str) -> int:
    if len(left) < len(right):
        left, right = right, left
    previous = list(range(len(right) + 1))
    for left_index, left_item in enumerate(left, start=1):
        current = [left_index]
        for right_index, right_item in enumerate(right, start=1):
            current.append(
                min(
                    current[-1] + 1,
                    previous[right_index] + 1,
                    previous[right_index - 1] + (left_item != right_item),
                )
            )
        previous = current
    return previous[-1]


def rate(errors: int, reference_size: int) -> float:
    if reference_size == 0:
        return 0.0 if errors == 0 else 1.0
    return errors / reference_size


def percentile(values: Iterable[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return math.nan
    index = max(0, math.ceil(fraction * len(ordered)) - 1)
    return ordered[index]


def summarize_latency(values: list[float]) -> dict[str, float]:
    return {
        "count": len(values),
        "median_ms": round(statistics.median(values), 3),
        "p95_ms": round(percentile(values, 0.95), 3),
        "min_ms": round(min(values), 3),
        "max_ms": round(max(values), 3),
    }


def score_ocr(cases: list[dict[str, Any]]) -> dict[str, Any]:
    results = []
    char_errors = char_reference = 0
    folded_errors = folded_reference = 0
    word_errors = word_reference = 0
    latencies: list[float] = []

    for case in cases:
        reference = normalize_text(case["source_text"])
        output = normalize_text(case["ocr"]["output_text"])
        reference_folded = normalize_text(reference, casefold=True)
        output_folded = normalize_text(output, casefold=True)
        reference_words = tokenize_words(reference)
        output_words = tokenize_words(output)

        case_char_errors = edit_distance(reference, output)
        case_folded_errors = edit_distance(reference_folded, output_folded)
        case_word_errors = edit_distance(reference_words, output_words)
        char_errors += case_char_errors
        char_reference += len(reference)
        folded_errors += case_folded_errors
        folded_reference += len(reference_folded)
        word_errors += case_word_errors
        word_reference += len(reference_words)
        latencies.extend(float(value) for value in case["ocr"]["latencies_ms"])

        results.append(
            {
                "id": case["id"],
                "cer": round(rate(case_char_errors, len(reference)), 6),
                "casefolded_cer": round(
                    rate(case_folded_errors, len(reference_folded)), 6
                ),
                "wer": round(rate(case_word_errors, len(reference_words)), 6),
                "exact_after_whitespace_normalization": output == reference,
                "output_text": case["ocr"]["output_text"],
            }
        )

    return {
        "corpus_cer": round(rate(char_errors, char_reference), 6),
        "corpus_casefolded_cer": round(
            rate(folded_errors, folded_reference), 6
        ),
        "corpus_wer": round(rate(word_errors, word_reference), 6),
        "exact_cases": sum(
            result["exact_after_whitespace_normalization"] for result in results
        ),
        "total_cases": len(results),
        "latency": summarize_latency(latencies),
        "cases": results,
    }


def evaluate_check(text: str, check: dict[str, Any]) -> dict[str, Any]:
    normalized_text = normalize_text(text)
    compact_text = compact_literal_text(text)
    missing_literals = [
        literal
        for literal in check.get("all_literals", [])
        if normalize_text(literal) not in normalized_text
    ]
    missing_compact_literals = [
        literal
        for literal in check.get("all_compact_literals", [])
        if compact_literal_text(literal) not in compact_text
    ]
    missing_regex = [
        pattern
        for pattern in check.get("all_regex", [])
        if re.search(pattern, normalized_text, flags=re.IGNORECASE) is None
    ]
    any_patterns = check.get("any_regex", [])
    any_match = not any_patterns or any(
        re.search(pattern, normalized_text, flags=re.IGNORECASE) is not None
        for pattern in any_patterns
    )
    forbidden_matches = [
        pattern
        for pattern in check.get("forbid_regex", [])
        if re.search(pattern, normalized_text, flags=re.IGNORECASE) is not None
    ]
    passed = (
        not missing_literals
        and not missing_compact_literals
        and not missing_regex
        and any_match
        and not forbidden_matches
    )
    return {
        "name": check["name"],
        "passed": passed,
        "missing_literals": missing_literals,
        "missing_compact_literals": missing_compact_literals,
        "missing_regex": missing_regex,
        "any_regex_matched": any_match,
        "forbidden_matches": forbidden_matches,
    }


def score_translation_layer(
    cases: list[dict[str, Any]],
    layer: str,
) -> dict[str, Any]:
    scored_cases = [case for case in cases if case.get("translation_scored", True)]
    outputs = [case[layer]["output_text"] for case in scored_cases]
    references = [case["reference_translation"] for case in scored_cases]
    case_results = []
    latency_values: list[float] = []
    critical_passes = critical_total = 0

    for case, output, reference in zip(scored_cases, outputs, references):
        checks = [
            evaluate_check(output, definition)
            for definition in CRITICAL_CHECKS.get(case["id"], [])
        ]
        critical_passes += sum(check["passed"] for check in checks)
        critical_total += len(checks)
        if layer == "end_to_end":
            latency_values.append(float(case[layer]["latency_ms"]))
        else:
            latency_values.extend(float(value) for value in case[layer]["latencies_ms"])

        case_results.append(
            {
                "id": case["id"],
                "bleu": round(BLEU_ZH.sentence_score(output, [reference]).score, 3),
                "chrf_pp": round(CHRF_PP.sentence_score(output, [reference]).score, 3),
                "critical_checks": checks,
                "output_text": output,
                "reference_translation": reference,
            }
        )

    return {
        "corpus_bleu": round(BLEU_ZH.corpus_score(outputs, [references]).score, 3),
        "corpus_chrf_pp": round(
            CHRF_PP.corpus_score(outputs, [references]).score, 3
        ),
        "critical_checks_passed": critical_passes,
        "critical_checks_total": critical_total,
        "latency": summarize_latency(latency_values),
        "cases": case_results,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("result_json", type=Path)
    parser.add_argument(
        "--output",
        type=Path,
        help="Output score JSON; defaults beside the input file.",
    )
    args = parser.parse_args()

    source = json.loads(args.result_json.read_text(encoding="utf-8"))
    cases = source["cases"]
    available_translation_layers = [
        layer
        for layer in ("translation_raw", "translation_pipeline", "end_to_end")
        if all(layer in case for case in cases)
    ]
    report = {
        "schema_version": 1,
        "source_result": str(args.result_json.resolve()),
        "device": source["device"],
        "engines": source["engines"],
        "ocr": score_ocr(cases),
        "translation": {
            layer: score_translation_layer(cases, layer)
            for layer in available_translation_layers
        },
        "interpretation": {
            "ocr": (
                "CER/WER use NFKC plus whitespace normalization. "
                "Case-folded CER is also reported."
            ),
            "translation": (
                "BLEU and chrF++ use one Chinese reference per fixture and are "
                "directional signals; critical semantic checks and human review "
                "remain acceptance gates."
            ),
        },
    }

    output = args.output or args.result_json.with_name(
        f"{args.result_json.stem}.scores.json"
    )
    output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
