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


if __name__ == "__main__":
    unittest.main()
