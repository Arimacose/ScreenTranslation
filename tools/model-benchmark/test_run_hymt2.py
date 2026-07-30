#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_hymt2.py")
SPEC = importlib.util.spec_from_file_location("run_hymt2", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class HyMt2RunnerTest(unittest.TestCase):
    def test_prompt_uses_full_target_language_name(self) -> None:
        prompt = MODULE.build_prompt("Hello.", "Chinese")
        self.assertIn("into Chinese", prompt)
        self.assertTrue(prompt.endswith("\n\nHello."))

    def test_generation_cleanup_removes_runtime_markers(self) -> None:
        self.assertEqual(
            MODULE.clean_generation("译文 [end of text]\n"),
            "译文",
        )
        self.assertEqual(
            MODULE.clean_generation("译文<｜hy_end▁of▁sentence｜>"),
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
