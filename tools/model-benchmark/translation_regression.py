#!/usr/bin/env python3
"""Versioned, key-free translation quality regression and blind-review tooling."""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
import math
import random
import re
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FIXTURES = (
    ROOT / "app" / "src" / "benchmark" / "assets" / "translation-fixtures.json"
)
DEFAULT_PIN = DEFAULT_FIXTURES.with_suffix(".sha256")
DEFAULT_FAILURES = Path(__file__).with_name("fixtures") / "online-failure-contract.json"
DEFAULT_THRESHOLDS = (
    Path(__file__).with_name("fixtures") / "translation-regression-thresholds.json"
)
DEFAULT_RUBRIC = Path(__file__).with_name("fixtures") / "human-rating-rubric.json"
PAIR_BY_SUITE = {
    "en-zh-diverse-v2": "en-zh",
    "ja-zh-diverse-v1": "ja-zh",
}
REQUIRED_DOMAINS = {"protected_span", "long_form", "ui", "subtitle", "commerce"}
REQUIRED_REVIEW_DOMAINS = {"protected_span", "long", "ui", "subtitle", "commerce"}
REQUIRED_FAILURE_CLASSES = {
    "credentials",
    "rate_limit",
    "timeout",
    "temporary_service",
    "server",
    "protocol",
    "empty_output",
}
FIXTURE_METHOD_KEYS = (
    "fixture_schema_version",
    "fixture_suite",
    "fixture_corpus_release",
    "fixture_sha256",
)


