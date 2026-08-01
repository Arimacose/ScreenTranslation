#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_translategemma.py")
SPEC = importlib.util.spec_from_file_location("run_translategemma", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class TranslateGemmaRunnerTest(unittest.TestCase):
    def test_english_prompt_uses_official_language_fields(self) -> None:
        prompt = MODULE.build_prompt("Hello.", "en", "zh")
        self.assertIn("English (en) to Chinese (zh)", prompt)
        self.assertIn("Hello.<end_of_turn>", prompt)
        self.assertTrue(prompt.endswith("<start_of_turn>model\n"))

    def test_japanese_prompt_uses_official_language_fields(self) -> None:
        prompt = MODULE.build_prompt("こんにちは。", "ja", "zh-Hans")
        self.assertIn("Japanese (ja) to Chinese (zh-Hans)", prompt)
        self.assertIn("こんにちは。<end_of_turn>", prompt)

    def test_generation_cleanup_removes_runtime_markers(self) -> None:
        self.assertEqual(
            MODULE.clean_generation("译文<end_of_turn>\n"),
            "译文",
        )
        self.assertEqual(
            MODULE.clean_generation("译文 [end of text]<|endoftext|>"),
            "译文",
        )

    def test_pipeline_parts_reuses_android_fixture_plan(self) -> None:
        case = {
            "source_text": "whole",
            "translation_pipeline": {"parts": ["first", "second"]},
        }
        self.assertEqual(MODULE.pipeline_parts(case), ["first", "second"])

    def test_pipeline_parts_falls_back_to_source(self) -> None:
        self.assertEqual(
            MODULE.pipeline_parts({"source_text": "whole"}),
            ["whole"],
        )


if __name__ == "__main__":
    unittest.main()
