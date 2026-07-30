#!/usr/bin/env python3
"""Regression tests for benchmark critical checks."""

from __future__ import annotations

import importlib.util
import json
import re
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

DEVICE_RUNNER_MODULE_PATH = (
    Path(__file__).resolve().parents[1]
    / "bergamot-android-poc"
    / "run_device.py"
)
DEVICE_RUNNER_SPEC = importlib.util.spec_from_file_location(
    "bergamot_android_device_runner",
    DEVICE_RUNNER_MODULE_PATH,
)
assert DEVICE_RUNNER_SPEC is not None and DEVICE_RUNNER_SPEC.loader is not None
device_runner = importlib.util.module_from_spec(DEVICE_RUNNER_SPEC)
DEVICE_RUNNER_SPEC.loader.exec_module(device_runner)


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

    def test_remote_hashes_match_every_expected_path(self) -> None:
        lines = device_runner.verify_remote_hashes(
            "abc123  /data/local/tmp/binary\n"
            "def456  /data/local/tmp/model\n",
            {
                "/data/local/tmp/binary": "abc123",
                "/data/local/tmp/model": "def456",
            },
        )
        self.assertEqual(2, len(lines))

    def test_remote_hash_mismatch_is_rejected(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "mismatched"):
            device_runner.verify_remote_hashes(
                "bad  /data/local/tmp/model\n",
                {"/data/local/tmp/model": "expected"},
            )


class DiverseFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        fixture_path = (
            Path(__file__).resolve().parents[2]
            / "app"
            / "src"
            / "benchmark"
            / "assets"
            / "translation-fixtures.json"
        )
        cls.fixture_document = json.loads(fixture_path.read_text(encoding="utf-8"))

    def fixture_case(self, case_id: str) -> dict:
        return next(
            case
            for suite in self.fixture_document["suites"]
            for case in suite["cases"]
            if case["id"] == case_id
        )

    def evaluate_fixture_case(self, case_id: str, text: str) -> list[bool]:
        return [
            score.evaluate_check(text, definition)["passed"]
            for definition in self.fixture_case(case_id)["critical_checks"]
        ]

    def test_fixture_suites_cover_both_language_pairs(self) -> None:
        suites = {
            (suite["source_language"], suite["target_language"]): suite
            for suite in self.fixture_document["suites"]
        }
        self.assertEqual({("en", "zh"), ("ja", "zh")}, set(suites))
        for suite in suites.values():
            self.assertEqual(40, len(suite["cases"]))
            self.assertGreaterEqual(
                len({case["category"] for case in suite["cases"]}),
                10,
            )
            self.assertGreaterEqual(
                sum(len(case["critical_checks"]) for case in suite["cases"]),
                50,
            )

    def test_fixture_ids_and_references_are_valid(self) -> None:
        identifiers: list[str] = []
        for suite in self.fixture_document["suites"]:
            for case in suite["cases"]:
                identifiers.append(case["id"])
                self.assertTrue(case["source_text"].strip())
                self.assertTrue(case["reference_translations"])
                self.assertTrue(
                    all(
                        reference.strip()
                        for reference in case["reference_translations"]
                    )
                )
        self.assertEqual(len(identifiers), len(set(identifiers)))

    def test_all_fixture_regular_expressions_compile(self) -> None:
        for suite in self.fixture_document["suites"]:
            for case in suite["cases"]:
                for definition in case["critical_checks"]:
                    for key in ("all_regex", "any_regex", "forbid_regex"):
                        for pattern in definition.get(key, []):
                            with self.subTest(case_id=case["id"], pattern=pattern):
                                re.compile(pattern)

    def test_second_reference_can_receive_full_sentence_score(self) -> None:
        case = {
            "id": "multi_reference",
            "category": "test",
            "source_text": "Source",
            "reference_translation": "第一种译文。",
            "reference_translations": ["第一种译文。", "第二种译文。"],
            "critical_checks": [],
            "ocr": {"output_text": "Source", "latencies_ms": [0.0]},
            "translation_raw": {
                "output_text": "第二种译文。",
                "latencies_ms": [1.0],
            },
        }
        result = score.score_translation_layer([case], "translation_raw")
        self.assertEqual(100.0, result["cases"][0]["bleu"])
        self.assertEqual(2, result["maximum_references_per_case"])
        self.assertIn("test", result["categories"])

    def test_fixture_critical_checks_override_legacy_id_checks(self) -> None:
        case = {
            "id": "offline_status",
            "critical_checks": [
                {"name": "custom", "all_literals": ["自定义标记"]},
            ],
        }
        self.assertEqual(
            "custom",
            score.case_check_definitions(case)[0]["name"],
        )

    def test_japanese_equivalent_chinese_wordings_pass(self) -> None:
        examples = {
            "ja_system_battery_schedule": (
                "电池电量为11%。节电模式在23小时30分时自动激活。",
                [True],
            ),
            "ja_omitted_subject_sent": (
                "我之前寄的，请检查一下。",
                [True, True],
            ),
            "ja_benefactive_kureta": (
                "田中先生把文件发给了我。",
                [True],
            ),
            "ja_technical_keys_logs": (
                "密钥在终端中生成，且从未写入日志。",
                [True, True],
            ),
            "ja_safety_medication": (
                "每天两次、饭后服用一片（5毫克）；"
                "24小时内不要超过10毫克。",
                [True, True, True],
            ),
            "ja_ui_permission_once": (
                "这次要允许此应用仅录制屏幕吗？",
                [True, True],
            ),
            "ja_system_airplane_exception": (
                "飞机模式已开启，但Wi-Fi和蓝牙可单独启用。",
                [True],
            ),
            "ja_system_train_door": (
                "接下来是新宿。右边的门会打开。",
                [True],
            ),
            "ja_system_approximate_location": (
                "位置信息仅“大约”共享，具体位置信息仍然关闭。",
                [True, True],
            ),
            "ja_numbers_download": (
                "下载了4.00个GiB中的1.25个（31.3%）。",
                [True],
            ),
            "ja_numbers_reservation": (
                "输入预订号码A-4096，并于2026年8月3日查询航班。",
                [True],
            ),
            "ja_logic_not_uncommon": (
                "第一个结果不正确并不罕见。",
                [True, True],
            ),
            "ja_conversation_soft_refusal": (
                "这可能有点困难。",
                [True],
            ),
            "ja_idiom_cat_hand": (
                "我太忙了，想借一只猫的手。",
                [True],
            ),
            "ja_technical_cache_not_data": (
                "清除缓存，而不是应用程序的数据。"
                "如果删除数据，下载的模型也会被删除。",
                [True, True],
            ),
            "ja_safety_refund": (
                "只有在未激活软件的情况下，"
                "才能在购买后十四天内申请退款。",
                [True, True],
            ),
            "ja_literary_wagahai": (
                "我是一只猫。我还没名字。",
                [True],
            ),
        }
        for case_id, (text, expected) in examples.items():
            with self.subTest(case_id=case_id):
                self.assertEqual(
                    expected,
                    self.evaluate_fixture_case(case_id, text),
                )

    def test_high_risk_meaning_errors_still_fail(self) -> None:
        examples = {
            "ja_ui_delete_irreversible": (
                "如果删除备份，则无法卸载。",
                [False],
            ),
            "ja_safety_medication": (
                "饭后每天服用一片（5毫克）两片。"
                "24小时内不要超过10毫克。",
                [True, False, True],
            ),
            "ja_safety_refund": (
                "如果您未启用软件，您可以在购买后14天内申请退款。",
                [False, True],
            ),
            "ja_numbers_reservation": (
                "输入预订号码A-4096，并于2012年8月3日查询航班。",
                [False],
            ),
            "ja_system_approximate_location": (
                "位置信息仅共享“大约”，正确的位置信息保持关闭。",
                [True, False],
            ),
        }
        for case_id, (text, expected) in examples.items():
            with self.subTest(case_id=case_id):
                self.assertEqual(
                    expected,
                    self.evaluate_fixture_case(case_id, text),
                )


if __name__ == "__main__":
    unittest.main()