def _load_score_module() -> Any:
    module_path = Path(__file__).with_name("score.py")
    spec = importlib.util.spec_from_file_location("translation_regression_score", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load scorer: {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


score = _load_score_module()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return value


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_sha256(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def read_pinned_hash(path: Path) -> tuple[str, str]:
    fields = path.read_text(encoding="utf-8").strip().split()
    if len(fields) != 2 or not re.fullmatch(r"[0-9a-f]{64}", fields[0]):
        raise ValueError(f"Invalid SHA-256 pin: {path}")
    return fields[0], fields[1].lstrip("*")


def suite_map(document: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {PAIR_BY_SUITE[suite["id"]]: suite for suite in document["suites"]}


def selected_reference(case: dict[str, Any]) -> str:
    """Pick a project reference that satisfies the greatest number of semantic checks."""
    checks = case.get("critical_checks", [])
    references = list(case["reference_translations"])
    return max(
        references,
        key=lambda text: sum(score.evaluate_check(text, check)["passed"] for check in checks),
    )


def validate_failure_contract(document: dict[str, Any]) -> dict[str, Any]:
    if document.get("schema_version") != 1:
        raise ValueError("Unsupported failure-contract schema")
    if document.get("license_spdx") != "Apache-2.0":
        raise ValueError("Failure fixtures must declare Apache-2.0")
    cases = document.get("cases", [])
    if not cases:
        raise ValueError("Failure contract is empty")
    identifiers: set[str] = set()
    classifications: set[str] = set()
    for case in cases:
        identifier = str(case.get("id", ""))
        if not identifier or identifier in identifiers:
            raise ValueError(f"Duplicate or empty failure case id: {identifier!r}")
        identifiers.add(identifier)
        expected = case.get("expected", {})
        classification = str(expected.get("classification", ""))
        classifications.add(classification)
        attempts = expected.get("maximum_attempts")
        if not isinstance(attempts, int) or not 1 <= attempts <= 2:
            raise ValueError(f"Invalid attempt count for {identifier}")
        if expected.get("retry") is False and attempts != 1:
            raise ValueError(f"Non-retryable case has multiple attempts: {identifier}")
        if expected.get("retry") is True and attempts < 2:
            raise ValueError(f"Retryable case has fewer than two attempts: {identifier}")
        if expected.get("preserve_previous_translation") is not True:
            raise ValueError(f"Failure must preserve prior overlay text: {identifier}")
    missing = REQUIRED_FAILURE_CLASSES - classifications
    if missing:
        raise ValueError(f"Failure contract misses classifications: {sorted(missing)}")
    serialized = json.dumps(document, ensure_ascii=False).casefold()
    if re.search(r"\bsk-[a-z0-9]{12,}\b", serialized):
        raise ValueError("Failure fixtures contain an API-key-shaped value")
    return {
        "case_count": len(cases),
        "classifications": sorted(classifications),
    }


def validate_thresholds(document: dict[str, Any], corpus_release: str) -> None:
    if document.get("schema_version") != 1:
        raise ValueError("Unsupported regression-threshold schema")
    if document.get("corpus_release") != corpus_release:
        raise ValueError("Thresholds are not pinned to the fixture corpus release")
    if document.get("scored_layer") not in {"translation_raw", "translation_pipeline"}:
        raise ValueError("Unsupported scored layer")
    if set(document.get("editions", {})) != {"lite", "full", "online"}:
        raise ValueError("Thresholds must define Lite, Full, and Online")
    for edition, edition_config in document["editions"].items():
        for pair in ("en-zh", "ja-zh"):
            pair_config = edition_config.get(pair)
            if not isinstance(pair_config, dict):
                raise ValueError(f"{edition} misses {pair} thresholds")
            for name in (
                "minimum_bleu",
                "minimum_chrf_pp",
                "minimum_critical_check_rate",
                "minimum_mean_adequacy",
                "minimum_mean_fluency",
            ):
                value = pair_config.get(name)
                if not isinstance(value, (int, float)) or not math.isfinite(float(value)):
                    raise ValueError(f"Invalid {edition}/{pair}/{name}")


def validate_fixtures(
    fixtures_path: Path = DEFAULT_FIXTURES,
    pin_path: Path = DEFAULT_PIN,
    failures_path: Path = DEFAULT_FAILURES,
    thresholds_path: Path = DEFAULT_THRESHOLDS,
) -> dict[str, Any]:
    document = load_json(fixtures_path)
    if document.get("schema_version") != 2:
        raise ValueError("Unsupported translation fixture schema")
    corpus_release = str(document.get("corpus_release", ""))
    if not re.fullmatch(r"\d{4}\.\d{2}-[a-z0-9-]+", corpus_release):
        raise ValueError("Missing or malformed corpus_release")
    license_info = document.get("license", {})
    if license_info.get("default_spdx") != "Apache-2.0":
        raise ValueError("Project-authored fixture license must be Apache-2.0")
    notice = fixtures_path.with_name(str(license_info.get("notice_file", "")))
    if not notice.is_file():
        raise ValueError(f"Missing fixture rights notice: {notice}")

    pinned_hash, pinned_name = read_pinned_hash(pin_path)
    actual_hash = sha256_file(fixtures_path)
    if pinned_name != fixtures_path.name or pinned_hash != actual_hash:
        raise ValueError(
            f"Fixture SHA-256 pin mismatch: expected {pinned_hash}, got {actual_hash}"
        )

    registry = document.get("provenance_registry", [])
    provenance = {entry["id"]: entry for entry in registry}
    if len(provenance) != len(registry):
        raise ValueError("Duplicate provenance registry id")
    if "project-authored-apache-2.0" not in provenance:
        raise ValueError("Missing project-authored provenance entry")
    for entry in registry:
        if not entry.get("source_uri") or not entry.get("license_spdx"):
            raise ValueError(f"Incomplete provenance registry entry: {entry.get('id')}")

    suites = suite_map(document)
    if set(suites) != {"en-zh", "ja-zh"}:
        raise ValueError("Fixture corpus must contain exactly en-zh and ja-zh suites")
    all_ids: set[str] = set()
    suite_stats: dict[str, Any] = {}
    for pair, suite in suites.items():
        if suite.get("corpus_release") != corpus_release:
            raise ValueError(f"Suite {suite.get('id')} has a stale corpus release")
        source_language, target_language = pair.split("-", 1)
        if suite.get("source_language") != source_language:
            raise ValueError(f"Suite {suite.get('id')} source language is inconsistent")
        if suite.get("target_language") != target_language:
            raise ValueError(f"Suite {suite.get('id')} target language is inconsistent")
        required_review_domains = set(suite.get("required_review_domains", []))
        if required_review_domains != REQUIRED_REVIEW_DOMAINS | {"failure_contract"}:
            raise ValueError(f"Suite {suite.get('id')} review-domain contract is incomplete")
        cases = suite.get("cases", [])
        if len(cases) < 48:
            raise ValueError(f"Suite {suite.get('id')} is smaller than the public v1 floor")
        categories = {str(case.get("category", "")) for case in cases}
        missing_domains = REQUIRED_DOMAINS - categories
        if missing_domains:
            raise ValueError(f"{pair} misses domains: {sorted(missing_domains)}")
        check_count = 0
        public_domain_count = 0
        for case in cases:
            identifier = str(case.get("id", ""))
            if not identifier or identifier in all_ids:
                raise ValueError(f"Duplicate or empty fixture id: {identifier!r}")
            all_ids.add(identifier)
            provenance_id = case.get("provenance_id")
            if provenance_id not in provenance:
                raise ValueError(f"Unknown provenance for {identifier}: {provenance_id}")
            provenance_kind = provenance[provenance_id]["kind"]
            if case.get("provenance") != provenance_kind:
                raise ValueError(f"Provenance kind mismatch for {identifier}")
            if provenance_kind == "public_domain":
                public_domain_count += 1
            if case.get("reference_license_spdx") != "Apache-2.0":
                raise ValueError(f"Reference license missing for {identifier}")
            if not str(case.get("source_text", "")).strip():
                raise ValueError(f"Empty source text: {identifier}")
            source_text = str(case["source_text"])
            if "\ufffd" in source_text or "??" in source_text:
                raise ValueError(f"Source text appears encoding-corrupted: {identifier}")
            if pair == "ja-zh" and re.search(r"[\u3040-\u30ff\u3400-\u9fff]", source_text) is None:
                raise ValueError(f"Japanese fixture has no Japanese script: {identifier}")
            references = case.get("reference_translations", [])
            if not references or any(not str(reference).strip() for reference in references):
                raise ValueError(f"Missing reference translation: {identifier}")
            if any(
                "\ufffd" in str(reference)
                or "??" in str(reference)
                or re.search(r"[\u3400-\u9fff]", str(reference)) is None
                for reference in references
            ):
                raise ValueError(f"Reference appears encoding-corrupted: {identifier}")
            checks = case.get("critical_checks", [])
            check_count += len(checks)
            for check in checks:
                if not check.get("name"):
                    raise ValueError(f"Unnamed critical check: {identifier}")
                for key in ("all_regex", "any_regex", "forbid_regex"):
                    for pattern in check.get(key, []):
                        re.compile(pattern)
                if not any(score.evaluate_check(reference, check)["passed"] for reference in references):
                    raise ValueError(
                        f"No reference satisfies critical check {check.get('name')!r} "
                        f"for {identifier}"
                    )
        if check_count < 60:
            raise ValueError(f"{pair} has too few semantic checks: {check_count}")
        suite_stats[pair] = {
            "suite_id": suite["id"],
            "case_count": len(cases),
            "category_count": len(categories),
            "categories": sorted(categories),
            "critical_check_count": check_count,
            "public_domain_source_cases": public_domain_count,
        }

    failure_stats = validate_failure_contract(load_json(failures_path))
    validate_thresholds(load_json(thresholds_path), corpus_release)
    return {
        "schema_version": 1,
        "corpus_release": corpus_release,
        "fixture_sha256": actual_hash,
        "fixture_license": license_info["default_spdx"],
        "suite_stats": suite_stats,
        "failure_contract": failure_stats,
        "threshold_editions": ["lite", "full", "online"],
    }


def make_reference_replay(
    pair: str,
    engine_id: str,
    fixtures_path: Path = DEFAULT_FIXTURES,
) -> dict[str, Any]:
    document = load_json(fixtures_path)
    suite = suite_map(document)[pair]
    cases: list[dict[str, Any]] = []
    for case_index, fixture in enumerate(suite["cases"], start=1):
        output = selected_reference(fixture)
        case = copy.deepcopy(fixture)
        case["reference_translation"] = fixture["reference_translations"][0]
        case["ocr"] = {
            "output_text": fixture["source_text"],
            "blocks": [fixture["source_text"]],
            "latencies_ms": [0.0],
            "median_latency_ms": 0.0,
        }
        deterministic_latency = round(1.0 + case_index / 100.0, 3)
        case["translation_raw"] = {
            "output_text": output,
            "latencies_ms": [deterministic_latency],
            "median_latency_ms": deterministic_latency,
        }
        case["translation_pipeline"] = copy.deepcopy(case["translation_raw"])
        cases.append(case)
    source, target = pair.split("-", 1)
    return {
        "schema_version": 1,
        "result_kind": "reference_replay_not_model_evidence",
        "device": {"kind": "deterministic_fixture_replay"},
        "engines": {
            "ocr": "gold source pass-through",
            "translation": f"reference-replay/{engine_id}",
            "engine_id": engine_id,
            "source_language": source,
            "target_language": target,
        },
        "method": {
            "translation_only": True,
            "fixture_schema_version": document["schema_version"],
            "fixture_suite": suite["id"],
            "fixture_corpus_release": document["corpus_release"],
            "fixture_sha256": sha256_file(fixtures_path),
            "replay_strategy": "highest-critical-check project-authored reference",
        },
        "cases": cases,
    }


def make_failure_replay(failures_path: Path = DEFAULT_FAILURES) -> dict[str, Any]:
    contract = load_json(failures_path)
    return {
        "schema_version": 1,
        "result_kind": "offline_failure_contract_replay",
        "contract_sha256": sha256_file(failures_path),
        "cases": [
            {"id": case["id"], "actual": copy.deepcopy(case["expected"])}
            for case in contract["cases"]
        ],
    }


def verify_failure_replay(
    replay: dict[str, Any],
    contract: dict[str, Any],
    failures_path: Path,
) -> dict[str, Any]:
    if replay.get("contract_sha256") != sha256_file(failures_path):
        raise ValueError("Failure replay targets a different contract hash")
    expected = {case["id"]: case["expected"] for case in contract["cases"]}
    actual = {case["id"]: case["actual"] for case in replay.get("cases", [])}
    if actual != expected:
        missing = sorted(set(expected) - set(actual))
        extra = sorted(set(actual) - set(expected))
        mismatched = sorted(
            identifier
            for identifier in set(expected) & set(actual)
            if expected[identifier] != actual[identifier]
        )
        raise ValueError(
            f"Failure replay mismatch; missing={missing}, extra={extra}, "
            f"mismatched={mismatched}"
        )
    return {"passed": True, "case_count": len(expected)}


def validate_result_against_suite(
    result: dict[str, Any],
    pair: str,
    suite: dict[str, Any],
    corpus_release: str,
    fixture_hash: str,
    layer: str,
) -> None:
    method = result.get("method", {})
    expected_method = {
        "fixture_schema_version": 2,
        "fixture_suite": suite["id"],
        "fixture_corpus_release": corpus_release,
        "fixture_sha256": fixture_hash,
    }
    for key, expected in expected_method.items():
        if method.get(key) != expected:
            raise ValueError(f"Result {pair} has {key}={method.get(key)!r}, expected {expected!r}")
    expected_ids = [case["id"] for case in suite["cases"]]
    actual_ids = [case.get("id") for case in result.get("cases", [])]
    if actual_ids != expected_ids:
        raise ValueError(f"Result {pair} case order/content does not match the pinned suite")
    if not all(layer in case for case in result["cases"]):
        raise ValueError(f"Result {pair} does not contain {layer} for every case")
    source, target = pair.split("-", 1)
    engines = result.get("engines", {})
    if engines.get("source_language") != source:
        raise ValueError(f"Result source language mismatch for {pair}")
    if str(engines.get("target_language", "")).split("-", 1)[0] != target:
        raise ValueError(f"Result target language mismatch for {pair}")


def automatic_metrics(result: dict[str, Any], layer: str) -> dict[str, Any]:
    summary = score.score_translation_layer(result["cases"], layer)
    total = summary["critical_checks_total"]
    critical_rate = summary["critical_checks_passed"] / total if total else 1.0
    protected = [case for case in summary["cases"] if case["category"] == "protected_span"]
    protected_passed = all(
        check["passed"] for case in protected for check in case["critical_checks"]
    )
    return {
        "bleu": summary["corpus_bleu"],
        "chrf_pp": summary["corpus_chrf_pp"],
        "critical_checks_passed": summary["critical_checks_passed"],
        "critical_checks_total": total,
        "critical_check_rate": round(critical_rate, 6),
        "protected_span_checks_passed": protected_passed,
        "case_count": len(summary["cases"]),
    }


def _check(name: str, actual: float | bool, relation: str, expected: float | bool) -> dict[str, Any]:
    if relation == ">=":
        passed = float(actual) >= float(expected)
    elif relation == "<=":
        passed = float(actual) <= float(expected)
    elif relation == "==":
        passed = actual == expected
    else:
        raise ValueError(f"Unknown relation: {relation}")
    return {
        "name": name,
        "actual": actual,
        "relation": relation,
        "expected": expected,
        "passed": passed,
    }


def evaluate_pair_gate(
    candidate: dict[str, Any],
    baseline: dict[str, Any],
    pair_thresholds: dict[str, Any],
    comparison: dict[str, Any],
    layer: str,
) -> dict[str, Any]:
    candidate_metrics = automatic_metrics(candidate, layer)
    baseline_metrics = automatic_metrics(baseline, layer)
    checks = [
        _check("minimum BLEU", candidate_metrics["bleu"], ">=", pair_thresholds["minimum_bleu"]),
        _check(
            "minimum chrF++",
            candidate_metrics["chrf_pp"],
            ">=",
            pair_thresholds["minimum_chrf_pp"],
        ),
        _check(
            "minimum critical-check rate",
            candidate_metrics["critical_check_rate"],
            ">=",
            pair_thresholds["minimum_critical_check_rate"],
        ),
        _check(
            "all protected-span checks",
            candidate_metrics["protected_span_checks_passed"],
            "==",
            True,
        ),
        _check(
            "BLEU regression versus incumbent",
            baseline_metrics["bleu"] - candidate_metrics["bleu"],
            "<=",
            comparison["maximum_bleu_drop"],
        ),
        _check(
            "chrF++ regression versus incumbent",
            baseline_metrics["chrf_pp"] - candidate_metrics["chrf_pp"],
            "<=",
            comparison["maximum_chrf_pp_drop"],
        ),
        _check(
            "critical-check regression versus incumbent",
            baseline_metrics["critical_check_rate"]
            - candidate_metrics["critical_check_rate"],
            "<=",
            comparison["maximum_critical_check_rate_drop"],
        ),
    ]
    return {
        "passed": all(check["passed"] for check in checks),
        "candidate": candidate_metrics,
        "baseline": baseline_metrics,
        "checks": checks,
    }


def parse_human_summary(
    summary: dict[str, Any],
    system_id: str,
    pair: str,
) -> dict[str, Any]:
    try:
        return summary["systems"][system_id][pair]
    except KeyError as error:
        raise ValueError(f"Human summary misses {system_id}/{pair}") from error


def run_gate(
    edition: str,
    candidates: dict[str, dict[str, Any]],
    baselines: dict[str, dict[str, Any]],
    *,
    fixtures_path: Path = DEFAULT_FIXTURES,
    thresholds_path: Path = DEFAULT_THRESHOLDS,
    failures_path: Path = DEFAULT_FAILURES,
    failure_replay: dict[str, Any] | None = None,
    human_summary: dict[str, Any] | None = None,
    candidate_system: str | None = None,
    automated_only_smoke: bool = False,
) -> dict[str, Any]:
    fixtures = load_json(fixtures_path)
    thresholds = load_json(thresholds_path)
    validate_thresholds(thresholds, fixtures["corpus_release"])
    if edition not in thresholds["editions"]:
        raise ValueError(f"Unknown edition: {edition}")
    if set(candidates) != {"en-zh", "ja-zh"} or set(baselines) != {
        "en-zh",
        "ja-zh",
    }:
        raise ValueError("Gate requires en-zh and ja-zh candidate and baseline results")
    suites = suite_map(fixtures)
    fixture_hash = sha256_file(fixtures_path)
    layer = thresholds["scored_layer"]
    pair_reports: dict[str, Any] = {}
    for pair in ("en-zh", "ja-zh"):
        validate_result_against_suite(
            candidates[pair],
            pair,
            suites[pair],
            fixtures["corpus_release"],
            fixture_hash,
            layer,
        )
        validate_result_against_suite(
            baselines[pair],
            pair,
            suites[pair],
            fixtures["corpus_release"],
            fixture_hash,
            layer,
        )
        pair_reports[pair] = evaluate_pair_gate(
            candidates[pair],
            baselines[pair],
            thresholds["editions"][edition][pair],
            thresholds["comparison"],
            layer,
        )

    failure_report: dict[str, Any] = {"required": edition == "online"}
    if edition == "online":
        if failure_replay is None:
            raise ValueError("Online gate requires an offline failure replay result")
        failure_report.update(
            verify_failure_replay(failure_replay, load_json(failures_path), failures_path)
        )
    else:
        failure_report["passed"] = True

    human_report: dict[str, Any]
    if automated_only_smoke:
        human_report = {
            "passed": False,
            "status": "not_run_in_harness_smoke",
            "release_gate_satisfied": False,
        }
    else:
        if human_summary is None or not candidate_system:
            raise ValueError("Release gate requires blind human scores and candidate system id")
        if human_summary.get("corpus_release") != fixtures["corpus_release"]:
            raise ValueError("Human summary targets a different corpus release")
        if human_summary.get("fixture_sha256") != fixture_hash:
            raise ValueError("Human summary targets a different fixture hash")
        human_checks = []
        human_config = thresholds["human_review"]
        for pair in ("en-zh", "ja-zh"):
            metrics = parse_human_summary(human_summary, candidate_system, pair)
            pair_config = thresholds["editions"][edition][pair]
            human_checks.extend(
                [
                    _check(
                        f"{pair} minimum adequacy",
                        metrics["mean_adequacy"],
                        ">=",
                        pair_config["minimum_mean_adequacy"],
                    ),
                    _check(
                        f"{pair} minimum fluency",
                        metrics["mean_fluency"],
                        ">=",
                        pair_config["minimum_mean_fluency"],
                    ),
                    _check(
                        f"{pair} critical human error rate",
                        metrics["critical_error_rate"],
                        "<=",
                        human_config["maximum_critical_error_rate"],
                    ),
                    _check(
                        f"{pair} raters per output",
                        metrics["minimum_ratings_per_output"],
                        ">=",
                        human_config["minimum_raters_per_output"],
                    ),
                    _check(
                        f"{pair} case coverage",
                        metrics["case_coverage"],
                        ">=",
                        human_config["minimum_case_coverage"],
                    ),
                ]
            )
        human_report = {
            "passed": all(check["passed"] for check in human_checks),
            "status": "evaluated",
            "checks": human_checks,
        }

    automated_passed = all(report["passed"] for report in pair_reports.values()) and bool(
        failure_report["passed"]
    )
    release_ready = automated_passed and bool(human_report["passed"])
    return {
        "schema_version": 1,
        "report_kind": (
            "harness_smoke_not_release_evidence"
            if automated_only_smoke
            else "translation_quality_release_gate"
        ),
        "edition": edition,
        "corpus_release": fixtures["corpus_release"],
        "fixture_sha256": fixture_hash,
        "automated_passed": automated_passed,
        "release_ready": release_ready,
        "pairs": pair_reports,
        "failure_contract": failure_report,
        "human_review": human_report,
    }


def parse_system_specifications(specifications: Iterable[str]) -> dict[str, dict[str, Path]]:
    systems: dict[str, dict[str, Path]] = defaultdict(dict)
    for specification in specifications:
        left, separator, path_text = specification.partition("=")
        if not separator or ":" not in left:
            raise ValueError("System must use SYSTEM:PAIR=RESULT.json")
        system_id, pair = left.split(":", 1)
        if not re.fullmatch(r"[a-zA-Z0-9_.-]+", system_id):
            raise ValueError(f"Invalid system id: {system_id}")
        if pair not in {"en-zh", "ja-zh"}:
            raise ValueError(f"Invalid pair: {pair}")
        if pair in systems[system_id]:
            raise ValueError(f"Duplicate system pair: {system_id}/{pair}")
        systems[system_id][pair] = Path(path_text)
    if len(systems) < 2:
        raise ValueError("Blind comparison requires at least two systems")
    if any(set(pairs) != {"en-zh", "ja-zh"} for pairs in systems.values()):
        raise ValueError("Every blind system must provide en-zh and ja-zh")
    return dict(systems)


def make_blind_bundle(
    systems: dict[str, dict[str, dict[str, Any]]],
    seed: str,
    fixtures_path: Path = DEFAULT_FIXTURES,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    fixtures = load_json(fixtures_path)
    suites = suite_map(fixtures)
    fixture_hash = sha256_file(fixtures_path)
    randomizer = random.Random(seed)
    sheet_items = []
    key_entries = []
    for pair in ("en-zh", "ja-zh"):
        for system_results in systems.values():
            validate_result_against_suite(
                system_results[pair],
                pair,
                suites[pair],
                fixtures["corpus_release"],
                fixture_hash,
                "translation_raw",
            )
        for index, fixture in enumerate(suites[pair]["cases"]):
            item_id = hashlib.sha256(
                f"{fixtures['corpus_release']}\0{pair}\0{fixture['id']}".encode("utf-8")
            ).hexdigest()[:20]
            outputs = []
            system_ids = list(systems)
            randomizer.shuffle(system_ids)
            for position, system_id in enumerate(system_ids):
                result_case = systems[system_id][pair]["cases"][index]
                output_id = hashlib.sha256(
                    f"{seed}\0{item_id}\0{position}\0{system_id}".encode("utf-8")
                ).hexdigest()[:20]
                outputs.append(
                    {
                        "output_id": output_id,
                        "position": position + 1,
                        "text": result_case["translation_raw"]["output_text"],
                    }
                )
                key_entries.append(
                    {
                        "item_id": item_id,
                        "output_id": output_id,
                        "system_id": system_id,
                        "pair": pair,
                        "case_id": fixture["id"],
                    }
                )
            sheet_items.append(
                {
                    "item_id": item_id,
                    "pair": pair,
                    "source_text": fixture["source_text"],
                    "category": fixture["category"],
                    "risk": fixture.get("risk", "general"),
                    "outputs": outputs,
                }
            )
    sheet = {
        "schema_version": 1,
        "corpus_release": fixtures["corpus_release"],
        "fixture_sha256": fixture_hash,
        "blinding": "engine identities are stored only in the separate key",
        "items": sheet_items,
    }
    key = {
        "schema_version": 1,
        "sheet_sha256": canonical_json_sha256(sheet),
        "seed_sha256": hashlib.sha256(seed.encode("utf-8")).hexdigest(),
        "systems": sorted(systems),
        "entries": key_entries,
    }
    rating_template = {
        "schema_version": 1,
        "rater_id": "REPLACE_WITH_PSEUDONYMOUS_RATER_ID",
        "ratings": [
            {
                "item_id": entry["item_id"],
                "output_id": entry["output_id"],
                "adequacy": None,
                "fluency": None,
                "critical_error": "none",
                "notes": "",
            }
            for entry in key_entries
        ],
    }
    return sheet, key, rating_template


def score_human_ratings(
    sheet: dict[str, Any],
    key: dict[str, Any],
    rating_documents: list[dict[str, Any]],
    rubric: dict[str, Any],
) -> dict[str, Any]:
    if key.get("sheet_sha256") != canonical_json_sha256(sheet):
        raise ValueError("Blind key does not match the supplied sheet")
    if rubric.get("schema_version") != 1:
        raise ValueError("Unsupported human-rating rubric schema")
    if not rating_documents:
        raise ValueError("At least one completed rating document is required")
    expected = {(entry["item_id"], entry["output_id"]): entry for entry in key["entries"]}
    valid_errors = set(rubric["critical_error_values"])
    rater_ids: set[str] = set()
    grouped: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    counts_by_output: dict[tuple[str, str], int] = defaultdict(int)
    for document in rating_documents:
        rater_id = str(document.get("rater_id", "")).strip()
        if not rater_id or rater_id == "REPLACE_WITH_PSEUDONYMOUS_RATER_ID":
            raise ValueError("Each rating file needs a pseudonymous rater_id")
        if rater_id in rater_ids:
            raise ValueError(f"Duplicate rater id: {rater_id}")
        rater_ids.add(rater_id)
        ratings = document.get("ratings", [])
        actual_keys = {(rating.get("item_id"), rating.get("output_id")) for rating in ratings}
        if actual_keys != set(expected) or len(ratings) != len(expected):
            raise ValueError(f"Rater {rater_id} did not score every blind output exactly once")
        for rating in ratings:
            rating_key = (rating["item_id"], rating["output_id"])
            adequacy = rating.get("adequacy")
            fluency = rating.get("fluency")
            critical_error = rating.get("critical_error")
            if adequacy not in {1, 2, 3, 4, 5} or fluency not in {1, 2, 3, 4, 5}:
                raise ValueError(f"Rating outside 1..5: {rating_key}")
            if critical_error not in valid_errors:
                raise ValueError(f"Invalid critical error value: {critical_error}")
            entry = expected[rating_key]
            system_pair = (entry["system_id"], entry["pair"])
            grouped[system_pair].append(
                {
                    "adequacy": adequacy,
                    "fluency": fluency,
                    "critical": critical_error != "none",
                    "case_id": entry["case_id"],
                }
            )
            counts_by_output[rating_key] += 1

    systems: dict[str, dict[str, Any]] = defaultdict(dict)
    expected_case_counts = {
        pair: sum(1 for item in sheet["items"] if item["pair"] == pair)
        for pair in ("en-zh", "ja-zh")
    }
    for (system_id, pair), ratings in grouped.items():
        covered_cases = {rating["case_id"] for rating in ratings}
        relevant_output_counts = [
            count
            for output_key, count in counts_by_output.items()
            if expected[output_key]["system_id"] == system_id
            and expected[output_key]["pair"] == pair
        ]
        systems[system_id][pair] = {
            "mean_adequacy": round(
                statistics.fmean(rating["adequacy"] for rating in ratings), 4
            ),
            "mean_fluency": round(
                statistics.fmean(rating["fluency"] for rating in ratings), 4
            ),
            "critical_error_rate": round(
                sum(rating["critical"] for rating in ratings) / len(ratings), 6
            ),
            "case_coverage": round(len(covered_cases) / expected_case_counts[pair], 6),
            "minimum_ratings_per_output": min(relevant_output_counts),
            "rating_count": len(ratings),
        }
    return {
        "schema_version": 1,
        "corpus_release": sheet["corpus_release"],
        "fixture_sha256": sheet["fixture_sha256"],
        "rater_count": len(rater_ids),
        "systems": dict(systems),
    }


def smoke_report(
    fixtures_path: Path = DEFAULT_FIXTURES,
    pin_path: Path = DEFAULT_PIN,
    failures_path: Path = DEFAULT_FAILURES,
    thresholds_path: Path = DEFAULT_THRESHOLDS,
) -> dict[str, Any]:
    validation = validate_fixtures(fixtures_path, pin_path, failures_path, thresholds_path)
    candidates = {
        pair: make_reference_replay(pair, "candidate-smoke", fixtures_path)
        for pair in ("en-zh", "ja-zh")
    }
    baselines = {
        pair: make_reference_replay(pair, "baseline-smoke", fixtures_path)
        for pair in ("en-zh", "ja-zh")
    }
    failure_replay = make_failure_replay(failures_path)
    editions = {
        edition: run_gate(
            edition,
            candidates,
            baselines,
            fixtures_path=fixtures_path,
            thresholds_path=thresholds_path,
            failures_path=failures_path,
            failure_replay=failure_replay if edition == "online" else None,
            automated_only_smoke=True,
        )
        for edition in ("lite", "full", "online")
    }
    return {
        "schema_version": 1,
        "report_kind": "deterministic_harness_smoke_not_model_evidence",
        "validation": validation,
        "editions": editions,
        "passed": all(report["automated_passed"] for report in editions.values()),
        "release_ready": False,
        "release_ready_reason": "Blind ratings and real candidate outputs are deliberately absent from harness smoke.",
    }


def parse_pair_paths(values: Iterable[str]) -> dict[str, Path]:
    parsed: dict[str, Path] = {}
    for value in values:
        pair, separator, path = value.partition("=")
        if not separator or pair not in {"en-zh", "ja-zh"}:
            raise ValueError("Result path must use en-zh=FILE or ja-zh=FILE")
        if pair in parsed:
            raise ValueError(f"Duplicate result path for {pair}")
        parsed[pair] = Path(path)
    if set(parsed) != {"en-zh", "ja-zh"}:
        raise ValueError("Both en-zh and ja-zh result paths are required")
    return parsed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate", help="Validate fixtures and pins")
    validate_parser.add_argument("--output", type=Path)

    replay_parser = subparsers.add_parser("replay", help="Emit deterministic fixture replay")
    replay_parser.add_argument("--pair", choices=("en-zh", "ja-zh"), required=True)
    replay_parser.add_argument("--engine-id", required=True)
    replay_parser.add_argument("--output", type=Path, required=True)

    failure_parser = subparsers.add_parser("replay-failures", help="Emit failure replay")
    failure_parser.add_argument("--output", type=Path, required=True)

    blind_parser = subparsers.add_parser("blind", help="Build a blinded comparison bundle")
    blind_parser.add_argument(
        "--system",
        action="append",
        required=True,
        help="SYSTEM:PAIR=RESULT.json; repeat for both pairs and every system",
    )
    blind_parser.add_argument("--seed", required=True)
    blind_parser.add_argument("--sheet", type=Path, required=True)
    blind_parser.add_argument("--key", type=Path, required=True)
    blind_parser.add_argument("--rating-template", type=Path, required=True)

    human_parser = subparsers.add_parser("score-human", help="Aggregate blind ratings")
    human_parser.add_argument("--sheet", type=Path, required=True)
    human_parser.add_argument("--key", type=Path, required=True)
    human_parser.add_argument("--ratings", type=Path, action="append", required=True)
    human_parser.add_argument("--output", type=Path, required=True)

    gate_parser = subparsers.add_parser("gate", help="Evaluate an edition candidate")
    gate_parser.add_argument("--edition", choices=("lite", "full", "online"), required=True)
    gate_parser.add_argument("--candidate", action="append", required=True)
    gate_parser.add_argument("--baseline", action="append", required=True)
    gate_parser.add_argument("--human-summary", type=Path)
    gate_parser.add_argument("--candidate-system")
    gate_parser.add_argument("--failure-replay", type=Path)
    gate_parser.add_argument("--automated-only-smoke", action="store_true")
    gate_parser.add_argument("--output", type=Path, required=True)

    smoke_parser = subparsers.add_parser("smoke", help="Run deterministic key-free harness smoke")
    smoke_parser.add_argument("--output", type=Path, required=True)

    args = parser.parse_args()
    if args.command == "validate":
        result = validate_fixtures()
        if args.output:
            write_json(args.output, result)
    elif args.command == "replay":
        result = make_reference_replay(args.pair, args.engine_id)
        write_json(args.output, result)
    elif args.command == "replay-failures":
        result = make_failure_replay()
        write_json(args.output, result)
    elif args.command == "blind":
        system_paths = parse_system_specifications(args.system)
        systems = {
            system_id: {pair: load_json(path) for pair, path in pair_paths.items()}
            for system_id, pair_paths in system_paths.items()
        }
        sheet, key, template = make_blind_bundle(systems, args.seed)
        write_json(args.sheet, sheet)
        write_json(args.key, key)
        write_json(args.rating_template, template)
        result = {"sheet": str(args.sheet), "key": str(args.key)}
    elif args.command == "score-human":
        result = score_human_ratings(
            load_json(args.sheet),
            load_json(args.key),
            [load_json(path) for path in args.ratings],
            load_json(DEFAULT_RUBRIC),
        )
        write_json(args.output, result)
    elif args.command == "gate":
        candidate_paths = parse_pair_paths(args.candidate)
        baseline_paths = parse_pair_paths(args.baseline)
        result = run_gate(
            args.edition,
            {pair: load_json(path) for pair, path in candidate_paths.items()},
            {pair: load_json(path) for pair, path in baseline_paths.items()},
            failure_replay=load_json(args.failure_replay) if args.failure_replay else None,
            human_summary=load_json(args.human_summary) if args.human_summary else None,
            candidate_system=args.candidate_system,
            automated_only_smoke=args.automated_only_smoke,
        )
        write_json(args.output, result)
        if not (result["automated_passed"] if args.automated_only_smoke else result["release_ready"]):
            raise SystemExit(1)
    elif args.command == "smoke":
        result = smoke_report()
        write_json(args.output, result)
        if not result["passed"]:
            raise SystemExit(1)
    else:
        raise AssertionError(args.command)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
