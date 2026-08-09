#!/usr/bin/env python3
"""Regression tests for the public translation quality gate."""

from __future__ import annotations

import copy
import importlib.util
import json
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("translation_regression.py")
SPEC = importlib.util.spec_from_file_location("translation_regression", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
regression = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(regression)


class FixtureValidationTest(unittest.TestCase):
    def test_public_fixture_release_is_complete_and_pinned(self) -> None:
        report = regression.validate_fixtures()
        self.assertEqual("2026.08-public-v1", report["corpus_release"])
        self.assertEqual(48, report["suite_stats"]["en-zh"]["case_count"])
        self.assertEqual(48, report["suite_stats"]["ja-zh"]["case_count"])
        self.assertGreaterEqual(
            report["suite_stats"]["en-zh"]["critical_check_count"],
            60,
        )
        self.assertGreaterEqual(
            report["suite_stats"]["ja-zh"]["critical_check_count"],
            60,
        )
        for pair in ("en-zh", "ja-zh"):
            categories = set(report["suite_stats"][pair]["categories"])
            self.assertTrue(regression.REQUIRED_DOMAINS <= categories)

    def test_bad_failure_replay_is_rejected(self) -> None:
        contract = regression.load_json(regression.DEFAULT_FAILURES)
        replay = regression.make_failure_replay()
        replay["cases"][0]["actual"]["retry"] = True
        with self.assertRaisesRegex(ValueError, "mismatch"):
            regression.verify_failure_replay(
                replay,
                contract,
                regression.DEFAULT_FAILURES,
            )

    def test_api_key_shaped_failure_fixture_is_rejected(self) -> None:
        contract = regression.load_json(regression.DEFAULT_FAILURES)
        contract["description"] += " sk-example0123456789"
        with self.assertRaisesRegex(ValueError, "API-key-shaped"):
            regression.validate_failure_contract(contract)


class AutomaticGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.candidates = {
            pair: regression.make_reference_replay(pair, "candidate")
            for pair in ("en-zh", "ja-zh")
        }
        cls.baselines = {
            pair: regression.make_reference_replay(pair, "baseline")
            for pair in ("en-zh", "ja-zh")
        }

    def test_reference_replay_exercises_every_automatic_check(self) -> None:
        report = regression.run_gate(
            "online",
            self.candidates,
            self.baselines,
            failure_replay=regression.make_failure_replay(),
            automated_only_smoke=True,
        )
        self.assertTrue(report["automated_passed"])
        self.assertFalse(report["release_ready"])
        self.assertEqual(
            "not_run_in_harness_smoke",
            report["human_review"]["status"],
        )
        for pair in ("en-zh", "ja-zh"):
            metrics = report["pairs"][pair]["candidate"]
            self.assertEqual(100.0, metrics["bleu"])
            self.assertEqual(100.0, metrics["chrf_pp"])
            self.assertEqual(1.0, metrics["critical_check_rate"])
            self.assertTrue(metrics["protected_span_checks_passed"])

    def test_protected_span_corruption_fails_even_when_other_scores_are_high(self) -> None:
        candidates = copy.deepcopy(self.candidates)
        case = next(
            case
            for case in candidates["en-zh"]["cases"]
            if case["id"] == "en_protected_url_email"
        )
        case["translation_raw"]["output_text"] = "链接与邮件地址均已省略。"
        report = regression.run_gate(
            "lite",
            candidates,
            self.baselines,
            automated_only_smoke=True,
        )
        self.assertFalse(report["automated_passed"])
        self.assertFalse(
            report["pairs"]["en-zh"]["candidate"]["protected_span_checks_passed"]
        )

    def test_result_from_a_different_corpus_hash_is_rejected(self) -> None:
        candidates = copy.deepcopy(self.candidates)
        candidates["ja-zh"]["method"]["fixture_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "fixture_sha256"):
            regression.run_gate(
                "full",
                candidates,
                self.baselines,
                automated_only_smoke=True,
            )


class BlindHumanReviewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.systems = {
            system_id: {
                pair: regression.make_reference_replay(pair, system_id)
                for pair in ("en-zh", "ja-zh")
            }
            for system_id in ("incumbent", "candidate")
        }

    def make_complete_rating(self, template: dict, rater_id: str) -> dict:
        rating = copy.deepcopy(template)
        rating["rater_id"] = rater_id
        for item in rating["ratings"]:
            item["adequacy"] = 5
            item["fluency"] = 5
        return rating

    def test_blind_bundle_is_deterministic_and_hides_system_ids(self) -> None:
        first = regression.make_blind_bundle(self.systems, "fixed-seed")
        second = regression.make_blind_bundle(self.systems, "fixed-seed")
        self.assertEqual(first, second)
        sheet_text = json.dumps(first[0], ensure_ascii=False)
        self.assertNotIn("incumbent", sheet_text)
        self.assertNotIn("candidate", sheet_text)
        self.assertIn("candidate", json.dumps(first[1]))

    def test_two_complete_blind_raters_satisfy_full_release_gate(self) -> None:
        sheet, key, template = regression.make_blind_bundle(
            self.systems,
            "release-review-seed",
        )
        summary = regression.score_human_ratings(
            sheet,
            key,
            [
                self.make_complete_rating(template, "reviewer-a"),
                self.make_complete_rating(template, "reviewer-b"),
            ],
            regression.load_json(regression.DEFAULT_RUBRIC),
        )
        report = regression.run_gate(
            "full",
            self.systems["candidate"],
            self.systems["incumbent"],
            human_summary=summary,
            candidate_system="candidate",
        )
        self.assertTrue(report["automated_passed"])
        self.assertTrue(report["human_review"]["passed"])
        self.assertTrue(report["release_ready"])

    def test_incomplete_rating_sheet_is_rejected(self) -> None:
        sheet, key, template = regression.make_blind_bundle(
            self.systems,
            "incomplete-review-seed",
        )
        rating = self.make_complete_rating(template, "reviewer-a")
        rating["ratings"].pop()
        with self.assertRaisesRegex(ValueError, "every blind output"):
            regression.score_human_ratings(
                sheet,
                key,
                [rating],
                regression.load_json(regression.DEFAULT_RUBRIC),
            )

    def test_blind_key_from_another_sheet_is_rejected(self) -> None:
        sheet, key, template = regression.make_blind_bundle(
            self.systems,
            "sheet-integrity-seed",
        )
        sheet["items"][0]["source_text"] = "tampered"
        with self.assertRaisesRegex(ValueError, "does not match"):
            regression.score_human_ratings(
                sheet,
                key,
                [self.make_complete_rating(template, "reviewer-a")],
                regression.load_json(regression.DEFAULT_RUBRIC),
            )


class CommandInputTest(unittest.TestCase):
    def test_system_specification_requires_both_pairs_for_two_systems(self) -> None:
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


if __name__ == "__main__":
    unittest.main()
