#!/usr/bin/env python3
"""Adversarial tests for the public translation regression release gate."""

from __future__ import annotations

import copy
import importlib.util
import inspect
import json
import math
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("translation_regression.py")
SPEC = importlib.util.spec_from_file_location("translation_regression", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
regression = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(regression)


def real_shaped_candidate(pair: str, system_id: str) -> dict:
    fixtures = regression.load_json(regression.DEFAULT_FIXTURES)
    suite = regression.suite_map(fixtures)[pair]
    source, target = pair.split("-", 1)
    cases = []
    for index, fixture in enumerate(suite["cases"], start=1):
        latency = float(10 + index)
        cases.append(
            {
                "case_id": fixture["id"],
                "source_sha256": regression.sha256_text(fixture["source_text"]),
                "candidate": {
                    # Deliberately not byte-equal to a reference. These are
                    # ephemeral schema-test inputs, never checked-in evidence.
                    "output_text": regression.selected_reference(fixture) + "（实测输出）",
                    "latencies_ms": [latency],
                    "median_latency_ms": latency,
                },
            }
        )
    result = {
        "schema_version": 2,
        "evidence_kind": regression.FORMAL_EVIDENCE_KIND,
        "corpus_release": fixtures["corpus_release"],
        "fixture_sha256": regression.sha256_file(regression.DEFAULT_FIXTURES),
        "suite_id": suite["id"],
        "source_language": source,
        "target_language": target,
        "inference": {
            "producer": regression.FORMAL_PRODUCER_ID,
            "engine_id": system_id,
            "model_id": f"model-{system_id}",
            "model_revision": "0123456789abcdef",
            "runtime_id": "android-runtime",
            "runtime_revision": "fedcba9876543210",
            "device_kind": "physical-android-device",
            "device_model": "benchmark-handset",
            "os_version": "Android-16",
            "architecture": "arm64-v8a",
            "started_at_utc": "2026-08-09T01:00:00Z",
            "completed_at_utc": "2026-08-09T01:05:00Z",
            "repetitions": 1,
            "latency_clock": "elapsed-realtime-monotonic",
            "network_path": "offline",
        },
        "cases": cases,
    }
    result["provenance"] = {
        "schema_version": 1,
        "producer_id": regression.FORMAL_PRODUCER_ID,
        "producer_source_sha256": regression.sha256_file(
            regression.DEFAULT_CANDIDATE_RUNNER_SOURCE
        ),
        "raw_inference_record_sha256": regression.formal_raw_inference_record_sha256(
            result
        ),
    }
    return result


def validate_candidate(document: dict, pair: str = "en-zh", *, formal: bool = True):
    fixtures = regression.load_json(regression.DEFAULT_FIXTURES)
    return regression.validate_candidate_against_suite(
        document,
        pair,
        regression.suite_map(fixtures)[pair],
        fixtures["corpus_release"],
        regression.sha256_file(regression.DEFAULT_FIXTURES),
        formal=formal,
    )


def completed_rating(template: dict, rater_id: str, score: int = 5) -> dict:
    rating = copy.deepcopy(template)
    rating["rater_id"] = rater_id
    for item in rating["ratings"]:
        item["adequacy"] = score
        item["fluency"] = score
    return rating


class FixtureValidationTest(unittest.TestCase):
    def test_public_fixture_release_is_complete_and_pinned(self) -> None:
        report = regression.validate_fixtures()
        self.assertEqual("2026.08-public-v2-original-references", report["corpus_release"])
        self.assertEqual(48, report["suite_stats"]["en-zh"]["case_count"])
        self.assertEqual(48, report["suite_stats"]["ja-zh"]["case_count"])

    def test_both_pairs_cover_required_domains_and_semantic_checks(self) -> None:
        report = regression.validate_fixtures()
        for pair in ("en-zh", "ja-zh"):
            self.assertTrue(
                regression.REQUIRED_DOMAINS
                <= set(report["suite_stats"][pair]["categories"])
            )
            self.assertGreaterEqual(report["suite_stats"][pair]["critical_check_count"], 60)
            self.assertGreaterEqual(report["suite_stats"][pair]["protected_gate_cases"], 1)

    def test_failure_contract_uses_production_response_classification(self) -> None:
        contract = regression.load_json(regression.DEFAULT_FAILURES)
        report = regression.validate_failure_contract(contract)
        self.assertIn("response", report["classifications"])
        for case in contract["cases"]:
            if case["id"] in {"http_200_malformed_json", "http_200_empty_translation"}:
                self.assertEqual("response", case["expected"]["classification"])

    def test_api_key_shaped_failure_fixture_is_rejected(self) -> None:
        contract = regression.load_json(regression.DEFAULT_FAILURES)
        contract["description"] += " sk-" + ("x" * 24)
        with self.assertRaisesRegex(ValueError, "API-key-shaped"):
            regression.validate_failure_contract(contract)

    def test_failure_contract_unknown_field_is_rejected(self) -> None:
        contract = regression.load_json(regression.DEFAULT_FAILURES)
        contract["cases"][0]["expected"]["provider_message"] = "copied payload"
        with self.assertRaisesRegex(ValueError, "schema mismatch"):
            regression.validate_failure_contract(contract)

    def test_failure_contract_duplicate_id_is_rejected(self) -> None:
        contract = regression.load_json(regression.DEFAULT_FAILURES)
        contract["cases"][1]["id"] = contract["cases"][0]["id"]
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            regression.validate_failure_contract(contract)

    def test_failure_contract_retry_attempt_mismatch_is_rejected(self) -> None:
        contract = regression.load_json(regression.DEFAULT_FAILURES)
        contract["cases"][0]["expected"]["maximum_attempts"] = 2
        with self.assertRaisesRegex(ValueError, "Retry/attempt"):
            regression.validate_failure_contract(contract)

    def test_corpus_poisoning_with_duplicate_case_id_is_rejected(self) -> None:
        document = regression.load_json(regression.DEFAULT_FIXTURES)
        document["suites"][0]["cases"][1]["id"] = document["suites"][0]["cases"][0]["id"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixtures = root / "translation-fixtures.json"
            pin = root / "translation-fixtures.sha256"
            notice_name = document["license"]["notice_file"]
            (root / notice_name).write_text("fixture notice\n", encoding="utf-8")
            regression.write_json(fixtures, document)
            pin.write_text(
                f"{regression.sha256_file(fixtures)}  {fixtures.name}\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                regression.validate_fixtures(
                    fixtures,
                    pin,
                    regression.DEFAULT_FAILURES,
                    regression.DEFAULT_THRESHOLDS,
                    regression.DEFAULT_CALIBRATION,
                )

    def test_pin_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            pin = Path(directory) / "translation-fixtures.sha256"
            pin.write_text("0" * 64 + "  translation-fixtures.json\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "pin mismatch"):
                regression.validate_fixtures(
                    regression.DEFAULT_FIXTURES,
                    pin,
                    regression.DEFAULT_FAILURES,
                    regression.DEFAULT_THRESHOLDS,
                    regression.DEFAULT_CALIBRATION,
                )

    def test_retired_reference_hashes_are_absent(self) -> None:
        fixtures = regression.load_json(regression.DEFAULT_FIXTURES)
        hashes = {
            regression.sha256_text(reference)
            for suite in fixtures["suites"]
            for case in suite["cases"]
            for reference in case["reference_translations"]
        }
        self.assertFalse(hashes & regression.RETIRED_REFERENCE_SHA256)

    def test_retired_near_duplicate_fingerprints_are_below_gate(self) -> None:
        fixtures = regression.load_json(regression.DEFAULT_FIXTURES)
        for suite in fixtures["suites"]:
            for case in suite["cases"]:
                for reference in case["reference_translations"]:
                    compact = "".join(reference.split())
                    hits = {
                        regression.sha256_text(compact[index : index + 12])
                        for index in range(max(0, len(compact) - 11))
                    } & regression.RETIRED_REFERENCE_NGRAM_SHA256
                    self.assertLess(len(hits), 2, case["id"])


class ThresholdSchemaTest(unittest.TestCase):
    def setUp(self) -> None:
        self.thresholds = regression.load_json(regression.DEFAULT_THRESHOLDS)
        self.release = regression.load_json(regression.DEFAULT_FIXTURES)["corpus_release"]

    def validate(self, document: dict) -> None:
        regression.validate_thresholds(document, self.release)

    def test_current_thresholds_and_calibration_validate(self) -> None:
        self.validate(self.thresholds)
        report = regression.validate_calibration(
            regression.load_json(regression.DEFAULT_CALIBRATION), self.release
        )
        self.assertEqual(6, report["entry_count"])

    def test_nan_automatic_threshold_is_rejected(self) -> None:
        self.thresholds["editions"]["lite"]["en-zh"]["minimum_bleu"] = math.nan
        with self.assertRaisesRegex(ValueError, "finite"):
            self.validate(self.thresholds)

    def test_infinite_comparison_threshold_is_rejected(self) -> None:
        self.thresholds["comparison"]["maximum_bleu_drop"] = math.inf
        with self.assertRaisesRegex(ValueError, "finite"):
            self.validate(self.thresholds)

    def test_negative_comparison_threshold_is_rejected(self) -> None:
        self.thresholds["comparison"]["maximum_chrf_pp_drop"] = -0.1
        with self.assertRaisesRegex(ValueError, ">= 0"):
            self.validate(self.thresholds)

    def test_automatic_rate_above_one_is_rejected(self) -> None:
        self.thresholds["editions"]["full"]["ja-zh"]["minimum_critical_check_rate"] = 1.1
        with self.assertRaisesRegex(ValueError, "<= 1"):
            self.validate(self.thresholds)

    def test_human_score_below_one_is_rejected(self) -> None:
        self.thresholds["editions"]["online"]["en-zh"]["minimum_mean_fluency"] = 0.9
        with self.assertRaisesRegex(ValueError, ">= 1"):
            self.validate(self.thresholds)

    def test_boolean_numeric_threshold_is_rejected(self) -> None:
        self.thresholds["editions"]["lite"]["en-zh"]["minimum_bleu"] = True
        with self.assertRaisesRegex(ValueError, "numeric"):
            self.validate(self.thresholds)

    def test_unknown_comparison_field_is_rejected(self) -> None:
        self.thresholds["comparison"]["magic"] = 1
        with self.assertRaisesRegex(ValueError, "unknown"):
            self.validate(self.thresholds)

    def test_invalid_minimum_rater_count_is_rejected(self) -> None:
        self.thresholds["human_review"]["minimum_raters_per_output"] = 1
        with self.assertRaisesRegex(ValueError, "2..20"):
            self.validate(self.thresholds)

    def test_online_failure_gate_cannot_be_disabled(self) -> None:
        self.thresholds["editions"]["online"]["required_failure_contract"] = False
        with self.assertRaisesRegex(ValueError, "must require"):
            self.validate(self.thresholds)

    def test_calibration_hash_mismatch_is_rejected(self) -> None:
        self.thresholds["calibration_manifest_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.validate(self.thresholds)

    def test_historical_calibration_cannot_claim_formal_gate(self) -> None:
        calibration = regression.load_json(regression.DEFAULT_CALIBRATION)
        calibration["formal_gate_eligible"] = True
        with self.assertRaisesRegex(ValueError, "must not claim"):
            regression.validate_calibration(calibration, self.release)


class CandidateSchemaTest(unittest.TestCase):
    def setUp(self) -> None:
        self.candidate = real_shaped_candidate("en-zh", "alpha")

    def test_minimal_candidate_joins_canonical_fields(self) -> None:
        materialized, evidence_hash = validate_candidate(self.candidate)
        self.assertEqual(48, len(materialized))
        self.assertRegex(evidence_hash, r"^[0-9a-f]{64}$")
        self.assertIn("reference_translations", materialized[0])
        self.assertIn("category", materialized[0])
        self.assertNotIn("reference_translations", self.candidate["cases"][0])

    def test_case_order_is_not_trusted_but_complete_set_is_accepted(self) -> None:
        self.candidate["cases"].reverse()
        materialized, _ = validate_candidate(self.candidate)
        fixtures = regression.load_json(regression.DEFAULT_FIXTURES)
        self.assertEqual(
            fixtures["suites"][0]["cases"][0]["id"], materialized[0]["id"]
        )

    def test_top_level_reference_poisoning_is_rejected(self) -> None:
        self.candidate["references"] = ["attacker"]
        with self.assertRaisesRegex(ValueError, "unknown"):
            validate_candidate(self.candidate)

    def test_case_category_poisoning_is_rejected(self) -> None:
        self.candidate["cases"][0]["category"] = "protected_span"
        with self.assertRaisesRegex(ValueError, "unknown"):
            validate_candidate(self.candidate)

    def test_case_check_poisoning_is_rejected(self) -> None:
        self.candidate["cases"][0]["critical_checks"] = []
        with self.assertRaisesRegex(ValueError, "unknown"):
            validate_candidate(self.candidate)

    def test_output_reference_poisoning_is_rejected(self) -> None:
        self.candidate["cases"][0]["candidate"]["reference_translation"] = "fake"
        with self.assertRaisesRegex(ValueError, "unknown"):
            validate_candidate(self.candidate)

    def test_source_hash_mismatch_is_rejected(self) -> None:
        self.candidate["cases"][0]["source_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "source_sha256"):
            validate_candidate(self.candidate)

    def test_missing_case_is_rejected(self) -> None:
        self.candidate["cases"].pop()
        with self.assertRaisesRegex(ValueError, "coverage mismatch"):
            validate_candidate(self.candidate)

    def test_duplicate_case_id_is_rejected(self) -> None:
        self.candidate["cases"][1]["case_id"] = self.candidate["cases"][0]["case_id"]
        with self.assertRaisesRegex(ValueError, "duplicate"):
            validate_candidate(self.candidate)

    def test_extra_case_is_rejected(self) -> None:
        extra = copy.deepcopy(self.candidate["cases"][0])
        extra["case_id"] = "unknown-extra"
        self.candidate["cases"].append(extra)
        with self.assertRaisesRegex(ValueError, "coverage mismatch"):
            validate_candidate(self.candidate)

    def test_fixture_hash_mismatch_is_rejected(self) -> None:
        self.candidate["fixture_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "fixture_sha256"):
            validate_candidate(self.candidate)

    def test_suite_mismatch_is_rejected(self) -> None:
        self.candidate["suite_id"] = "ja-zh-diverse-v1"
        with self.assertRaisesRegex(ValueError, "suite_id"):
            validate_candidate(self.candidate)

    def test_latency_coverage_mismatch_is_rejected(self) -> None:
        self.candidate["inference"]["repetitions"] = 2
        with self.assertRaisesRegex(ValueError, "latency coverage"):
            validate_candidate(self.candidate)

    def test_nan_latency_is_rejected(self) -> None:
        self.candidate["cases"][0]["candidate"]["latencies_ms"][0] = math.nan
        with self.assertRaisesRegex(ValueError, "finite"):
            validate_candidate(self.candidate)

    def test_median_mismatch_is_rejected(self) -> None:
        self.candidate["cases"][0]["candidate"]["median_latency_ms"] = 999
        with self.assertRaisesRegex(ValueError, "median"):
            validate_candidate(self.candidate)

    def test_empty_output_is_rejected(self) -> None:
        self.candidate["cases"][0]["candidate"]["output_text"] = "  "
        with self.assertRaisesRegex(ValueError, "non-empty"):
            validate_candidate(self.candidate)

    def test_completed_before_started_is_rejected(self) -> None:
        self.candidate["inference"]["completed_at_utc"] = "2026-08-08T00:00:00Z"
        with self.assertRaisesRegex(ValueError, "before"):
            validate_candidate(self.candidate)

    def test_unknown_inference_field_is_rejected(self) -> None:
        self.candidate["inference"]["secret"] = "value"
        with self.assertRaisesRegex(ValueError, "unknown"):
            validate_candidate(self.candidate)

    def test_formal_metadata_replay_marker_is_rejected(self) -> None:
        self.candidate["inference"]["model_id"] = "reference replay model"
        with self.assertRaisesRegex(ValueError, "non-inference marker"):
            validate_candidate(self.candidate)

    def test_reference_evidence_kind_is_rejected(self) -> None:
        self.candidate["evidence_kind"] = "reference_replay"
        with self.assertRaisesRegex(ValueError, "reference/fixture/replay"):
            validate_candidate(self.candidate)

    def test_complete_canonical_reference_replay_is_rejected_even_with_real_label(self) -> None:
        fixtures = regression.load_json(regression.DEFAULT_FIXTURES)
        suite = regression.suite_map(fixtures)["en-zh"]
        by_id = {case["id"]: case for case in suite["cases"]}
        for case in self.candidate["cases"]:
            case["candidate"]["output_text"] = regression.selected_reference(
                by_id[case["case_id"]]
            )
        with self.assertRaisesRegex(ValueError, "canonical reference replay"):
            validate_candidate(self.candidate)

    def test_complete_source_passthrough_is_rejected(self) -> None:
        fixtures = regression.load_json(regression.DEFAULT_FIXTURES)
        by_id = {
            case["id"]: case for case in regression.suite_map(fixtures)["en-zh"]["cases"]
        }
        for case in self.candidate["cases"]:
            case["candidate"]["output_text"] = by_id[case["case_id"]]["source_text"]
        with self.assertRaisesRegex(ValueError, "source fixture passthrough"):
            validate_candidate(self.candidate)

    def test_complete_invisible_and_format_only_replays_are_rejected(self) -> None:
        fixtures = regression.load_json(regression.DEFAULT_FIXTURES)
        by_id = {
            case["id"]: case for case in regression.suite_map(fixtures)["en-zh"]["cases"]
        }
        mutations = {
            "zero_width": lambda value: "\u200b".join(value) + "\u200b",
            "variation_selector": lambda value: value + "\ufe0f",
            "reserved_default_ignorable_2065": lambda value: value + "\u2065",
            "reserved_default_ignorable_fff0": lambda value: value + "\ufff0",
            "punctuation_whitespace": lambda value: f" \t{value}！？… \n",
        }
        for source_kind in ("reference", "source"):
            for mutation_name, mutate in mutations.items():
                with self.subTest(source_kind=source_kind, mutation=mutation_name):
                    candidate = real_shaped_candidate("en-zh", "alpha")
                    for case in candidate["cases"]:
                        fixture = by_id[case["case_id"]]
                        original = (
                            regression.selected_reference(fixture)
                            if source_kind == "reference"
                            else fixture["source_text"]
                        )
                        case["candidate"]["output_text"] = mutate(original)
                    expected = (
                        "reference replay after Unicode normalization"
                        if source_kind == "reference"
                        else "source fixture passthrough after Unicode normalization"
                    )
                    with self.assertRaisesRegex(ValueError, expected):
                        validate_candidate(candidate)

    def test_forty_seven_of_forty_eight_normalized_replays_are_rejected(self) -> None:
        fixtures = regression.load_json(regression.DEFAULT_FIXTURES)
        by_id = {
            case["id"]: case for case in regression.suite_map(fixtures)["en-zh"]["cases"]
        }
        for source_kind in ("reference", "source"):
            for invisible in ("\u200b", "\u2065", "\ufff0"):
                with self.subTest(source_kind=source_kind, invisible=hex(ord(invisible))):
                    candidate = real_shaped_candidate("en-zh", "alpha")
                    for case in candidate["cases"][:-1]:
                        fixture = by_id[case["case_id"]]
                        original = (
                            regression.selected_reference(fixture)
                            if source_kind == "reference"
                            else fixture["source_text"]
                        )
                        case["candidate"]["output_text"] = original + invisible
                    candidate["provenance"][
                        "raw_inference_record_sha256"
                    ] = regression.formal_raw_inference_record_sha256(candidate)
                    expected = (
                        "near-complete canonical reference replay"
                        if source_kind == "reference"
                        else "near-complete source fixture passthrough"
                    )
                    with self.assertRaisesRegex(ValueError, expected):
                        validate_candidate(candidate)

    def test_u2065_reference_replay_would_clear_metrics_but_is_rejected(self) -> None:
        fixtures = regression.load_json(regression.DEFAULT_FIXTURES)
        suite = regression.suite_map(fixtures)["en-zh"]
        by_id = {case["id"]: case for case in suite["cases"]}
        candidate = real_shaped_candidate("en-zh", "alpha")
        materialized = []
        for case in candidate["cases"]:
            fixture = by_id[case["case_id"]]
            case["candidate"]["output_text"] = (
                regression.selected_reference(fixture) + "\u2065"
            )
            joined = copy.deepcopy(fixture)
            joined["translation_raw"] = copy.deepcopy(case["candidate"])
            materialized.append(joined)
        candidate["provenance"][
            "raw_inference_record_sha256"
        ] = regression.formal_raw_inference_record_sha256(candidate)

        metrics = regression.automatic_metrics(materialized)
        self.assertGreater(metrics["bleu"], 90.0)
        self.assertEqual(64, metrics["critical_checks_passed"])
        self.assertTrue(metrics["protected_span_checks_passed"])
        with self.assertRaisesRegex(
            ValueError,
            "reference replay after Unicode normalization",
        ):
            validate_candidate(candidate)

    def test_formal_candidate_requires_pinned_runner_provenance(self) -> None:
        candidate = copy.deepcopy(self.candidate)
        candidate["provenance"]["producer_source_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "source hash mismatch"):
            validate_candidate(candidate)

    def test_formal_candidate_raw_record_hash_detects_output_edit(self) -> None:
        candidate = copy.deepcopy(self.candidate)
        candidate["cases"][0]["candidate"]["output_text"] += "人工修改"
        with self.assertRaisesRegex(ValueError, "raw inference record hash mismatch"):
            validate_candidate(candidate)

    def test_formal_candidate_rejects_unpinned_revision_label(self) -> None:
        candidate = copy.deepcopy(self.candidate)
        candidate["inference"]["model_revision"] = "latest"
        with self.assertRaisesRegex(ValueError, "pinned lowercase revision hash"):
            validate_candidate(candidate)


class SmokeAndProtectedGateTest(unittest.TestCase):
    def test_smoke_is_explicitly_non_model_and_never_release_ready(self) -> None:
        report = regression.smoke_report()
        self.assertTrue(report["passed"])
        self.assertFalse(report["release_ready"])
        self.assertIn("not_model_evidence", report["report_kind"])
        for edition in report["editions"].values():
            self.assertFalse(edition["release_ready"])

    def test_formal_gate_rejects_automated_only_flag(self) -> None:
        pairs = {pair: real_shaped_candidate(pair, "alpha") for pair in ("en-zh", "ja-zh")}
        with self.assertRaisesRegex(ValueError, "never accepts"):
            regression.run_gate(
                "lite", pairs, copy.deepcopy(pairs), automated_only_smoke=True
            )

    def test_formal_gate_rejects_reference_smoke_candidates(self) -> None:
        pairs = {
            pair: regression.make_reference_replay(pair, "smoke")
            for pair in ("en-zh", "ja-zh")
        }
        with self.assertRaisesRegex(ValueError, "evidence_kind"):
            regression.run_gate("lite", pairs, copy.deepcopy(pairs))

    def test_expected_to_actual_failure_replay_api_is_disabled(self) -> None:
        with self.assertRaisesRegex(ValueError, "not accepted"):
            regression.verify_failure_replay({}, {}, regression.DEFAULT_FAILURES)

    def test_tag_only_protected_case_is_in_hard_gate(self) -> None:
        materialized, _ = validate_candidate(real_shaped_candidate("en-zh", "alpha"))
        case = next(case for case in materialized if case["category"] == "protected_span")
        case["category"] = "ui"
        case["tags"] = ["protected"]
        case["translation_raw"]["output_text"] = "所有固定标记都被省略。"
        metrics = regression.automatic_metrics(materialized)
        self.assertFalse(metrics["protected_span_checks_passed"])
        self.assertGreaterEqual(metrics["protected_case_count"], 1)

    def test_check_rejects_nan_actual(self) -> None:
        with self.assertRaisesRegex(ValueError, "finite"):
            regression._check("nan", math.nan, ">=", 0)


class OnlineEvidenceTest(unittest.TestCase):
    def copied_expected_evidence(self, kind: str) -> dict:
        contract = regression.load_json(regression.DEFAULT_FAILURES)
        return {
            "schema_version": 2,
            "evidence_kind": kind,
            "contract_sha256": regression.sha256_file(regression.DEFAULT_FAILURES),
            "producer": "OnlineFailureContractExecutionTest",
            "producer_source_sha256": regression.sha256_file(
                regression.DEFAULT_ONLINE_EVIDENCE_SOURCE
            ),
            "cases": [
                {"case_id": case["id"], "actual": copy.deepcopy(case["expected"])}
                for case in contract["cases"]
            ],
        }

    def test_copied_online_replay_kind_is_rejected(self) -> None:
        evidence = self.copied_expected_evidence("synthetic_failure_replay")
        with self.assertRaisesRegex(ValueError, "rejects"):
            regression.verify_failure_evidence(
                evidence,
                regression.load_json(regression.DEFAULT_FAILURES),
            )

    def test_online_evidence_unknown_field_is_rejected(self) -> None:
        evidence = self.copied_expected_evidence(regression.ONLINE_EVIDENCE_KIND)
        evidence["copied_from_expected"] = True
        with self.assertRaisesRegex(ValueError, "unknown"):
            regression.verify_failure_evidence(
                evidence,
                regression.load_json(regression.DEFAULT_FAILURES),
            )

    def test_online_evidence_producer_source_hash_is_checked(self) -> None:
        evidence = self.copied_expected_evidence(regression.ONLINE_EVIDENCE_KIND)
        evidence["producer_source_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "source hash"):
            regression.verify_failure_evidence(
                evidence,
                regression.load_json(regression.DEFAULT_FAILURES),
            )

    def test_online_evidence_field_mismatch_is_reported(self) -> None:
        evidence = self.copied_expected_evidence(regression.ONLINE_EVIDENCE_KIND)
        evidence["cases"][0]["actual"]["retry"] = True
        with self.assertRaisesRegex(ValueError, "retry"):
            regression.verify_failure_evidence(
                evidence,
                regression.load_json(regression.DEFAULT_FAILURES),
            )

    def test_online_evidence_duplicate_case_is_rejected(self) -> None:
        evidence = self.copied_expected_evidence(regression.ONLINE_EVIDENCE_KIND)
        evidence["cases"][1]["case_id"] = evidence["cases"][0]["case_id"]
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            regression.verify_failure_evidence(
                evidence,
                regression.load_json(regression.DEFAULT_FAILURES),
            )

    def test_formal_gate_has_no_caller_online_evidence_parameter(self) -> None:
        self.assertNotIn("failure_evidence", inspect.signature(regression.run_gate).parameters)
        pairs = {
            pair: real_shaped_candidate(pair, "candidate")
            for pair in ("en-zh", "ja-zh")
        }
        forged = self.copied_expected_evidence(regression.ONLINE_EVIDENCE_KIND)
        with self.assertRaisesRegex(TypeError, "failure_evidence"):
            regression.run_gate(
                "online",
                pairs,
                copy.deepcopy(pairs),
                failure_evidence=forged,
            )


class BlindHumanReviewTest(unittest.TestCase):
    def setUp(self) -> None:
        self.systems = {
            system: {
                pair: real_shaped_candidate(pair, system)
                for pair in ("en-zh", "ja-zh")
            }
            for system in ("private-system-one", "private-system-two")
        }
        self.sheet, self.key, self.template = regression.make_blind_bundle(
            self.systems, secret=b"test-only-high-entropy-secret-32b"
        )

    def write_gate_inputs(
        self,
        directory: str,
        *,
        sheet: dict | None = None,
        key: dict | None = None,
        ratings: list[dict] | None = None,
        rubric: dict | None = None,
    ) -> dict:
        root = Path(directory)
        sheet_path = root / "review.blind-sheet.json"
        key_path = root / "review.blind-key.json"
        rating_documents = ratings if ratings is not None else [
            completed_rating(self.template, "reviewer-a"),
            completed_rating(self.template, "reviewer-b"),
        ]
        rating_paths = []
        regression.write_json(sheet_path, sheet if sheet is not None else self.sheet)
        regression.write_json(key_path, key if key is not None else self.key)
        for index, document in enumerate(rating_documents, start=1):
            path = root / f"reviewer-{index}.blind-ratings.json"
            regression.write_json(path, document)
            rating_paths.append(path)
        rubric_path = regression.DEFAULT_RUBRIC
        if rubric is not None:
            rubric_path = root / "human-rating-rubric.json"
            regression.write_json(rubric_path, rubric)
        return {
            "blind_sheet_path": sheet_path,
            "blind_key_path": key_path,
            "rating_paths": rating_paths,
            "rubric_path": rubric_path,
            "candidate_system": "private-system-two",
            "baseline_system": "private-system-one",
        }

    def test_public_sheet_hides_system_ids_case_ids_and_seed(self) -> None:
        serialized = json.dumps(self.sheet, ensure_ascii=False)
        self.assertNotIn("private-system-one", serialized)
        self.assertNotIn("private-system-two", serialized)
        self.assertNotIn("literary_long_sentence", serialized)
        self.assertNotIn("seed", serialized.casefold())

    def test_private_key_contains_no_seed_or_secret_hash(self) -> None:
        serialized = json.dumps(self.key, ensure_ascii=False).casefold()
        self.assertNotIn("seed", serialized)
        self.assertNotIn("secret", serialized)

    def test_rating_template_contains_no_system_identity(self) -> None:
        serialized = json.dumps(self.template, ensure_ascii=False)
        self.assertNotIn("private-system-one", serialized)
        self.assertNotIn("private-system-two", serialized)

    def test_blind_bundle_is_deterministic_only_with_internal_secret(self) -> None:
        second = regression.make_blind_bundle(
            self.systems, secret=b"test-only-high-entropy-secret-32b"
        )
        self.assertEqual((self.sheet, self.key, self.template), second)

    def test_complete_ratings_bind_summary_to_candidate_hashes(self) -> None:
        summary = regression.score_human_ratings(
            self.sheet,
            self.key,
            [completed_rating(self.template, "reviewer-a"), completed_rating(self.template, "reviewer-b")],
            regression.load_json(regression.DEFAULT_RUBRIC),
        )
        for system in ("private-system-one", "private-system-two"):
            for pair in ("en-zh", "ja-zh"):
                self.assertEqual(
                    regression.canonical_json_sha256(self.systems[system][pair]),
                    summary["systems"][system][pair]["system_evidence_sha256"],
                )

    def test_formal_gate_recomputes_verified_raw_rating_bundle_but_needs_attestation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = regression.run_gate(
                "full",
                self.systems["private-system-two"],
                self.systems["private-system-one"],
                **self.write_gate_inputs(directory),
            )
        self.assertFalse(report["release_ready"])
        self.assertTrue(report["metric_checks_passed"])
        self.assertFalse(report["automated_passed"])
        self.assertFalse(report["baseline_admission"]["passed"])
        self.assertFalse(report["human_review"]["passed"])
        self.assertTrue(report["human_review"]["score_checks_passed"])
        self.assertFalse(
            report["human_review"]["reviewer_authenticity"]["passed"]
        )
        self.assertFalse(report["runner_provenance"]["passed"])
        self.assertEqual(
            "recomputed_from_structurally_valid_unauthenticated_raw_ratings",
            report["human_review"]["status"],
        )
        self.assertIn(
            "authenticated_independent_human_reviewers_required",
            report["release_ready_blockers"],
        )
        self.assertEqual(2, report["human_review"]["evidence"]["raw_rating_document_count"])
        self.assertEqual(2, report["human_review"]["evidence"]["unique_rater_count"])

    def test_public_hashes_and_recomputed_raw_record_do_not_attest_forged_outputs(self) -> None:
        forged_candidate = copy.deepcopy(self.systems["private-system-two"])
        for document in forged_candidate.values():
            document["inference"]["device_model"] = "caller-fabricated-public-record"
            document["provenance"]["producer_source_sha256"] = regression.sha256_file(
                regression.DEFAULT_CANDIDATE_RUNNER_SOURCE
            )
            document["provenance"][
                "raw_inference_record_sha256"
            ] = regression.formal_raw_inference_record_sha256(document)
        systems = {
            "private-system-one": self.systems["private-system-one"],
            "private-system-two": forged_candidate,
        }
        sheet, key, template = regression.make_blind_bundle(
            systems,
            secret=b"test-only-forged-provenance-secret",
        )
        ratings = [
            completed_rating(template, "reviewer-a"),
            completed_rating(template, "reviewer-b"),
        ]
        with tempfile.TemporaryDirectory() as directory:
            report = regression.run_gate(
                "full",
                forged_candidate,
                self.systems["private-system-one"],
                **self.write_gate_inputs(
                    directory,
                    sheet=sheet,
                    key=key,
                    ratings=ratings,
                ),
            )
        self.assertTrue(report["metric_checks_passed"])
        self.assertFalse(report["automated_passed"])
        self.assertFalse(report["human_review"]["passed"])
        self.assertTrue(report["human_review"]["score_checks_passed"])
        self.assertFalse(report["runner_provenance"]["passed"])
        self.assertFalse(report["release_ready"])

    def test_fabricated_aggregate_summary_is_not_a_formal_gate_input(self) -> None:
        candidate_hashes = {
            pair: regression.canonical_json_sha256(
                self.systems["private-system-two"][pair]
            )
            for pair in ("en-zh", "ja-zh")
        }
        forged = {
            "schema_version": 2,
            "bundle_id": "fabricated-without-sheet-or-ratings",
            "sheet_sha256": "0" * 64,
            "corpus_release": "2026.08-public-v2-original-references",
            "fixture_sha256": regression.sha256_file(regression.DEFAULT_FIXTURES),
            "rater_count": 2,
            "systems": {
                "private-system-two": {
                    pair: {
                        "system_evidence_sha256": candidate_hashes[pair],
                        "mean_adequacy": 5,
                        "mean_fluency": 5,
                        "critical_error_rate": 0,
                        "case_coverage": 1,
                        "minimum_ratings_per_output": 2,
                        "rating_count": 96,
                    }
                    for pair in ("en-zh", "ja-zh")
                }
            },
        }
        with self.assertRaisesRegex(TypeError, "human_summary"):
            regression.run_gate(
                "full",
                self.systems["private-system-two"],
                self.systems["private-system-one"],
                human_summary=forged,
                candidate_system="private-system-two",
            )

    def test_formal_gate_rejects_synchronized_sheet_and_key_output_tamper(self) -> None:
        sheet = copy.deepcopy(self.sheet)
        key = copy.deepcopy(self.key)
        candidate_entry = next(
            entry for entry in key["entries"]
            if entry["system_id"] == "private-system-two"
        )
        item = next(
            item for item in sheet["items"]
            if item["item_id"] == candidate_entry["item_id"]
        )
        output = next(
            output for output in item["outputs"]
            if output["output_id"] == candidate_entry["output_id"]
        )
        output["text"] = "同步伪造的满分候选输出"
        key["sheet_sha256"] = regression.canonical_json_sha256(sheet)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "output is not bound"):
                regression.run_gate(
                    "full",
                    self.systems["private-system-two"],
                    self.systems["private-system-one"],
                    **self.write_gate_inputs(directory, sheet=sheet, key=key),
                )

    def test_formal_gate_rejects_blind_key_candidate_hash_tamper(self) -> None:
        key = copy.deepcopy(self.key)
        for entry in key["entries"]:
            if entry["system_id"] == "private-system-two":
                entry["system_evidence_sha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "not hash-bound"):
                regression.run_gate(
                    "full",
                    self.systems["private-system-two"],
                    self.systems["private-system-one"],
                    **self.write_gate_inputs(directory, key=key),
                )

    def test_formal_gate_rejects_synchronized_baseline_output_tamper(self) -> None:
        sheet = copy.deepcopy(self.sheet)
        key = copy.deepcopy(self.key)
        baseline_entry = next(
            entry for entry in key["entries"]
            if entry["system_id"] == "private-system-one"
        )
        item = next(
            item for item in sheet["items"]
            if item["item_id"] == baseline_entry["item_id"]
        )
        output = next(
            output for output in item["outputs"]
            if output["output_id"] == baseline_entry["output_id"]
        )
        output["text"] = "联合修改后的无关基线文字"
        key["sheet_sha256"] = regression.canonical_json_sha256(sheet)
        ratings = [
            completed_rating(self.template, "reviewer-a"),
            completed_rating(self.template, "reviewer-b"),
        ]
        for rating in ratings:
            rating["sheet_sha256"] = key["sheet_sha256"]
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "private-system-one evidence"):
                regression.run_gate(
                    "full",
                    self.systems["private-system-two"],
                    self.systems["private-system-one"],
                    **self.write_gate_inputs(
                        directory,
                        sheet=sheet,
                        key=key,
                        ratings=ratings,
                    ),
                )

    def test_formal_gate_rejects_raw_rating_tamper(self) -> None:
        ratings = [
            completed_rating(self.template, "reviewer-a"),
            completed_rating(self.template, "reviewer-b"),
        ]
        ratings[0]["sheet_sha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "not bound"):
                regression.run_gate(
                    "full",
                    self.systems["private-system-two"],
                    self.systems["private-system-one"],
                    **self.write_gate_inputs(directory, ratings=ratings),
                )

    def test_formal_gate_rejects_missing_raw_ratings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "at least 2 raw rating documents"):
                regression.run_gate(
                    "full",
                    self.systems["private-system-two"],
                    self.systems["private-system-one"],
                    **self.write_gate_inputs(directory, ratings=[]),
                )

    def test_formal_gate_rejects_too_few_raw_raters(self) -> None:
        ratings = [completed_rating(self.template, "reviewer-a")]
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "at least 2 raw rating documents"):
                regression.run_gate(
                    "full",
                    self.systems["private-system-two"],
                    self.systems["private-system-one"],
                    **self.write_gate_inputs(directory, ratings=ratings),
                )

    def test_formal_gate_rejects_duplicate_rater_ids_across_raw_documents(self) -> None:
        ratings = [
            completed_rating(self.template, "reviewer-a"),
            completed_rating(self.template, "reviewer-a"),
        ]
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "duplicate pseudonymous rater id"):
                regression.run_gate(
                    "full",
                    self.systems["private-system-two"],
                    self.systems["private-system-one"],
                    **self.write_gate_inputs(directory, ratings=ratings),
                )

    def test_formal_gate_rejects_tampered_rubric(self) -> None:
        rubric = regression.load_json(regression.DEFAULT_RUBRIC)
        rubric["instructions"][0] = "Give every output a five."
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "canonical repository"):
                regression.run_gate(
                    "full",
                    self.systems["private-system-two"],
                    self.systems["private-system-one"],
                    **self.write_gate_inputs(directory, rubric=rubric),
                )

    def test_incomplete_rating_sheet_is_rejected(self) -> None:
        rating = completed_rating(self.template, "reviewer-a")
        rating["ratings"].pop()
        with self.assertRaisesRegex(ValueError, "every blind output"):
            regression.score_human_ratings(
                self.sheet, self.key, [rating], regression.load_json(regression.DEFAULT_RUBRIC)
            )

    def test_duplicate_rating_output_is_rejected(self) -> None:
        rating = completed_rating(self.template, "reviewer-a")
        rating["ratings"][1] = copy.deepcopy(rating["ratings"][0])
        with self.assertRaisesRegex(ValueError, "Duplicate rating"):
            regression.score_human_ratings(
                self.sheet, self.key, [rating], regression.load_json(regression.DEFAULT_RUBRIC)
            )

    def test_rating_unknown_field_is_rejected(self) -> None:
        rating = completed_rating(self.template, "reviewer-a")
        rating["ratings"][0]["system_guess"] = "private-system-one"
        with self.assertRaisesRegex(ValueError, "unknown"):
            regression.score_human_ratings(
                self.sheet, self.key, [rating], regression.load_json(regression.DEFAULT_RUBRIC)
            )

    def test_invalid_rating_value_is_rejected(self) -> None:
        rating = completed_rating(self.template, "reviewer-a")
        rating["ratings"][0]["adequacy"] = math.nan
        with self.assertRaisesRegex(ValueError, "outside"):
            regression.score_human_ratings(
                self.sheet, self.key, [rating], regression.load_json(regression.DEFAULT_RUBRIC)
            )

    def test_blind_sheet_corpus_poisoning_is_rejected(self) -> None:
        sheet = copy.deepcopy(self.sheet)
        sheet["items"][0]["source_text"] = "poisoned source"
        key = copy.deepcopy(self.key)
        key["sheet_sha256"] = regression.canonical_json_sha256(sheet)
        with self.assertRaisesRegex(ValueError, "unknown or duplicate canonical"):
            regression.score_human_ratings(
                sheet,
                key,
                [completed_rating(self.template, "reviewer-a")],
                regression.load_json(regression.DEFAULT_RUBRIC),
            )

    def test_blind_key_from_another_sheet_is_rejected(self) -> None:
        sheet = copy.deepcopy(self.sheet)
        sheet["items"][0]["outputs"][0]["text"] += "tampered"
        with self.assertRaisesRegex(ValueError, "does not match"):
            regression.score_human_ratings(
                sheet,
                self.key,
                [completed_rating(self.template, "reviewer-a")],
                regression.load_json(regression.DEFAULT_RUBRIC),
            )

    def test_blind_key_unknown_field_is_rejected(self) -> None:
        key = copy.deepcopy(self.key)
        key["seed_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "unknown"):
            regression.score_human_ratings(
                self.sheet,
                key,
                [completed_rating(self.template, "reviewer-a")],
                regression.load_json(regression.DEFAULT_RUBRIC),
            )

    def test_repository_key_path_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "outside"):
            regression.ensure_external_blind_key_path(
                regression.ROOT / "private.blind-key.json"
            )

    def test_external_key_path_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            resolved = regression.ensure_external_blind_key_path(
                Path(directory) / "private.blind-key.json"
            )
            self.assertTrue(resolved.is_absolute())

    def test_short_blinding_secret_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "at least 32"):
            regression.make_blind_bundle(self.systems, secret=b"predictable")

    def test_all_five_scores_for_different_candidate_hash_are_rejected(self) -> None:
        key = copy.deepcopy(self.key)
        for entry in key["entries"]:
            if entry["system_id"] == "private-system-two" and entry["pair"] == "en-zh":
                entry["system_evidence_sha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "not hash-bound"):
                regression.run_gate(
                    "full",
                    self.systems["private-system-two"],
                    self.systems["private-system-one"],
                    **self.write_gate_inputs(directory, key=key),
                )


class CommandInputTest(unittest.TestCase):
    def test_system_specification_requires_both_pairs(self) -> None:
        parsed = regression.parse_system_specifications(
            [
                "old:en-zh=old-en.json",
                "old:ja-zh=old-ja.json",
                "new:en-zh=new-en.json",
                "new:ja-zh=new-ja.json",
            ]
        )
        self.assertEqual({"old", "new"}, set(parsed))
        with self.assertRaisesRegex(ValueError, "Every blind system"):
            regression.parse_system_specifications(
                [
                    "old:en-zh=old-en.json",
                    "old:ja-zh=old-ja.json",
                    "new:en-zh=new-en.json",
                ]
            )

    def test_pair_path_parser_rejects_duplicate_pair(self) -> None:
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            regression.parse_pair_paths(["en-zh=a", "en-zh=b", "ja-zh=c"])


if __name__ == "__main__":
    unittest.main()
