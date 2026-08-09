from __future__ import annotations

import copy
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("verify_translation_admission.py")
SPEC = importlib.util.spec_from_file_location("verify_translation_admission", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_PATH = REPO_ROOT / "docs/evidence/hymt2-stq-admission-source-v1.json"


def pr_payload(**updates: object) -> dict[str, object]:
    value: dict[str, object] = {
        "number": 22836,
        "html_url": "https://github.com/ggml-org/llama.cpp/pull/22836",
        "state": "open",
        "merged": False,
        "merged_at": None,
        # GitHub exposes a synthetic test-merge SHA while a PR is open. The
        # verifier must ignore it rather than treating it as an upstream merge.
        "merge_commit_sha": "7da680c0bee1288cb428d22dd5e0430d2cf73455",
        "updated_at": "2026-07-29T02:07:21Z",
        "head": {"sha": "7e74b8296fbb2e48ad2fbe4663410279bbd2a5e7"},
        "base": {"repo": {"full_name": "ggml-org/llama.cpp"}},
    }
    value.update(updates)
    return value


def score_summary(evaluated: list[str]) -> dict[str, object]:
    return {
        "schema": VERIFIER.SCORE_SCHEMA,
        "candidate_id": "hymt2-stq-2026-07-30-xiaomi15pro-android16",
        "route_id": "en-zh",
        "corpus_suite_id": "en-zh-diverse-v2",
        "corpus_sha256": "1" * 64,
        "source_model_sha256": "6" * 64,
        "runnable_model_sha256": "2" * 64,
        "transformation_manifest_sha256": "7" * 64,
        "apk_sha256": "3" * 64,
        "signer_cert_sha256": "4" * 64,
        "device_summary_sha256": "5" * 64,
        "evaluation_run_id": "stq-admission-run-1",
        "q4_bleu_retention_percent": 95.0,
        "critical_evaluated_ids": evaluated,
        "critical_regressed_ids": [],
        "raw_median_latency_ms": 349.9,
        "pipeline": {
            "median_latency_ms": 749.9,
            "p95_latency_ms": 1499.9,
            "timeout_count": 0,
        },
    }


class TranslationAdmissionVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = json.loads(SOURCE_PATH.read_text(encoding="utf-8"))
        _, cls.critical_ids_by_suite = VERIFIER.inspect_corpus(
            cls.source["artifacts"]["corpus"],
            REPO_ROOT,
        )

    def test_versioned_source_has_exact_schema(self) -> None:
        validated = VERIFIER.validate_source(copy.deepcopy(self.source))
        self.assertEqual(VERIFIER.SOURCE_SCHEMA, validated["schema"])
        self.assertEqual(["en-zh", "ja-zh"], [route["route_id"] for route in validated["routes"]])

    def test_versioned_source_binds_current_corpus_counts(self) -> None:
        corpus = self.source["artifacts"]["corpus"]
        self.assertEqual("2026.08-public-v2-original-references", corpus["corpus_id"])
        self.assertEqual(
            "043bb49a27d647a24aba96c605f8d5eea0b5fd8d19eac490161b4e48b772bd72",
            corpus["expected_sha256"],
        )
        self.assertEqual(
            {"en-zh-diverse-v2": 48, "ja-zh-diverse-v1": 48},
            corpus["suite_case_counts"],
        )
        self.assertEqual([64, 62], [route["expected_critical_check_count"] for route in self.source["routes"]])

    def test_source_rejects_incomplete_suite_case_counts(self) -> None:
        forged = copy.deepcopy(self.source)
        del forged["artifacts"]["corpus"]["suite_case_counts"]["ja-zh-diverse-v1"]

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "keys mismatch"):
            VERIFIER.validate_source(forged)

    def test_corpus_case_count_drift_fails_closed_even_with_matching_byte_hash(self) -> None:
        with tempfile.TemporaryDirectory(dir=REPO_ROOT) as temporary:
            path = Path(temporary) / "translation-fixtures.json"
            corpus_document = json.loads(
                (REPO_ROOT / "app/src/benchmark/assets/translation-fixtures.json")
                .read_text(encoding="utf-8")
            )
            corpus_document["suites"][0]["cases"].pop()
            path.write_text(
                json.dumps(corpus_document, ensure_ascii=False, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            forged = copy.deepcopy(self.source["artifacts"]["corpus"])
            forged["path"] = path.relative_to(REPO_ROOT).as_posix()
            forged["expected_sha256"] = VERIFIER.sha256_file(path)

            observation, _ = VERIFIER.inspect_corpus(forged, REPO_ROOT)

            self.assertFalse(observation["verified"])
            self.assertEqual(48, observation["expected_suite_case_counts"]["en-zh-diverse-v2"])
            self.assertEqual(47, observation["actual_suite_case_counts"]["en-zh-diverse-v2"])

    def test_source_rejects_caller_supplied_ancestor_boolean(self) -> None:
        forged = copy.deepcopy(self.source)
        forged["runtime"]["merge_ancestor_of_runtime"] = True

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "extra=.*merge_ancestor"):
            VERIFIER.validate_source(forged)

    def test_source_rejects_fictional_upstream_repository(self) -> None:
        forged = copy.deepcopy(self.source)
        forged["runtime"]["upstream_repository"] = "fiction/example"

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "canonical upstream"):
            VERIFIER.validate_source(forged)

    def test_source_rejects_not_measured_string_in_nullable_hash(self) -> None:
        forged = copy.deepcopy(self.source)
        forged["artifacts"]["apk"]["expected_sha256"] = "NOT_MEASURED"

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "invalid format"):
            VERIFIER.validate_source(forged)

    def test_pr_response_rejects_fictional_url(self) -> None:
        forged = pr_payload(html_url="https://example.invalid/pull/22836")

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "URL"):
            VERIFIER.validate_pr_payload(forged, "ggml-org/llama.cpp", 22836)

    def test_pr_response_rejects_fictional_head_commit(self) -> None:
        forged = pr_payload(head={"sha": "not-a-commit"})

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "invalid format"):
            VERIFIER.validate_pr_payload(forged, "ggml-org/llama.cpp", 22836)

    def test_open_pr_synthetic_merge_sha_is_never_merge_evidence(self) -> None:
        observed = VERIFIER.validate_pr_payload(
            pr_payload(),
            "ggml-org/llama.cpp",
            22836,
        )

        self.assertEqual("OPEN", observed["state"])
        self.assertIsNone(observed["merge_commit"])

    def test_merged_pr_requires_real_merge_commit(self) -> None:
        forged = pr_payload(state="closed", merged=True, merged_at="2026-08-09T00:00:00Z", merge_commit_sha="fake")

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "invalid format"):
            VERIFIER.validate_pr_payload(forged, "ggml-org/llama.cpp", 22836)

    def test_score_summary_maps_not_measured_to_json_null_only(self) -> None:
        forged = score_summary(self.critical_ids_by_suite["en-zh-diverse-v2"])
        forged["q4_bleu_retention_percent"] = "NOT_MEASURED"
        route = self.source["routes"][0]

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "must be a number"):
            VERIFIER.validate_score_summary(
                forged,
                route,
                self.critical_ids_by_suite["en-zh-diverse-v2"],
            )

    def test_score_summary_rejects_bleu_outside_zero_to_one_hundred(self) -> None:
        forged = score_summary(self.critical_ids_by_suite["en-zh-diverse-v2"])
        forged["q4_bleu_retention_percent"] = 100.001
        route = self.source["routes"][0]

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "<= 100"):
            VERIFIER.validate_score_summary(
                forged,
                route,
                self.critical_ids_by_suite["en-zh-diverse-v2"],
            )

    def test_score_summary_binds_complete_critical_ids(self) -> None:
        forged = score_summary(self.critical_ids_by_suite["en-zh-diverse-v2"])
        forged["critical_evaluated_ids"] = forged["critical_evaluated_ids"][:-1]
        route = self.source["routes"][0]

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "canonical corpus"):
            VERIFIER.validate_score_summary(
                forged,
                route,
                self.critical_ids_by_suite["en-zh-diverse-v2"],
            )

    def test_score_summary_rejects_same_count_fictional_critical_ids(self) -> None:
        expected = self.critical_ids_by_suite["en-zh-diverse-v2"]
        forged = score_summary([f"fictional.critical.{index}" for index in range(len(expected))])

        with self.assertRaisesRegex(VERIFIER.EvidenceError, "canonical corpus"):
            VERIFIER.validate_score_summary(forged, self.source["routes"][0], expected)

    def test_non_finite_measurement_is_rejected(self) -> None:
        with self.assertRaisesRegex(VERIFIER.EvidenceError, ">= 0"):
            VERIFIER.require_number(float("inf"), "latency")
        with self.assertRaises(ValueError):
            VERIFIER.canonical_json_bytes({"latency": float("inf")})

    def test_integer_ranges_match_kotlin_int_and_long(self) -> None:
        with self.assertRaisesRegex(VERIFIER.EvidenceError, "between"):
            VERIFIER.require_int(VERIFIER.KOTLIN_LONG_MAX + 1, "pss")
        with self.assertRaisesRegex(VERIFIER.EvidenceError, "between"):
            VERIFIER.require_int(
                VERIFIER.KOTLIN_INT_MAX + 1,
                "timeout",
                maximum=VERIFIER.KOTLIN_INT_MAX,
            )

    def test_json_loader_rejects_duplicate_object_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "duplicate.json"
            path.write_text('{"schema":"a","schema":"b"}', encoding="utf-8")

            with self.assertRaisesRegex(VERIFIER.EvidenceError, "duplicate JSON object key"):
                VERIFIER.load_json(path)

    def test_empty_observed_artifact_is_rejected_consistently(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "empty.gguf"
            path.write_bytes(b"")
            source = {
                "expected_sha256": "0" * 64,
                "path_environment": "SCREEN_TRANSLATION_TEST_EMPTY_ARTIFACT",
            }
            with patch.dict(
                os.environ,
                {"SCREEN_TRANSLATION_TEST_EMPTY_ARTIFACT": str(path)},
            ):
                with self.assertRaisesRegex(VERIFIER.EvidenceError, "empty artifact"):
                    VERIFIER.artifact_observation(source, REPO_ROOT)

    def test_release_binding_rejects_mixed_evaluation_runs(self) -> None:
        summary = {
            "candidate_id": "candidate",
            "corpus_sha256": "1" * 64,
            "source_model_sha256": "2" * 64,
            "runnable_model_sha256": "3" * 64,
            "transformation_manifest_sha256": "4" * 64,
            "apk_sha256": "5" * 64,
            "signer_cert_sha256": "6" * 64,
            "device_summary_sha256": "7" * 64,
            "evaluation_run_id": "run-a",
            "score_summary_sha256_by_route": {"en-zh": "8" * 64, "ja-zh": "9" * 64},
        }
        self.assertFalse(
            VERIFIER.release_bindings_match(
                summary,
                score_summary_sha256_by_route=summary["score_summary_sha256_by_route"],
                score_run_ids=["run-a", "run-b"],
                candidate_id="candidate",
                corpus_sha="1" * 64,
                source_sha="2" * 64,
                runnable_sha="3" * 64,
                manifest_sha="4" * 64,
                apk_sha="5" * 64,
                signer_sha="6" * 64,
                device_sha="7" * 64,
            ),
        )

    def test_generated_artifact_comparison_rejects_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "record.json"
            path.write_bytes(b"tampered\n")

            with self.assertRaisesRegex(VERIFIER.EvidenceError, "stale"):
                VERIFIER.compare(path, b"canonical\n")

    def test_sha_sidecar_is_bound_to_canonical_file_name(self) -> None:
        path = Path("hymt2-stq-admission-v1.json")
        digest = "6" * 64

        self.assertEqual(
            f"{digest}  hymt2-stq-admission-v1.json\n",
            VERIFIER.pin_text(path, digest),
        )


if __name__ == "__main__":
    unittest.main()
