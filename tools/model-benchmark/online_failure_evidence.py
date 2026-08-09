#!/usr/bin/env python3
"""Produce fresh current-checkout Online failure-contract evidence through Gradle.

Formal callers use :func:`run_fresh_online_failure_evidence`; they do not pass
an evidence JSON document. The function creates a one-use challenge, owns an
empty temporary output path, starts the production-path Kotlin test, and
validates the challenge-bound response before returning it. This proves a fresh
execution of the hash-pinned local checkout, not independent runner attestation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any, Mapping


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONTRACT = ROOT / "tools" / "model-benchmark" / "fixtures" / "online-failure-contract.json"
DEFAULT_PRODUCER_SOURCE = (
    ROOT
    / "app"
    / "src"
    / "testOnline"
    / "java"
    / "com"
    / "screentranslation"
    / "app"
    / "online"
    / "OnlineFailureContractExecutionTest.kt"
)
EXECUTION_CHAIN_PATHS = tuple(
    sorted(
        (
            "app/build.gradle.kts",
            "app/src/main/java/com/screentranslation/app/overlay/OverlayController.kt",
            "app/src/main/java/com/screentranslation/app/service/ScreenTranslationService.kt",
            "app/src/online/java/com/screentranslation/app/online/OnlineHttpPolicy.kt",
            "app/src/online/java/com/screentranslation/app/online/OpenAiChatProtocol.kt",
            "app/src/testOnline/java/com/screentranslation/app/online/OnlineFailureContractExecutionTest.kt",
            "app/src/testOnline/java/com/screentranslation/app/online/OnlineFailureEvidenceProtocol.kt",
            "build.gradle.kts",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
            "settings.gradle.kts",
            "tools/model-benchmark/online_failure_evidence.py",
        )
    )
)
CHALLENGE_PURPOSE = "online_failure_contract_production_execution"
EVIDENCE_KIND = "kotlin_policy_execution_challenge_response"
PRODUCER = "OnlineFailureContractExecutionTest"
CHALLENGE_LIFETIME_MILLIS = 10 * 60 * 1_000
SHA256_LENGTH = 64


def _canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _canonical_bytes(value: Any) -> bytes:
    return (_canonical_json(value) + "\n").encode("utf-8")


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _execution_chain_sha256(root: Path) -> str:
    """Hash every file that can define or launch the local evidence execution.

    The manifest is deliberately deterministic and mirrored in Kotlin. It
    narrows freshness to one exact checkout; because all hashes are public, it
    is structural integrity rather than a signature or remote attestation.
    """

    payload = bytearray()
    for relative in EXECUTION_CHAIN_PATHS:
        source = root / Path(relative)
        if not source.is_file():
            raise FileNotFoundError(f"Online evidence execution-chain file is missing: {source}")
        payload.extend(relative.encode("utf-8"))
        payload.extend(b"\0")
        payload.extend(_sha256_file(source).encode("ascii"))
        payload.extend(b"\n")
    return _sha256_bytes(bytes(payload))


def _gradle_user_home(environment: Mapping[str, str]) -> Path:
    configured = environment.get("GRADLE_USER_HOME", "").strip()
    return Path(configured).expanduser().resolve() if configured else (Path.home() / ".gradle").resolve()


def _reject_gradle_init_scripts(
    gradle_user_home: Path,
    gradle_install_home: Path | None = None,
) -> None:
    """Reject implicit user/install Gradle configuration for the formal run."""

    candidates = [
        gradle_user_home / "init.gradle",
        gradle_user_home / "init.gradle.kts",
        gradle_user_home / "gradle.properties",
    ]
    init_directories = [gradle_user_home / "init.d"]
    init_directories.extend(
        gradle_user_home.glob("wrapper/dists/*/*/gradle-*/init.d")
    )
    if gradle_install_home is not None:
        init_directories.append(gradle_install_home / "init.d")
    for init_directory in init_directories:
        if init_directory.is_dir():
            candidates.extend(init_directory.glob("*.gradle"))
            candidates.extend(init_directory.glob("*.gradle.kts"))
    present = sorted(str(path.resolve()) for path in candidates if path.is_file())
    if present:
        raise RuntimeError(
            "Fresh Online evidence rejects implicit Gradle user configuration: "
            + ", ".join(present)
        )


def _sanitized_gradle_environment(environment: Mapping[str, str]) -> dict[str, str]:
    blocked_exact = {
        "GRADLE_OPTS",
        "JAVA_OPTS",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS",
    }
    return {
        key: value
        for key, value in environment.items()
        if key.upper() not in blocked_exact
        and not key.upper().startswith("ORG_GRADLE_PROJECT_")
    }


def _load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as source:
        value = json.load(source)
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return value


def _exact_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        raise ValueError(
            f"{label} fields differ: missing={sorted(expected - actual)} "
            f"extra={sorted(actual - expected)}"
        )


def _is_sha256(value: Any) -> bool:
    return (
        isinstance(value, str)
        and len(value) == SHA256_LENGTH
        and all(character in "0123456789abcdef" for character in value)
    )


def make_challenge(
    contract_sha256: str,
    producer_source_sha256: str,
    execution_chain_sha256: str,
    *,
    now_epoch_ms: int | None = None,
    nonce: str | None = None,
) -> dict[str, Any]:
    now = int(time.time() * 1_000) if now_epoch_ms is None else now_epoch_ms
    challenge_nonce = secrets.token_hex(32) if nonce is None else nonce
    if not _is_sha256(challenge_nonce):
        raise ValueError("Online evidence challenge nonce must be 256 random bits")
    if not _is_sha256(execution_chain_sha256):
        raise ValueError("Online evidence execution-chain hash must be SHA-256")
    return {
        "schema_version": 1,
        "purpose": CHALLENGE_PURPOSE,
        "nonce": challenge_nonce,
        "issued_at_epoch_ms": now,
        "expires_at_epoch_ms": now + CHALLENGE_LIFETIME_MILLIS,
        "contract_sha256": contract_sha256,
        "producer_source_sha256": producer_source_sha256,
        "execution_chain_sha256": execution_chain_sha256,
    }


def _execution_sha256(nonce: str, case_id: str, actual: Mapping[str, Any]) -> str:
    payload = f"{nonce}\n{case_id}\n{_canonical_json(actual)}".encode("utf-8")
    return _sha256_bytes(payload)


def validate_fresh_online_failure_evidence(
    evidence: dict[str, Any],
    *,
    challenge: dict[str, Any],
    challenge_bytes: bytes,
    contract: dict[str, Any],
    contract_sha256: str,
    producer_source_sha256: str,
    execution_chain_sha256: str,
    run_started_epoch_ms: int,
    run_finished_epoch_ms: int,
    evidence_mtime_epoch_ms: int | None = None,
) -> dict[str, Any]:
    """Validate a response against gate-owned run context.

    This pure validator is useful for tests, but is not a formal entry point:
    caller-supplied challenge/timestamp values have no provenance. Only
    :func:`run_fresh_online_failure_evidence` owns the paths and starts Gradle.
    """

    _exact_keys(
        challenge,
        {
            "schema_version",
            "purpose",
            "nonce",
            "issued_at_epoch_ms",
            "expires_at_epoch_ms",
            "contract_sha256",
            "producer_source_sha256",
            "execution_chain_sha256",
        },
        "Online evidence challenge",
    )
    if challenge["schema_version"] != 1 or challenge["purpose"] != CHALLENGE_PURPOSE:
        raise ValueError("Unsupported Online evidence challenge")
    nonce = challenge["nonce"]
    if not _is_sha256(nonce):
        raise ValueError("Online evidence challenge nonce must be 256 random bits")
    issued_at = challenge["issued_at_epoch_ms"]
    expires_at = challenge["expires_at_epoch_ms"]
    if (
        not isinstance(issued_at, int)
        or isinstance(issued_at, bool)
        or not isinstance(expires_at, int)
        or isinstance(expires_at, bool)
        or expires_at <= issued_at
        or expires_at - issued_at > CHALLENGE_LIFETIME_MILLIS
    ):
        raise ValueError("Online evidence challenge window is invalid")
    if run_started_epoch_ms < issued_at or run_finished_epoch_ms > expires_at:
        raise ValueError("Online evidence Gradle run fell outside the challenge window")

    _exact_keys(
        evidence,
        {
            "schema_version",
            "evidence_kind",
            "challenge_nonce",
            "challenge_sha256",
            "generated_at_epoch_ms",
            "contract_sha256",
            "producer",
            "producer_source_sha256",
            "execution_chain_sha256",
            "cases",
        },
        "fresh Online failure evidence",
    )
    if evidence["schema_version"] != 3 or evidence["evidence_kind"] != EVIDENCE_KIND:
        raise ValueError(
            "Formal Online gate requires a fresh challenge response, not caller JSON"
        )
    if evidence["challenge_nonce"] != nonce:
        raise ValueError("Online failure evidence was replayed from another nonce")
    if evidence["challenge_sha256"] != _sha256_bytes(challenge_bytes):
        raise ValueError("Online failure evidence targets another challenge")
    generated_at = evidence["generated_at_epoch_ms"]
    if (
        not isinstance(generated_at, int)
        or isinstance(generated_at, bool)
        or generated_at < run_started_epoch_ms
        or generated_at > run_finished_epoch_ms
        or generated_at < issued_at
        or generated_at > expires_at
    ):
        raise ValueError("Online failure evidence timestamp is outside the live Gradle run")
    if evidence_mtime_epoch_ms is not None and evidence_mtime_epoch_ms + 2_000 < run_started_epoch_ms:
        raise ValueError("Online failure evidence file predates the live Gradle run")

    if (
        not _is_sha256(contract_sha256)
        or challenge["contract_sha256"] != contract_sha256
        or evidence["contract_sha256"] != contract_sha256
    ):
        raise ValueError("Online failure evidence targets another failure contract")
    if evidence["producer"] != PRODUCER:
        raise ValueError("Online failure evidence producer is not the production-path JVM test")
    if (
        challenge["producer_source_sha256"] != producer_source_sha256
        or evidence["producer_source_sha256"] != producer_source_sha256
    ):
        raise ValueError("Online failure evidence producer source hash mismatch")
    if (
        not _is_sha256(execution_chain_sha256)
        or challenge["execution_chain_sha256"] != execution_chain_sha256
        or evidence["execution_chain_sha256"] != execution_chain_sha256
    ):
        raise ValueError("Online failure evidence execution-chain hash mismatch")

    contract_cases = contract.get("cases")
    if not isinstance(contract_cases, list) or not contract_cases:
        raise ValueError("Online failure contract has no cases")
    expected_by_id: dict[str, dict[str, Any]] = {}
    for item in contract_cases:
        if not isinstance(item, dict) or not isinstance(item.get("expected"), dict):
            raise ValueError("Online failure contract case is invalid")
        case_id = item.get("id")
        if not isinstance(case_id, str) or not case_id or case_id in expected_by_id:
            raise ValueError("Online failure contract case id is invalid or repeated")
        expected_by_id[case_id] = item["expected"]

    response_cases = evidence["cases"]
    if not isinstance(response_cases, list):
        raise ValueError("Online failure evidence cases must be a list")
    actual_by_id: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(response_cases):
        if not isinstance(item, dict):
            raise ValueError(f"Online failure evidence case[{index}] must be an object")
        _exact_keys(item, {"case_id", "actual", "execution_sha256"}, f"Online evidence case[{index}]")
        case_id = item["case_id"]
        actual = item["actual"]
        if not isinstance(case_id, str) or case_id not in expected_by_id:
            raise ValueError(f"Unknown Online failure evidence case: {case_id!r}")
        if case_id in actual_by_id:
            raise ValueError(f"Duplicate Online failure evidence case: {case_id}")
        if not isinstance(actual, dict):
            raise ValueError(f"Online failure evidence actual is not an object: {case_id}")
        _exact_keys(actual, set(expected_by_id[case_id]), f"Online evidence {case_id}.actual")
        if item["execution_sha256"] != _execution_sha256(nonce, case_id, actual):
            raise ValueError(f"Online failure evidence challenge digest mismatch: {case_id}")
        actual_by_id[case_id] = actual

    if set(actual_by_id) != set(expected_by_id):
        raise ValueError(
            "Online failure evidence coverage mismatch: "
            f"missing={sorted(set(expected_by_id) - set(actual_by_id))}"
        )
    mismatches: list[str] = []
    for case_id, expected in expected_by_id.items():
        for field, wanted in expected.items():
            got = actual_by_id[case_id][field]
            if type(got) is not type(wanted) or got != wanted:
                mismatches.append(
                    f"{case_id}.{field}: expected={wanted!r}, actual={got!r}"
                )
    if mismatches:
        raise ValueError("Online failure evidence mismatch: " + "; ".join(mismatches))
    return {
        "passed": True,
        "fresh": True,
        "status": "fresh_kotlin_policy_execution",
        "case_count": len(expected_by_id),
        "challenge_sha256": evidence["challenge_sha256"],
        "contract_sha256": contract_sha256,
        "producer_source_sha256": producer_source_sha256,
        "execution_chain_sha256": execution_chain_sha256,
        "trust_boundary": "fresh_hash_pinned_current_checkout_not_attestation",
    }


def _gradle_command(root: Path) -> list[str]:
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise FileNotFoundError(f"Gradle wrapper is missing: {wrapper}")
    return [str(wrapper)]


def run_fresh_online_failure_evidence(
    *,
    timeout_seconds: int = 600,
) -> dict[str, dict[str, Any]]:
    """Launch Kotlin execution and return its evidence plus verification report.

    This API intentionally has no ``evidence`` parameter. Formal callers cannot
    substitute a pre-computed JSON document for the live Gradle execution. The
    result remains local current-checkout evidence, not external attestation.
    """

    root = ROOT.resolve()
    contract_path = DEFAULT_CONTRACT.resolve()
    producer_source = DEFAULT_PRODUCER_SOURCE.resolve()
    if not contract_path.is_file() or not producer_source.is_file():
        raise FileNotFoundError("Online failure contract or Kotlin producer source is missing")
    contract_bytes = contract_path.read_bytes()
    contract = json.loads(contract_bytes.decode("utf-8"))
    if not isinstance(contract, dict):
        raise ValueError("Online failure contract must be a JSON object")
    contract_hash = _sha256_bytes(contract_bytes)
    producer_hash = _sha256_file(producer_source)
    execution_chain_hash = _execution_chain_sha256(root)
    challenge = make_challenge(contract_hash, producer_hash, execution_chain_hash)
    challenge_bytes = _canonical_bytes(challenge)

    inherited_environment = dict(os.environ)
    configured_gradle_home = inherited_environment.get("GRADLE_HOME", "").strip()
    _reject_gradle_init_scripts(
        _gradle_user_home(inherited_environment),
        Path(configured_gradle_home).expanduser().resolve()
        if configured_gradle_home
        else None,
    )
    gradle_environment = _sanitized_gradle_environment(inherited_environment)

    with tempfile.TemporaryDirectory(prefix="screen-translation-online-evidence-") as temp_name:
        temp = Path(temp_name)
        challenge_path = temp / "challenge.json"
        evidence_path = temp / "response.json"
        with challenge_path.open("xb") as destination:
            destination.write(challenge_bytes)
        if evidence_path.exists():  # pragma: no cover - TemporaryDirectory invariant
            raise RuntimeError("Gate-owned Online evidence output unexpectedly exists")

        command = _gradle_command(root) + [
            "--no-daemon",
            "--console=plain",
            "--no-build-cache",
            "--no-configuration-cache",
            "--no-watch-fs",
            ":app:generateFreshOnlineFailureEvidence",
            f"-PonlineFailureEvidenceChallengeFile={challenge_path}",
            f"-PonlineFailureEvidenceOutputFile={evidence_path}",
        ]
        run_started = int(time.time() * 1_000)
        try:
            completed = subprocess.run(
                command,
                cwd=root,
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout_seconds,
                env=gradle_environment,
            )
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(
                f"Fresh Online evidence Gradle run exceeded {timeout_seconds}s"
            ) from error
        run_finished = int(time.time() * 1_000)
        if completed.returncode != 0:
            combined = "\n".join(
                value.strip() for value in (completed.stdout, completed.stderr) if value.strip()
            )
            tail = "\n".join(combined.splitlines()[-80:])
            raise RuntimeError(
                f"Fresh Online evidence Gradle run failed ({completed.returncode}):\n{tail}"
            )
        if not evidence_path.is_file() or evidence_path.stat().st_size <= 0:
            raise RuntimeError("Kotlin production-path test did not create fresh Online evidence")
        evidence_mtime = evidence_path.stat().st_mtime_ns // 1_000_000
        evidence = _load_json(evidence_path)
        verification = validate_fresh_online_failure_evidence(
            evidence,
            challenge=challenge,
            challenge_bytes=challenge_bytes,
            contract=contract,
            contract_sha256=contract_hash,
            producer_source_sha256=producer_hash,
            execution_chain_sha256=execution_chain_hash,
            run_started_epoch_ms=run_started,
            run_finished_epoch_ms=run_finished,
            evidence_mtime_epoch_ms=evidence_mtime,
        )
        return {"evidence": evidence, "verification": verification}


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run fresh Kotlin Online failure evidence with a one-use challenge"
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=600)
    args = parser.parse_args()
    result = run_fresh_online_failure_evidence(timeout_seconds=args.timeout_seconds)
    evidence = result["evidence"]
    verification = result["verification"]
    _write_json(args.output, evidence)
    print(
        json.dumps(
            {
                **verification,
                "evidence": str(args.output.resolve()),
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
