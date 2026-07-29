#!/usr/bin/env python3
"""Regression tests for benchmark critical checks."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("score.py")
SPEC = importlib.util.spec_from_file_location("model_benchmark_score", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
score = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(score)

BERGAMOT_MODULE_PATH = Path(__file__).with_name("run_bergamot.py")
BERGAMOT_SPEC = importlib.util.spec_from_file_location(
    "model_benchmark_bergamot",
    BERGAMOT_MODULE_PATH,
)
assert BERGAMOT_SPEC is not None and BERGAMOT_SPEC.loader is not None
bergamot = importlib.util.module_from_spec(BERGAMOT_SPEC)
BERGAMOT_SPEC.loader.exec_module(bergamot)


class CriticalCheckTest(unittest.TestCase):
    def evaluate_case(self, case_id: str, text: str) -> list[bool]:
        return [
            score.evaluate_check(text, definition)["passed"]
            for definition in score.CRITICAL_CHECKS[case_id]
        ]

    def test_issue18_semantic_variants_pass(self) -> None:
        text = (
            "文字从不离开电话，即使没有网络连接，模型仍然有效。"
        )
        self.assertEqual([True, True], self.evaluate_case("issue18_long_compound", text))

    def test_issue18_imperative_negation_is_recorded(self) -> None:
        text = (
            "文字永远不要离开电话，即使完全没有网络连接，模型仍然在工作。"
        )
        self.assertEqual([True, True], self.evaluate_case("issue18_long_compound", text))

    def test_issue18_mozilla_continuity_wording_passes(self) -> None:
        text = (
            "文本永远不会离开手机，即使完全没有网络连接，该模型仍能持续运行。"
        )
        self.assertEqual([True, True], self.evaluate_case("issue18_long_compound", text))

    def test_notification_mozilla_active_wording_passes(self) -> None:
        text = "捕获服务仍保持活跃状态，随后恢复了所选区域的翻译。"
        self.assertEqual([True, True], self.evaluate_case("notification_recovery", text))

    def test_compact_status_tokens_pass(self) -> None:
        text = "网络状态: OFFLINE。Worker 10/ 10 仍在运行；重试 1.5 s。"
        self.assertEqual([True, True], self.evaluate_case("offline_status", text))

    def test_corrupted_offline_token_fails(self) -> None:
        text = "网络状态: OFLINE。Worker 10/10 仍在运行；重试 1.5 s。"
        self.assertEqual([False, True], self.evaluate_case("offline_status", text))

    def test_amount_spacing_is_presentation_only(self) -> None:
        text = (
            "XT-2048 将于 2026-07-31 09:45 发货，总计 1 249.50 英镑。"
        )
        self.assertEqual([True], self.evaluate_case("numbers_and_symbols", text))

    def test_wrong_version_currency_fails(self) -> None:
        text = "版本 v.1.0，构建 37，金额 12 345.67 英镑，日期 2026-07-31。"
        self.assertEqual([False], self.evaluate_case("version_amount_date", text))


class BergamotInputTest(unittest.TestCase):
    def test_pipeline_parts_reuse_full_baseline_plan(self) -> None:
        case = {
            "source_text": "Ignored fallback.",
            "translation_pipeline": {"parts": ["First clause.", "Second clause."]},
        }
        self.assertEqual(
            ["First clause.", "Second clause."],
            bergamot.pipeline_parts(case),
        )

    def test_pipeline_parts_support_ocr_only_baseline(self) -> None:
        source = (
            "The translation engine runs entirely on your device, "
            "which means the text captured from the screen never leaves the phone "
            "and the model keeps working even when there is no network connection."
        )
        parts = bergamot.pipeline_parts({"source_text": source})
        self.assertGreaterEqual(len(parts), 2)
        self.assertTrue(all(parts))
        self.assertIn("which means", " ".join(parts))


if __name__ == "__main__":
    unittest.main()
