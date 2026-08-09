#!/usr/bin/env python3
"""Counterexample tests for fresh Online production-policy evidence."""

from __future__ import annotations

import copy
import importlib.util
import inspect
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("online_failure_evidence.py")
SPEC = importlib.util.spec_from_file_location("online_failure_evidence", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
online = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(online)


class FreshOnlineFailureEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contract = online._load_json(online.DEFAULT_CONTRACT)
        self.contract_sha256 = online._sha256_file(online.DEFAULT_CONTRACT)
        self.producer_sha256 = online._sha256_file(online.DEFAULT_PRODUCER_SOURCE)
        self.execution_chain_sha256 = online._execution_chain_sha256(online.ROOT)
        self.challenge = online.make_challenge(
            self.contract_sha256,
            self.producer_sha256,
            self.execution_chain_sha256,
            now_epoch_ms=1_000,
            nonce="a" * 64,
        )
        self.challenge_bytes = online._canonical_bytes(self.challenge)

    def response(self) -> dict:
        cases = []
        for item in self.contract["cases"]:
            actual = copy.deepcopy(item["expected"])
            cases.append(
                {
                    "case_id": item["id"],
                    "actual": actual,
                    "execution_sha256": online._execution_sha256(
                        self.challenge["nonce"], item["id"], actual
                    ),
                }
            )
        return {
            "schema_version": 3,
            "evidence_kind": online.EVIDENCE_KIND,
            "challenge_nonce": self.challenge["nonce"],
            "challenge_sha256": online._sha256_bytes(self.challenge_bytes),
            "generated_at_epoch_ms": 1_200,
            "contract_sha256": self.contract_sha256,
            "producer": online.PRODUCER,
            "producer_source_sha256": self.producer_sha256,
            "execution_chain_sha256": self.execution_chain_sha256,
            "cases": cases,
        }

    def validate(self, response: dict, **overrides: object) -> dict:
        arguments = {
            "challenge": self.challenge,
            "challenge_bytes": self.challenge_bytes,
            "contract": self.contract,
            "contract_sha256": self.contract_sha256,
            "producer_source_sha256": self.producer_sha256,
            "execution_chain_sha256": self.execution_chain_sha256,
            "run_started_epoch_ms": 1_100,
            "run_finished_epoch_ms": 1_300,
            "evidence_mtime_epoch_ms": 1_250,
        }
        arguments.update(overrides)
        return online.validate_fresh_online_failure_evidence(response, **arguments)

    def test_valid_challenge_response_passes(self) -> None:
        report = self.validate(self.response())
        self.assertTrue(report["passed"])
        self.assertTrue(report["fresh"])
        self.assertEqual(len(self.contract["cases"]), report["case_count"])

    def test_expected_copy_forgery_with_public_hashes_is_rejected(self) -> None:
        forged = {
            "schema_version": 2,
            "evidence_kind": "kotlin_policy_execution",
            "contract_sha256": self.contract_sha256,
            "producer": online.PRODUCER,
            "producer_source_sha256": self.producer_sha256,
            "cases": [
                {"case_id": item["id"], "actual": copy.deepcopy(item["expected"])}
                for item in self.contract["cases"]
            ],
        }

        with self.assertRaisesRegex(ValueError, "fresh Online failure evidence fields differ"):
            self.validate(forged)

    def test_response_for_another_nonce_is_rejected_as_replay(self) -> None:
        replay = self.response()
        second_challenge = online.make_challenge(
            self.contract_sha256,
            self.producer_sha256,
            self.execution_chain_sha256,
            now_epoch_ms=1_000,
            nonce="b" * 64,
        )

        with self.assertRaisesRegex(ValueError, "replayed from another nonce"):
            self.validate(
                replay,
                challenge=second_challenge,
                challenge_bytes=online._canonical_bytes(second_challenge),
            )

    def test_expected_copy_with_recomputed_digest_still_fails_policy_result(self) -> None:
        forged = self.response()
        first = forged["cases"][0]
        first["actual"]["classification"] = "server"
        first["execution_sha256"] = online._execution_sha256(
            self.challenge["nonce"], first["case_id"], first["actual"]
        )

        with self.assertRaisesRegex(ValueError, "Online failure evidence mismatch"):
            self.validate(forged)

    def test_formal_runner_has_no_caller_evidence_parameter(self) -> None:
        parameters = inspect.signature(online.run_fresh_online_failure_evidence).parameters
        self.assertEqual({"timeout_seconds"}, set(parameters))

    def test_execution_chain_mismatch_is_rejected(self) -> None:
        forged = self.response()
        forged["execution_chain_sha256"] = "b" * 64

        with self.assertRaisesRegex(ValueError, "execution-chain hash mismatch"):
            self.validate(forged)

    def test_gradle_init_script_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_name:
            gradle_home = Path(temporary_name)
            init_directory = gradle_home / "init.d"
            init_directory.mkdir()
            (init_directory / "forgery.gradle.kts").write_text(
                "// injected\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "implicit Gradle user configuration"):
                online._reject_gradle_init_scripts(gradle_home)

    def test_wrapper_distribution_init_script_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_name:
            gradle_home = Path(temporary_name)
            init_directory = (
                gradle_home
                / "wrapper"
                / "dists"
                / "gradle-version-bin"
                / "distribution-hash"
                / "gradle-version"
                / "init.d"
            )
            init_directory.mkdir(parents=True)
            (init_directory / "forgery.gradle").write_text(
                "// injected\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "implicit Gradle user configuration"):
                online._reject_gradle_init_scripts(gradle_home)

    def test_user_gradle_properties_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_name:
            gradle_home = Path(temporary_name)
            (gradle_home / "gradle.properties").write_text(
                "org.gradle.jvmargs=-javaagent:forged.jar\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "implicit Gradle user configuration"):
                online._reject_gradle_init_scripts(gradle_home)

    def test_gradle_environment_removes_script_injection_options(self) -> None:
        environment = {
            "PATH": "kept",
            "GRADLE_USER_HOME": "kept-too",
            "GRADLE_OPTS": "-I forged.gradle",
            "JAVA_TOOL_OPTIONS": "-javaagent:forged.jar",
            "jDk_JaVa_OpTiOnS": "-javaagent:forged-too.jar",
            "ORG_GRADLE_PROJECT_onlineFailureEvidenceOutputFile": "forged.json",
        }

        self.assertEqual(
            {"PATH": "kept", "GRADLE_USER_HOME": "kept-too"},
            online._sanitized_gradle_environment(environment),
        )


if __name__ == "__main__":
    unittest.main()
