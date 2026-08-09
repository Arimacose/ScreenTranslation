#!/usr/bin/env python3
"""Strict, key-free translation regression and blinded-review tooling.

The public corpus is the only authority for source text, references, tags,
categories and semantic checks.  Candidate files deliberately contain only
case identity, a source hash, model output, strict runner-claimed metadata and
structural hashes; those public self-hashes are not inference attestation.
Synthetic reference playback exists solely for a deterministic harness smoke
and is rejected by the formal release gate.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import hmac
import importlib.util
import json
import math
import re
import secrets
import statistics
import unicodedata
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FIXTURES = ROOT / "app" / "src" / "benchmark" / "assets" / "translation-fixtures.json"
DEFAULT_PIN = DEFAULT_FIXTURES.with_suffix(".sha256")
DEFAULT_FAILURES = Path(__file__).with_name("fixtures") / "online-failure-contract.json"
DEFAULT_THRESHOLDS = Path(__file__).with_name("fixtures") / "translation-regression-thresholds.json"
DEFAULT_CALIBRATION = Path(__file__).with_name("fixtures") / "translation-regression-calibration.json"
DEFAULT_RUBRIC = Path(__file__).with_name("fixtures") / "human-rating-rubric.json"
DEFAULT_ONLINE_EVIDENCE_SOURCE = (
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
DEFAULT_CANDIDATE_RUNNER_SOURCE = (
    ROOT
    / "app"
    / "src"
    / "benchmark"
    / "java"
    / "com"
    / "screentranslation"
    / "app"
    / "benchmark"
    / "ModelBenchmarkActivity.kt"
)
DEFAULT_BLIND_KEY_DIRECTORY = Path.home() / ".screentranslation" / "blind-review-keys"

PAIR_BY_SUITE = {"en-zh-diverse-v2": "en-zh", "ja-zh-diverse-v1": "ja-zh"}
REQUIRED_DOMAINS = {"protected_span", "long_form", "ui", "subtitle", "commerce"}
REQUIRED_REVIEW_DOMAINS = {"protected_span", "long", "ui", "subtitle", "commerce"}
REQUIRED_FAILURE_CLASSES = {
    "credentials",
    "rate_limit",
    "timeout",
    "temporary_service",
    "server",
    "response",
}
FORMAL_EVIDENCE_KIND = "real_model_inference"
FORMAL_PRODUCER_ID = "screen-translation-benchmark-runner/v1"
SMOKE_EVIDENCE_KIND = "synthetic_harness_smoke"
ONLINE_EVIDENCE_KIND = "kotlin_policy_execution"
BANNED_FORMAL_MARKERS = ("reference", "fixture", "replay", "synthetic", "smoke")
# Fixed Unicode 15.1 Default_Ignorable_Code_Point coverage used by replay
# normalization.  General categories alone are insufficient: reserved Cn
# code points such as U+2065 and U+FFF0 are default-ignorable as well.
DEFAULT_IGNORABLE_UNICODE_VERSION = "15.1.0"
DEFAULT_IGNORABLE_RANGES = (
    (0x00AD, 0x00AD),
    (0x034F, 0x034F),
    (0x061C, 0x061C),
    (0x115F, 0x1160),
    (0x17B4, 0x17B5),
    (0x180B, 0x180F),
    (0x200B, 0x200F),
    (0x202A, 0x202E),
    (0x2060, 0x206F),
    (0x3164, 0x3164),
    (0xFE00, 0xFE0F),
    (0xFEFF, 0xFEFF),
    (0xFFA0, 0xFFA0),
    (0xFFF0, 0xFFF8),
    (0x1BCA0, 0x1BCA3),
    (0x1D173, 0x1D17A),
    (0xE0000, 0xE0FFF),
)
MAX_NORMALIZED_REPLAY_FRACTION = 0.90
RETIRED_REFERENCE_SHA256 = {
    "945ad37059351ba0a1c03d81b549c016a96691a6e8bd1079ce33d85dd466123c",
    "e4f9bc931f42955c25eb6df798d8fcfc6eacd5414fe3d157e876df062a2713fa",
    "ce01663e1576cf9fdde36c473be032626031cad2c59752251fed98649c5dac21",
    "9d2ab7cb25e30e63208a955c41371d30a8d722b0d2ff1463ea3113c4fa6daf6a",
    "a1ae65c5361cf55cca961e83be0378f4686f348e5aab1fa7f590ac6e73667390",
    "00bbcf9cfeefc7eb5e96c273b15e0113ad794edbb9d1cd0675090d00531cfbab",
}
RETIRED_REFERENCE_NGRAM_SHA256 = {
    "00bbcf9cfeefc7eb5e96c273b15e0113ad794edbb9d1cd0675090d00531cfbab",
    "012048bb92a3478942a2c57346db75eeb676c0178cee02551029f6641e0175ab",
    "053b2a4fa7a6d20df634e207d0c782ceeda706da7895b0ca7731c7066737c73a",
    "054243bd75cdf44de8b779a4c3c0cb3c2cf4462af2647a889743283b53acbb4f",
    "092e9ea37f75e89150a397110e9ce0a193f4e900b9d7af6b8ef0da885007c64f",
    "0de1b4c2a3241b23db7e7ec2215e60bf3e4bf88754c98b91e5c77498faa79bd8",
    "100515f37c1e8505f7315efdec9c6bd0e435dbebc7bc123353845bac35f9cb8e",
    "11a5aa1921c9cdd759e209300f679b3d98b4f007fde36dbdf4b9695f34d8e609",
    "170b349c577735e8f4cc555a245582fc8bcf06234d18076718eb443ce32cfb47",
    "1945c35c238b9d3b7099d01254548ae01bf2447fd374ea0690ee4f386085648c",
    "2358045b3b3f93cd7ff261c19476767b77cc8d96cb002d288941122ed413a89e",
    "2421dcc753266c7f6ab66d9d1dfeab437bf759344a77e65ef1e81a96eeaf847c",
    "2462f5f7e6e336a9246d31b407ea9d8bf0dceefbd760abe8af70baf2e7b1e891",
    "249844c1fafb1e5e52173b1d6b88badcd5eb18de7bd471a8cbbb23742f7905d8",
    "24c5f29273bcc292268d6b4c4c323ca1995e08edbf76f74a7b709eb445106183",
    "26a352ba91d5d4ce280d31d0ba9d819c137d41250a4b20e4a96a44dc9ad91ed4",
    "26c34a8b26602c4e88ee0faaa6b7c2a695f92e2c86c5ad0cbeafaf277f66f04f",
    "26f20543361da14ae123cac5759a9d375de4cea542c1c596c20fdbe24e2a5805",
    "2748a85a23a6fd0fac19f353a5a54c42b16982e0d9011d91cc54b513cf4c9fd5",
    "2928306b33cb1620aae119ef9c7804b61f53e5afe6855f9c8b86cf11a0f542f2",
    "2abaf8c406721585602251e6ec1c403b6fbdde4b0d66b596bf453a6b2aa9c29e",
    "2ba831ba5f6817c11e5fa7ff28a95e96bdf5584cee36f81dd37c647b0fe0dcef",
    "2ca2bfc7ddfae047e8a90d608da7c4eebcd5c3a2ba2179006ee8de8aeba02609",
    "2de4b96ed1671c1771b3b62feb5f4462bacbc81a8fdc4f1015990245a6aeb9d4",
    "3362890ba474effd241f9c0216c387a472be4a614a04c239162a240fb767dee9",
    "3424f19af8fd4350a73f2543bdd1b774d6ed91dd28c0318ab034d67cb4616302",
    "386dca6fb5f19bbc20250945bd99e4cf55161009824a279ac39d4954f331c79f",
    "3eafeed8a7c9393234eee556f65ea248bc877a463e42b48517bb7c3d961fa35d",
    "419724677b51c6804347203691902a4ebaf2f26cd9825b6300bc57137cf833d8",
    "41f0e2c720e439b7396e96b744dbf783950855272484a05c16db387b4bd213af",
    "4208fa6e46db6ceac0e837fa5f9aadb04d3183b00b9e0e9b4945900025e69e56",
    "438f144c8bb2e12e42491929edc0d40284fa77887ea01552ede3fb3738e92c9d",
    "457d18a5217bf333c285cc6d9517b0de23a069b346cf3d34fdda63c83d7a19a3",
    "460504345f6fe91bfbc14195798a81c8f59d59a5ae05ed370fe75f92cdb7d338",
    "475d98a11cf6ea39b9a05e6dbfba7862c36d4a25d9c5667707976029f33f36e3",
    "4a5076c466ba59222e3785de79169d79e4a319f1ec1909cb1865afcc1c96bc9e",
    "4d790a17dfc45fb58589b15431cba55825771dc71c0ffe36a532b0f4b2e9a1f3",
    "4d9e13e5473a19a22c3a41361855b3d557cb6ef6dcdd87f486656d0a0714caca",
    "51072f396d6b3250ee452e5caa3846b7c623095c1b004ba823755f59da31c745",
    "582654219a32ec0c83e5bb34297b69cd0208d62f7000db0564cbacb051e8b1bc",
    "585745ee8c708bcbc4792be1dbc675157f6b0ac668b7683746128eeb3f36a929",
    "59baee98d0ab92bff47d0f2b6a8ea51d2c12753ca593f560fe6c30267a245f74",
    "5a8fae5290045315dcd69f8fbcc97e1496d5e3f65b54fa692d3cfc376974d973",
    "5d718493dcb1f7edb2be927abc0a20bbfb5cd80679b988dd5a33434b0c7e0b8c",
    "64505f725fccc1b1e2285d24b770d2cbd752eae8e94dbdf83cb8279a9ee7f9e9",
    "6d3507c3ce9bdd9e86bc8f048675f044d16039e00378648b743d8fc469243563",
    "6e50211245f33224e599debc5757eba6bf399c801e51ace9819c73739e064acd",
    "7086c5fbd96a2a1e6b7b3442d8d2edac615e9c4f64161511210dee557bb5df3b",
    "7095e1322e2a26171d0e6c9ef1b268430b3ee4465a99fddbbdaa041663f63c07",
    "72462ee2c00d48f0a7c78993a36729e26e096b1377e4637cf4daf880a3ad4284",
    "74205a49cdd7215be6320dbc1a5731088caf2620bf14b09a527411c32e238190",
    "74d88c0a3e58e61b598398d050c7165d041b7fdbbbbbab9abfa9c2ec510cff12",
    "7cd911bec4594e0da6616b84cdd90d9ff73a9ff346df8cb205d09abf2764a255",
    "7d5c95a1b6e120fcb4a2ec31e49d2b2f7d53c33c365e6d6affd578531ace640b",
    "7fcab100d7324ff5ddc8fef957c3009c60c13445baeebc51ceb22db04c06c897",
    "801e6fe0b52a788d4b216945198cdbbc0c9e1ca37485b2e198b766e42bd5d579",
    "88371e811039e328d567c674678873f58bac35416be8495c1e207fb7fa4f58d8",
    "8904962f285d8b900e223097ddf2954b8b85e7fb68cb63a10039460584cb8b07",
    "91d524321642dddec9b611f63a0ad1275cc6ddcd22cfac8e78708def61a34bb0",
    "967cb2c8d05922de68027920c6d1316b51f53c54d83139b5c017d7703b21bde3",
    "9859cd8c74e828a72511e65135741b4027e2c1e60aabe89e9df23cbdf9e5cc62",
    "99bc851420321c8820de2f02b3417bfe8948f68eea9ad5608f8c09fbc308b952",
    "a1ae65c5361cf55cca961e83be0378f4686f348e5aab1fa7f590ac6e73667390",
    "a548da02e3ab40bce0034d22f368544c9bfa6df542b51563c9ed6f5a8dfb82f8",
    "a80465b916d88b1a61db3b5f9d4e1e0be0194cb6638eac7fa906ad3afe66f1ea",
    "a82959f29c05d86ae6973383a56202bcd8e90184dcb1c471f36b4c3594f3b06b",
    "ad9f09257cf213863521742f36204108bfc7b86ce33a6e7b0d2e9b82f4cb5d36",
    "aed3f2573af346f9c77e6f4d64072738c1082330986ef3804d487e8c2c161a24",
    "b07e7c2aacf1e9b2786b3a8424809316408cd2a3a24983803f9d06a3d0295921",
    "b6c22e669e4529867f6a956ef8a39bf0bc18ce06a8e93755aa86e48d31cd257b",
    "b8608ecf8a0920d7dec106d52121f4a8ffdaba18f0ecb82dd670e40fefd3bee0",
    "bf1b97c0d15d4d6aac05bf7079bcf7506e78f639be9f4674eb5ba1a44be11cfd",
    "bfe754a9b22d616d89dafefe4a7fa5dd5900b510aa1089efb03c77fa03088a5b",
    "c0d23c06779624e7b241dbdd686e64e4f74364708af426ae99828d3b3f10e325",
    "c9411d40f5f8dbe5c4c82670a169d5a1cd75405ec13f7e5607fad4bbac273059",
    "cb85f3aa74f525263fcb30172c3cebaa76005ee5474b91910eb5ad4ad2dc19c3",
    "ce4d2f51ecbf8e25b31afdad8782a78fbad347a5ebd57fbc67e58917feb5fd5d",
    "ceee42bd143a773293434554a3b4c3d58a4a5de0c109ff5135f4818b1e88598e",
    "d33c714cb1094fe32c2e0d28748a601beccfc0a12dfa50d3062a7fdd756e7fc1",
    "d5a2958af66d9d7eb778fc56e1c5bfeaf5ee72e750a98e5d9cae69cc3e82010c",
    "d68809997ec5b345d7ddc48b5acba920051615c6807dd72f2f8fed8635a3d9a5",
    "d9f618e24b01de784a004a51d8abe2d76ef47d1df0cd14962bad5fae8e8a9f95",
    "e00105b7f31720a8fbe7a1cacaf2def1806fc1b37b001aa9babb59f05f2ead63",
    "e5ab2803b2231470470e7a6844268625368748952a0ce421ddb9e423a85a6d35",
    "e6680cc02198cc4b9191b454000ec72b370bf3c1d96363a40ce92108fccd7040",
    "e70094967df07bbb26960e2483ab11f5cbefe85978434510063ddb6c99cadc1a",
    "ec8705037529cd8185a55f90dec1e467232c7c2519802ae500e6680e91161e2f",
    "ed389104437787df7196639fd67a57bbc8a1aa9ba12569b5fd85fe59b56e9dbc",
    "f3e910627bf8abd6f389c3499458052b6009f6a6f784c764b27b499d7ac82c5f",
    "f79adbe46708ae292a465f943c535d1c75c7098167c63e328576a58141a6a353",
}

CANDIDATE_KEYS = {
    "schema_version",
    "evidence_kind",
    "corpus_release",
    "fixture_sha256",
    "suite_id",
    "source_language",
    "target_language",
    "inference",
    "cases",
}
PROVENANCE_KEYS = {
    "schema_version",
    "producer_id",
    "producer_source_sha256",
    "raw_inference_record_sha256",
}
INFERENCE_KEYS = {
    "producer",
    "engine_id",
    "model_id",
    "model_revision",
    "runtime_id",
    "runtime_revision",
    "device_kind",
    "device_model",
    "os_version",
    "architecture",
    "started_at_utc",
    "completed_at_utc",
    "repetitions",
    "latency_clock",
    "network_path",
}
CASE_KEYS = {"case_id", "source_sha256", "candidate"}
OUTPUT_KEYS = {"output_text", "latencies_ms", "median_latency_ms"}
PAIR_THRESHOLD_KEYS = {
    "minimum_bleu",
    "minimum_chrf_pp",
    "minimum_critical_check_rate",
    "minimum_mean_adequacy",
    "minimum_mean_fluency",
}


def _load_score_module() -> Any:
    module_path = Path(__file__).with_name("score.py")
    spec = importlib.util.spec_from_file_location("translation_regression_score", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load scorer: {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _load_online_evidence_module() -> Any:
    module_path = Path(__file__).with_name("online_failure_evidence.py")
    spec = importlib.util.spec_from_file_location(
        "translation_regression_online_failure_evidence",
        module_path,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load Online evidence runner: {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


score = _load_score_module()
online_evidence = _load_online_evidence_module()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return value


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_text(value: str) -> str:
    return sha256_bytes(value.encode("utf-8"))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_sha256(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return sha256_bytes(encoded)


def read_pinned_hash(path: Path) -> tuple[str, str]:
    fields = path.read_text(encoding="utf-8").strip().split()
    if len(fields) != 2 or not re.fullmatch(r"[0-9a-f]{64}", fields[0]):
        raise ValueError(f"Invalid SHA-256 pin: {path}")
    return fields[0], fields[1].lstrip("*")


def _exact_keys(value: Any, expected: set[str], context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{context} must be an object")
    actual = set(value)
    if actual != expected:
        raise ValueError(
            f"{context} schema mismatch; missing={sorted(expected - actual)}, "
            f"unknown={sorted(actual - expected)}"
        )
    return value


def _nonempty_string(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{context} must be a non-empty string")
    if any(ord(character) < 32 for character in value):
        raise ValueError(f"{context} contains a control character")
    return value


def _output_string(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{context} must be a non-empty string")
    if any(ord(character) < 32 and character not in "\t\r\n" for character in value):
        raise ValueError(f"{context} contains a disallowed control character")
    return value


def _finite_number(
    value: Any,
    context: str,
    *,
    minimum: float | None = None,
    maximum: float | None = None,
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{context} must be numeric")
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"{context} must be finite")
    if minimum is not None and number < minimum:
        raise ValueError(f"{context} must be >= {minimum}")
    if maximum is not None and number > maximum:
        raise ValueError(f"{context} must be <= {maximum}")
    return number


def _integer(value: Any, context: str, *, minimum: int, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValueError(f"{context} must be an integer in {minimum}..{maximum}")
    return value


def _utc_timestamp(value: Any, context: str) -> datetime:
    text = _nonempty_string(value, context)
    if not text.endswith("Z"):
        raise ValueError(f"{context} must be an explicit UTC timestamp ending in Z")
    try:
        parsed = datetime.fromisoformat(text[:-1] + "+00:00")
    except ValueError as error:
        raise ValueError(f"{context} is not ISO-8601") from error
    if parsed.tzinfo is None:
        raise ValueError(f"{context} must include UTC")
    return parsed.astimezone(timezone.utc)


def suite_map(document: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    suites: dict[str, dict[str, Any]] = {}
    for suite in document.get("suites", []):
        suite_id = suite.get("id")
        if suite_id not in PAIR_BY_SUITE:
            raise ValueError(f"Unknown fixture suite: {suite_id!r}")
        pair = PAIR_BY_SUITE[suite_id]
        if pair in suites:
            raise ValueError(f"Duplicate fixture suite for {pair}")
        suites[pair] = suite
    return suites


def selected_reference(case: Mapping[str, Any]) -> str:
    checks = case.get("critical_checks", [])
    references = list(case["reference_translations"])
    return max(
        references,
        key=lambda text: sum(score.evaluate_check(text, check)["passed"] for check in checks),
    )


def _is_protected_case(case: Mapping[str, Any]) -> bool:
    tags = {str(tag).casefold() for tag in case.get("tags", [])}
    return case.get("category") == "protected_span" or bool(
        tags & {"protected", "protected_span", "protected-span"}
    )


def validate_failure_contract(document: dict[str, Any]) -> dict[str, Any]:
    _exact_keys(document, {"schema_version", "license_spdx", "description", "cases"}, "failure contract")
    if document["schema_version"] != 2:
        raise ValueError("Unsupported failure-contract schema")
    if document["license_spdx"] != "Apache-2.0":
        raise ValueError("Failure fixtures must declare Apache-2.0")
    _nonempty_string(document["description"], "failure contract description")
    cases = document["cases"]
    if not isinstance(cases, list) or not cases:
        raise ValueError("Failure contract is empty")
    identifiers: set[str] = set()
    classifications: set[str] = set()
    for index, case in enumerate(cases):
        _exact_keys(case, {"id", "stimulus", "expected"}, f"failure case[{index}]")
        identifier = _nonempty_string(case["id"], f"failure case[{index}].id")
        if identifier in identifiers:
            raise ValueError(f"Duplicate failure case id: {identifier}")
        identifiers.add(identifier)
        stimulus = case["stimulus"]
        if not isinstance(stimulus, dict) or stimulus.get("kind") not in {"http", "transport"}:
            raise ValueError(f"Invalid stimulus for {identifier}")
        expected = case["expected"]
        allowed = {
            "classification",
            "retry",
            "maximum_attempts",
            "preserve_previous_translation",
        }
        if expected.get("retry") is True:
            allowed.add("minimum_retry_delay_ms")
        _exact_keys(expected, allowed, f"failure case {identifier}.expected")
        classification = _nonempty_string(
            expected["classification"], f"failure case {identifier}.classification"
        )
        classifications.add(classification)
        if not isinstance(expected["retry"], bool):
            raise ValueError(f"Invalid retry flag for {identifier}")
        attempts = _integer(expected["maximum_attempts"], f"{identifier}.maximum_attempts", minimum=1, maximum=2)
        if expected["retry"] != (attempts == 2):
            raise ValueError(f"Retry/attempt mismatch for {identifier}")
        if expected["retry"]:
            _finite_number(expected["minimum_retry_delay_ms"], f"{identifier}.minimum_retry_delay_ms", minimum=0, maximum=2_000)
        if expected["preserve_previous_translation"] is not True:
            raise ValueError(f"Failure must preserve prior region translation: {identifier}")
    missing = REQUIRED_FAILURE_CLASSES - classifications
    if missing:
        raise ValueError(f"Failure contract misses classifications: {sorted(missing)}")
    serialized = json.dumps(document, ensure_ascii=False).casefold()
    if re.search(r"\bsk-[a-z0-9]{12,}\b", serialized):
        raise ValueError("Failure fixtures contain an API-key-shaped value")
    return {"case_count": len(cases), "classifications": sorted(classifications)}


def validate_calibration(document: dict[str, Any], corpus_release: str) -> dict[str, Any]:
    _exact_keys(
        document,
        {"schema_version", "description", "target_corpus_release", "formal_gate_eligible", "entries"},
        "calibration manifest",
    )
    if document["schema_version"] != 1:
        raise ValueError("Unsupported calibration schema")
    if document["target_corpus_release"] != corpus_release:
        raise ValueError("Calibration manifest targets another corpus release")
    if document["formal_gate_eligible"] is not False:
        raise ValueError("Historical calibration must not claim formal gate eligibility")
    _nonempty_string(document["description"], "calibration description")
    entries = document["entries"]
    if not isinstance(entries, list) or not entries:
        raise ValueError("Calibration manifest is empty")
    identifiers: set[str] = set()
    coverage: set[tuple[str, str]] = set()
    for index, entry in enumerate(entries):
        _exact_keys(
            entry,
            {
                "id",
                "edition",
                "pair",
                "backend",
                "source_document",
                "source_document_sha256",
                "source_result_sha256",
                "measured_corpus_release",
                "case_count",
                "metrics",
                "formal_gate_eligible",
                "exclusion_reason",
            },
            f"calibration entry[{index}]",
        )
        identifier = _nonempty_string(entry["id"], f"calibration entry[{index}].id")
        if identifier in identifiers:
            raise ValueError(f"Duplicate calibration id: {identifier}")
        identifiers.add(identifier)
        edition = entry["edition"]
        pair = entry["pair"]
        if edition not in {"lite", "full", "online"} or pair not in {"en-zh", "ja-zh"}:
            raise ValueError(f"Invalid calibration route: {edition}/{pair}")
        if (edition, pair) in coverage:
            raise ValueError(f"Duplicate calibration route: {edition}/{pair}")
        coverage.add((edition, pair))
        source_document = ROOT / _nonempty_string(entry["source_document"], f"{identifier}.source_document")
        if not source_document.is_file():
            raise ValueError(f"Calibration source document is missing: {source_document}")
        if entry["source_document_sha256"] != sha256_file(source_document):
            raise ValueError(f"Calibration source document hash mismatch: {identifier}")
        if not re.fullmatch(r"[0-9a-f]{64}", str(entry["source_result_sha256"])):
            raise ValueError(f"Invalid source result hash: {identifier}")
        _integer(entry["case_count"], f"{identifier}.case_count", minimum=1, maximum=10_000)
        metrics = _exact_keys(entry["metrics"], {"bleu", "chrf_pp", "critical_check_rate"}, f"{identifier}.metrics")
        _finite_number(metrics["bleu"], f"{identifier}.bleu", minimum=0, maximum=100)
        _finite_number(metrics["chrf_pp"], f"{identifier}.chrf_pp", minimum=0, maximum=100)
        _finite_number(metrics["critical_check_rate"], f"{identifier}.critical_check_rate", minimum=0, maximum=1)
        if entry["formal_gate_eligible"] is not False:
            raise ValueError(f"Historical calibration cannot satisfy the formal gate: {identifier}")
        _nonempty_string(entry["exclusion_reason"], f"{identifier}.exclusion_reason")
    required = {(edition, pair) for edition in ("lite", "full", "online") for pair in ("en-zh", "ja-zh")}
    if coverage != required:
        raise ValueError(f"Calibration route coverage mismatch: {sorted(required - coverage)}")
    return {"entry_count": len(entries), "entry_ids": sorted(identifiers)}


def validate_thresholds(
    document: dict[str, Any],
    corpus_release: str,
    calibration_path: Path = DEFAULT_CALIBRATION,
) -> None:
    _exact_keys(
        document,
        {
            "schema_version",
            "corpus_release",
            "scored_layer",
            "calibration_manifest_sha256",
            "formal_gate_policy",
            "comparison",
            "human_review",
            "editions",
        },
        "regression thresholds",
    )
    if document["schema_version"] != 2:
        raise ValueError("Unsupported regression-threshold schema")
    if document["corpus_release"] != corpus_release:
        raise ValueError("Thresholds are not pinned to the fixture corpus release")
    if document["scored_layer"] != "translation_raw":
        raise ValueError("Formal candidate schema supports only translation_raw")
    _nonempty_string(document["formal_gate_policy"], "formal_gate_policy")
    calibration = load_json(calibration_path)
    validate_calibration(calibration, corpus_release)
    if document["calibration_manifest_sha256"] != sha256_file(calibration_path):
        raise ValueError("Threshold calibration manifest hash mismatch")

    comparison = _exact_keys(
        document["comparison"],
        {"maximum_bleu_drop", "maximum_chrf_pp_drop", "maximum_critical_check_rate_drop"},
        "comparison thresholds",
    )
    _finite_number(comparison["maximum_bleu_drop"], "maximum_bleu_drop", minimum=0, maximum=100)
    _finite_number(comparison["maximum_chrf_pp_drop"], "maximum_chrf_pp_drop", minimum=0, maximum=100)
    _finite_number(
        comparison["maximum_critical_check_rate_drop"],
        "maximum_critical_check_rate_drop",
        minimum=0,
        maximum=1,
    )
    human = _exact_keys(
        document["human_review"],
        {"minimum_raters_per_output", "minimum_case_coverage", "maximum_critical_error_rate"},
        "human-review thresholds",
    )
    _integer(human["minimum_raters_per_output"], "minimum_raters_per_output", minimum=2, maximum=20)
    _finite_number(human["minimum_case_coverage"], "minimum_case_coverage", minimum=0.01, maximum=1)
    _finite_number(human["maximum_critical_error_rate"], "maximum_critical_error_rate", minimum=0, maximum=1)

    editions = document["editions"]
    if not isinstance(editions, dict) or set(editions) != {"lite", "full", "online"}:
        raise ValueError("Thresholds must define exactly Lite, Full, and Online")
    calibration_ids = {entry["id"]: entry for entry in calibration["entries"]}
    for edition, edition_config in editions.items():
        expected_keys = {"en-zh", "ja-zh", "calibration_evidence"}
        if edition == "online":
            expected_keys.add("required_failure_contract")
        _exact_keys(edition_config, expected_keys, f"{edition} thresholds")
        evidence_ids = edition_config["calibration_evidence"]
        if not isinstance(evidence_ids, list) or len(evidence_ids) != 2 or len(set(evidence_ids)) != 2:
            raise ValueError(f"{edition} needs two unique calibration evidence ids")
        evidence_routes = set()
        for evidence_id in evidence_ids:
            entry = calibration_ids.get(evidence_id)
            if entry is None or entry["edition"] != edition:
                raise ValueError(f"Unknown or wrong-edition calibration evidence: {evidence_id}")
            evidence_routes.add(entry["pair"])
        if evidence_routes != {"en-zh", "ja-zh"}:
            raise ValueError(f"{edition} calibration does not cover both pairs")
        if edition == "online" and edition_config["required_failure_contract"] is not True:
            raise ValueError("Online must require production-path failure evidence")
        for pair in ("en-zh", "ja-zh"):
            pair_config = _exact_keys(edition_config[pair], PAIR_THRESHOLD_KEYS, f"{edition}/{pair}")
            _finite_number(pair_config["minimum_bleu"], f"{edition}/{pair}/minimum_bleu", minimum=0, maximum=100)
            _finite_number(pair_config["minimum_chrf_pp"], f"{edition}/{pair}/minimum_chrf_pp", minimum=0, maximum=100)
            _finite_number(
                pair_config["minimum_critical_check_rate"],
                f"{edition}/{pair}/minimum_critical_check_rate",
                minimum=0,
                maximum=1,
            )
            _finite_number(pair_config["minimum_mean_adequacy"], f"{edition}/{pair}/minimum_mean_adequacy", minimum=1, maximum=5)
            _finite_number(pair_config["minimum_mean_fluency"], f"{edition}/{pair}/minimum_mean_fluency", minimum=1, maximum=5)


def validate_fixtures(
    fixtures_path: Path = DEFAULT_FIXTURES,
    pin_path: Path = DEFAULT_PIN,
    failures_path: Path = DEFAULT_FAILURES,
    thresholds_path: Path = DEFAULT_THRESHOLDS,
    calibration_path: Path = DEFAULT_CALIBRATION,
) -> dict[str, Any]:
    document = load_json(fixtures_path)
    if document.get("schema_version") != 2:
        raise ValueError("Unsupported translation fixture schema")
    corpus_release = str(document.get("corpus_release", ""))
    if not re.fullmatch(r"\d{4}\.\d{2}-[a-z0-9-]+", corpus_release):
        raise ValueError("Missing or malformed corpus_release")
    license_info = document.get("license", {})
    if license_info.get("default_spdx") != "Apache-2.0":
        raise ValueError("Project-authored fixture license must be Apache-2.0")
    notice = fixtures_path.with_name(str(license_info.get("notice_file", "")))
    if not notice.is_file():
        raise ValueError(f"Missing fixture rights notice: {notice}")

    pinned_hash, pinned_name = read_pinned_hash(pin_path)
    actual_hash = sha256_file(fixtures_path)
    if pinned_name != fixtures_path.name or pinned_hash != actual_hash:
        raise ValueError(f"Fixture SHA-256 pin mismatch: expected {pinned_hash}, got {actual_hash}")

    registry = document.get("provenance_registry", [])
    if not isinstance(registry, list):
        raise ValueError("Provenance registry must be a list")
    provenance = {entry.get("id"): entry for entry in registry}
    if len(provenance) != len(registry):
        raise ValueError("Duplicate provenance registry id")
    if "project-authored-apache-2.0" not in provenance:
        raise ValueError("Missing project-authored provenance entry")
    for entry in registry:
        if not entry.get("source_uri") or not entry.get("license_spdx"):
            raise ValueError(f"Incomplete provenance registry entry: {entry.get('id')}")

    suites = suite_map(document)
    if set(suites) != {"en-zh", "ja-zh"}:
        raise ValueError("Fixture corpus must contain exactly en-zh and ja-zh suites")
    all_ids: set[str] = set()
    suite_stats: dict[str, Any] = {}
    all_sources: set[str] = set()
    for pair, suite in suites.items():
        if suite.get("corpus_release") != corpus_release:
            raise ValueError(f"Suite {suite.get('id')} has a stale corpus release")
        source_language, target_language = pair.split("-", 1)
        if suite.get("source_language") != source_language or suite.get("target_language") != target_language:
            raise ValueError(f"Suite {suite.get('id')} language metadata is inconsistent")
        required_review_domains = set(suite.get("required_review_domains", []))
        if required_review_domains != REQUIRED_REVIEW_DOMAINS | {"failure_contract"}:
            raise ValueError(f"Suite {suite.get('id')} review-domain contract is incomplete")
        cases = suite.get("cases", [])
        if not isinstance(cases, list) or len(cases) < 48:
            raise ValueError(f"Suite {suite.get('id')} is smaller than the public floor")
        categories = {str(case.get("category", "")) for case in cases}
        missing_domains = REQUIRED_DOMAINS - categories
        if missing_domains:
            raise ValueError(f"{pair} misses domains: {sorted(missing_domains)}")
        check_count = public_domain_count = 0
        for case in cases:
            identifier = str(case.get("id", ""))
            if not identifier or identifier in all_ids:
                raise ValueError(f"Duplicate or empty fixture id: {identifier!r}")
            all_ids.add(identifier)
            provenance_id = case.get("provenance_id")
            if provenance_id not in provenance:
                raise ValueError(f"Unknown provenance for {identifier}: {provenance_id}")
            provenance_kind = provenance[provenance_id]["kind"]
            if case.get("provenance") != provenance_kind:
                raise ValueError(f"Provenance kind mismatch for {identifier}")
            public_domain_count += int(provenance_kind == "public_domain")
            if case.get("reference_license_spdx") != "Apache-2.0":
                raise ValueError(f"Reference license missing for {identifier}")
            source_text = str(case.get("source_text", ""))
            if not source_text.strip() or "\ufffd" in source_text or "??" in source_text:
                raise ValueError(f"Empty or corrupted source text: {identifier}")
            normalized_source = re.sub(r"\s+", " ", source_text).strip().casefold()
            if normalized_source in all_sources:
                raise ValueError(f"Duplicate source text: {identifier}")
            all_sources.add(normalized_source)
            if pair == "ja-zh" and re.search(r"[\u3040-\u30ff\u3400-\u9fff]", source_text) is None:
                raise ValueError(f"Japanese fixture has no Japanese script: {identifier}")
            references = case.get("reference_translations", [])
            if not isinstance(references, list) or not references or any(not str(reference).strip() for reference in references):
                raise ValueError(f"Missing reference translation: {identifier}")
            if len(set(map(str, references))) != len(references):
                raise ValueError(f"Duplicate reference translation: {identifier}")
            if any(
                "\ufffd" in str(reference)
                or "??" in str(reference)
                or re.search(r"[\u3400-\u9fff]", str(reference)) is None
                for reference in references
            ):
                raise ValueError(f"Reference appears encoding-corrupted: {identifier}")
            for reference in map(str, references):
                if sha256_text(reference) in RETIRED_REFERENCE_SHA256:
                    raise ValueError(f"Retired reference replay detected: {identifier}")
                compact_reference = re.sub(r"\s+", "", reference)
                retired_ngram_hits = {
                    sha256_text(compact_reference[index : index + 12])
                    for index in range(max(0, len(compact_reference) - 11))
                } & RETIRED_REFERENCE_NGRAM_SHA256
                if len(retired_ngram_hits) >= 2:
                    raise ValueError(
                        f"Reference retains {len(retired_ngram_hits)} retired 12-character "
                        f"fingerprints for {identifier}"
                    )
            checks = case.get("critical_checks", [])
            check_count += len(checks)
            for check in checks:
                if not check.get("name"):
                    raise ValueError(f"Unnamed critical check: {identifier}")
                for key in ("all_regex", "any_regex", "forbid_regex"):
                    for pattern in check.get(key, []):
                        re.compile(pattern)
                if not any(score.evaluate_check(reference, check)["passed"] for reference in references):
                    raise ValueError(f"No reference satisfies critical check {check.get('name')!r} for {identifier}")
        if check_count < 60:
            raise ValueError(f"{pair} has too few semantic checks: {check_count}")
        suite_stats[pair] = {
            "suite_id": suite["id"],
            "case_count": len(cases),
            "category_count": len(categories),
            "categories": sorted(categories),
            "critical_check_count": check_count,
            "public_domain_source_cases": public_domain_count,
            "protected_gate_cases": sum(_is_protected_case(case) for case in cases),
        }

    failure_stats = validate_failure_contract(load_json(failures_path))
    validate_thresholds(load_json(thresholds_path), corpus_release, calibration_path)
    calibration_stats = validate_calibration(load_json(calibration_path), corpus_release)
    return {
        "schema_version": 2,
        "corpus_release": corpus_release,
        "fixture_sha256": actual_hash,
        "fixture_license": license_info["default_spdx"],
        "suite_stats": suite_stats,
        "failure_contract": failure_stats,
        "calibration": calibration_stats,
        "threshold_editions": ["lite", "full", "online"],
    }


def _smoke_inference(engine_id: str) -> dict[str, Any]:
    return {
        "producer": "translation-regression deterministic harness",
        "engine_id": engine_id,
        "model_id": "project-reference playback",
        "model_revision": "not-model-evidence",
        "runtime_id": "python-harness",
        "runtime_revision": "not-runtime-evidence",
        "device_kind": "synthetic",
        "device_model": "fixture-replay",
        "os_version": "deterministic",
        "architecture": "none",
        "started_at_utc": "2000-01-01T00:00:00Z",
        "completed_at_utc": "2000-01-01T00:00:01Z",
        "repetitions": 1,
        "latency_clock": "synthetic",
        "network_path": "offline",
    }


def make_reference_replay(
    pair: str,
    engine_id: str,
    fixtures_path: Path = DEFAULT_FIXTURES,
) -> dict[str, Any]:
    """Create a minimal synthetic smoke candidate; formal validation rejects it."""
    document = load_json(fixtures_path)
    suite = suite_map(document)[pair]
    cases = []
    for index, fixture in enumerate(suite["cases"], start=1):
        latency = round(1.0 + index / 100.0, 3)
        cases.append(
            {
                "case_id": fixture["id"],
                "source_sha256": sha256_text(fixture["source_text"]),
                "candidate": {
                    "output_text": selected_reference(fixture),
                    "latencies_ms": [latency],
                    "median_latency_ms": latency,
                },
            }
        )
    source, target = pair.split("-", 1)
    return {
        "schema_version": 2,
        "evidence_kind": SMOKE_EVIDENCE_KIND,
        "corpus_release": document["corpus_release"],
        "fixture_sha256": sha256_file(fixtures_path),
        "suite_id": suite["id"],
        "source_language": source,
        "target_language": target,
        "inference": _smoke_inference(engine_id),
        "cases": cases,
    }


def formal_raw_inference_record_sha256(result: dict[str, Any]) -> str:
    """Hash the strict runner-owned portion of a formal candidate record."""
    cases = result["cases"]
    canonical_cases = (
        sorted(cases, key=lambda case: str(case.get("case_id", "")))
        if isinstance(cases, list) and all(isinstance(case, dict) for case in cases)
        else cases
    )
    return canonical_json_sha256(
        {
            "schema_version": result["schema_version"],
            "corpus_release": result["corpus_release"],
            "fixture_sha256": result["fixture_sha256"],
            "suite_id": result["suite_id"],
            "source_language": result["source_language"],
            "target_language": result["target_language"],
            "inference": result["inference"],
            "cases": canonical_cases,
        }
    )


def _validate_formal_provenance(result: dict[str, Any]) -> None:
    provenance = _exact_keys(result["provenance"], PROVENANCE_KEYS, "candidate provenance")
    if provenance["schema_version"] != 1:
        raise ValueError("Unsupported candidate provenance schema")
    if provenance["producer_id"] != FORMAL_PRODUCER_ID:
        raise ValueError("Formal candidate must name the project benchmark runner")
    expected_source_hash = sha256_file(DEFAULT_CANDIDATE_RUNNER_SOURCE)
    if provenance["producer_source_sha256"] != expected_source_hash:
        raise ValueError("Formal candidate benchmark-runner source hash mismatch")
    expected_record_hash = formal_raw_inference_record_sha256(result)
    if provenance["raw_inference_record_sha256"] != expected_record_hash:
        raise ValueError("Formal candidate raw inference record hash mismatch")


def _is_default_ignorable(character: str) -> bool:
    codepoint = ord(character)
    # Cc/Cf is an intentionally conservative replay-defense superset.  The
    # fixed table below supplies Default_Ignorable Cn/Mn gaps and prevents the
    # result from drifting with Python's bundled Unicode database.
    if unicodedata.category(character) in {"Cc", "Cf"}:
        return True
    return any(
        start <= codepoint <= end
        for start, end in DEFAULT_IGNORABLE_RANGES
    )


def replay_fingerprint(value: str) -> str:
    """Collapse obvious invisible/format-only mutations for replay detection."""
    normalized = unicodedata.normalize("NFKC", value)
    return "".join(
        character
        for character in normalized
        if not character.isspace()
        and not unicodedata.category(character).startswith("P")
        and not _is_default_ignorable(character)
    ).casefold()


def _validate_inference_metadata(inference: Any, *, formal: bool) -> dict[str, Any]:
    inference = _exact_keys(inference, INFERENCE_KEYS, "candidate inference")
    for key in INFERENCE_KEYS - {"repetitions"}:
        _nonempty_string(inference[key], f"candidate inference.{key}")
    repetitions = _integer(inference["repetitions"], "candidate inference.repetitions", minimum=1, maximum=100)
    started = _utc_timestamp(inference["started_at_utc"], "candidate inference.started_at_utc")
    completed = _utc_timestamp(inference["completed_at_utc"], "candidate inference.completed_at_utc")
    if completed < started:
        raise ValueError("Candidate inference completed before it started")
    if inference["network_path"] not in {"offline", "provider_https"}:
        raise ValueError("candidate inference.network_path must be offline or provider_https")
    if formal:
        if inference["producer"] != FORMAL_PRODUCER_ID:
            raise ValueError("Formal candidate producer must be the project benchmark runner")
        for key in ("model_revision", "runtime_revision"):
            if not re.fullmatch(r"[0-9a-f]{7,64}", inference[key]):
                raise ValueError(f"Formal candidate inference.{key} must be a pinned lowercase revision hash")
        if inference["device_kind"] != "physical-android-device":
            raise ValueError("Formal candidate must come from the physical Android benchmark runner")
        if inference["latency_clock"] != "elapsed-realtime-monotonic":
            raise ValueError("Formal candidate latency clock must be elapsed-realtime-monotonic")
        metadata = " ".join(str(inference[key]) for key in INFERENCE_KEYS if key != "repetitions").casefold()
        marker = next((item for item in BANNED_FORMAL_MARKERS if item in metadata), None)
        if marker:
            raise ValueError(f"Formal candidate contains non-inference marker: {marker}")
    return {"repetitions": repetitions}


def validate_candidate_against_suite(
    result: dict[str, Any],
    pair: str,
    suite: dict[str, Any],
    corpus_release: str,
    fixture_hash: str,
    *,
    formal: bool = True,
) -> tuple[list[dict[str, Any]], str]:
    expected_candidate_keys = CANDIDATE_KEYS | ({"provenance"} if formal else set())
    if not isinstance(result, dict):
        raise ValueError(f"{pair} candidate must be an object")
    expected_kind = FORMAL_EVIDENCE_KIND if formal else SMOKE_EVIDENCE_KIND
    if result.get("evidence_kind") != expected_kind:
        raise ValueError(
            f"{pair} candidate evidence_kind must be {expected_kind}; "
            "reference/fixture/replay/synthetic evidence is not release evidence"
        )
    _exact_keys(result, expected_candidate_keys, f"{pair} candidate")
    if result["schema_version"] != 2:
        raise ValueError(f"{pair} candidate has unsupported schema")
    if result["corpus_release"] != corpus_release or result["fixture_sha256"] != fixture_hash:
        raise ValueError(f"{pair} candidate corpus release or fixture_sha256 mismatch")
    source, target = pair.split("-", 1)
    expected_metadata = {
        "suite_id": suite["id"],
        "source_language": source,
        "target_language": target,
    }
    for key, expected in expected_metadata.items():
        if result[key] != expected:
            raise ValueError(f"{pair} candidate {key} mismatch")
    inference = _validate_inference_metadata(result["inference"], formal=formal)

    raw_cases = result["cases"]
    if not isinstance(raw_cases, list):
        raise ValueError(f"{pair} candidate cases must be a list")
    by_id: dict[str, dict[str, Any]] = {}
    for index, candidate_case in enumerate(raw_cases):
        _exact_keys(candidate_case, CASE_KEYS, f"{pair} candidate case[{index}]")
        case_id = _nonempty_string(candidate_case["case_id"], f"{pair} case[{index}].case_id")
        if case_id in by_id:
            raise ValueError(f"{pair} candidate has duplicate case id: {case_id}")
        output = _exact_keys(candidate_case["candidate"], OUTPUT_KEYS, f"{pair}/{case_id}.candidate")
        output_text = _output_string(output["output_text"], f"{pair}/{case_id}.output_text")
        latencies = output["latencies_ms"]
        if not isinstance(latencies, list) or len(latencies) != inference["repetitions"]:
            raise ValueError(f"{pair}/{case_id} latency coverage does not match repetitions")
        latency_values = [
            _finite_number(value, f"{pair}/{case_id}.latencies_ms", minimum=0, maximum=3_600_000)
            for value in latencies
        ]
        median = _finite_number(
            output["median_latency_ms"], f"{pair}/{case_id}.median_latency_ms", minimum=0, maximum=3_600_000
        )
        if not math.isclose(median, statistics.median(latency_values), rel_tol=0, abs_tol=0.001):
            raise ValueError(f"{pair}/{case_id} median latency does not match samples")
        by_id[case_id] = {
            "case_id": case_id,
            "source_sha256": candidate_case["source_sha256"],
            "candidate": {
                "output_text": output_text,
                "latencies_ms": latency_values,
                "median_latency_ms": median,
            },
        }

    canonical_ids = [case["id"] for case in suite["cases"]]
    if set(by_id) != set(canonical_ids) or len(by_id) != len(canonical_ids):
        raise ValueError(
            f"{pair} candidate coverage mismatch; missing={sorted(set(canonical_ids) - set(by_id))}, "
            f"extra={sorted(set(by_id) - set(canonical_ids))}"
        )
    materialized: list[dict[str, Any]] = []
    exact_reference_count = 0
    source_passthrough_count = 0
    normalized_reference_replay_count = 0
    normalized_source_passthrough_count = 0
    for fixture in suite["cases"]:
        candidate_case = by_id[fixture["id"]]
        expected_source_hash = sha256_text(fixture["source_text"])
        if candidate_case["source_sha256"] != expected_source_hash:
            raise ValueError(f"{pair}/{fixture['id']} source_sha256 mismatch")
        output_text = candidate_case["candidate"]["output_text"]
        exact_reference_count += int(output_text in fixture["reference_translations"])
        source_passthrough_count += int(output_text.strip() == fixture["source_text"].strip())
        output_fingerprint = replay_fingerprint(output_text)
        normalized_reference_replay_count += int(
            any(
                output_fingerprint == replay_fingerprint(reference)
                for reference in fixture["reference_translations"]
            )
        )
        normalized_source_passthrough_count += int(
            output_fingerprint == replay_fingerprint(fixture["source_text"])
        )
        joined = copy.deepcopy(fixture)
        joined["translation_raw"] = copy.deepcopy(candidate_case["candidate"])
        materialized.append(joined)
    if formal and exact_reference_count == len(materialized):
        raise ValueError("Formal candidate is a complete canonical reference replay")
    if formal and source_passthrough_count == len(materialized):
        raise ValueError("Formal candidate is a complete source fixture passthrough")
    if formal and normalized_reference_replay_count == len(materialized):
        raise ValueError(
            "Formal candidate is a complete canonical reference replay after Unicode normalization"
        )
    if formal and normalized_source_passthrough_count == len(materialized):
        raise ValueError(
            "Formal candidate is a complete source fixture passthrough after Unicode normalization"
        )
    normalized_replay_limit = math.ceil(
        len(materialized) * MAX_NORMALIZED_REPLAY_FRACTION
    )
    if formal and normalized_reference_replay_count >= normalized_replay_limit:
        raise ValueError(
            "Formal candidate is a near-complete canonical reference replay after Unicode "
            f"normalization: {normalized_reference_replay_count}/{len(materialized)}"
        )
    if formal and normalized_source_passthrough_count >= normalized_replay_limit:
        raise ValueError(
            "Formal candidate is a near-complete source fixture passthrough after Unicode "
            f"normalization: {normalized_source_passthrough_count}/{len(materialized)}"
        )
    if formal:
        _validate_formal_provenance(result)
    return materialized, canonical_json_sha256(result)


# Compatibility name retained for callers; it now enforces the strict schema.
validate_result_against_suite = validate_candidate_against_suite


def automatic_metrics(cases: list[dict[str, Any]], layer: str = "translation_raw") -> dict[str, Any]:
    summary = score.score_translation_layer(cases, layer)
    total = summary["critical_checks_total"]
    critical_rate = summary["critical_checks_passed"] / total if total else 1.0
    protected = [case for case in summary["cases"] if _is_protected_case(case)]
    if not protected:
        raise ValueError("Canonical suite contains no category/tag protected cases")
    protected_passed = all(
        check["passed"] for case in protected for check in case["critical_checks"]
    )
    return {
        "bleu": summary["corpus_bleu"],
        "chrf_pp": summary["corpus_chrf_pp"],
        "critical_checks_passed": summary["critical_checks_passed"],
        "critical_checks_total": total,
        "critical_check_rate": round(critical_rate, 6),
        "protected_span_checks_passed": protected_passed,
        "protected_case_count": len(protected),
        "case_count": len(summary["cases"]),
    }


def _check(name: str, actual: float | bool, relation: str, expected: float | bool) -> dict[str, Any]:
    if isinstance(actual, bool):
        if relation != "==" or not isinstance(expected, bool):
            raise ValueError(f"Boolean gate {name} must use ==")
    else:
        _finite_number(actual, f"gate actual: {name}")
        _finite_number(expected, f"gate expected: {name}")
    if relation == ">=":
        passed = float(actual) >= float(expected)
    elif relation == "<=":
        passed = float(actual) <= float(expected)
    elif relation == "==":
        passed = actual == expected
    else:
        raise ValueError(f"Unknown relation: {relation}")
    return {"name": name, "actual": actual, "relation": relation, "expected": expected, "passed": passed}


def evaluate_pair_gate(
    candidate_cases: list[dict[str, Any]],
    baseline_cases: list[dict[str, Any]],
    pair_thresholds: dict[str, Any],
    comparison: dict[str, Any],
) -> dict[str, Any]:
    candidate_metrics = automatic_metrics(candidate_cases)
    baseline_metrics = automatic_metrics(baseline_cases)
    checks = [
        _check("minimum BLEU", candidate_metrics["bleu"], ">=", pair_thresholds["minimum_bleu"]),
        _check("minimum chrF++", candidate_metrics["chrf_pp"], ">=", pair_thresholds["minimum_chrf_pp"]),
        _check(
            "minimum critical-check rate",
            candidate_metrics["critical_check_rate"],
            ">=",
            pair_thresholds["minimum_critical_check_rate"],
        ),
        _check("all category/tag protected checks", candidate_metrics["protected_span_checks_passed"], "==", True),
        _check(
            "BLEU regression versus incumbent",
            baseline_metrics["bleu"] - candidate_metrics["bleu"],
            "<=",
            comparison["maximum_bleu_drop"],
        ),
        _check(
            "chrF++ regression versus incumbent",
            baseline_metrics["chrf_pp"] - candidate_metrics["chrf_pp"],
            "<=",
            comparison["maximum_chrf_pp_drop"],
        ),
        _check(
            "critical-check regression versus incumbent",
            baseline_metrics["critical_check_rate"] - candidate_metrics["critical_check_rate"],
            "<=",
            comparison["maximum_critical_check_rate_drop"],
        ),
    ]
    return {
        "passed": all(check["passed"] for check in checks),
        "candidate": candidate_metrics,
        "baseline": baseline_metrics,
        "checks": checks,
    }


def verify_failure_evidence(
    evidence: dict[str, Any],
    contract: dict[str, Any],
    failures_path: Path = DEFAULT_FAILURES,
    producer_source: Path = DEFAULT_ONLINE_EVIDENCE_SOURCE,
) -> dict[str, Any]:
    _exact_keys(
        evidence,
        {"schema_version", "evidence_kind", "contract_sha256", "producer", "producer_source_sha256", "cases"},
        "online failure evidence",
    )
    if evidence["schema_version"] != 2 or evidence["evidence_kind"] != ONLINE_EVIDENCE_KIND:
        raise ValueError("Online formal gate rejects fixture/replay/synthetic failure evidence")
    if evidence["contract_sha256"] != sha256_file(failures_path):
        raise ValueError("Online failure evidence targets another contract hash")
    if evidence["producer"] != "OnlineFailureContractExecutionTest":
        raise ValueError("Online failure evidence producer is not the production-path JVM test")
    if not producer_source.is_file() or evidence["producer_source_sha256"] != sha256_file(producer_source):
        raise ValueError("Online failure evidence producer source hash mismatch")
    expected = {case["id"]: case["expected"] for case in contract["cases"]}
    actual: dict[str, dict[str, Any]] = {}
    cases = evidence["cases"]
    if not isinstance(cases, list):
        raise ValueError("Online failure evidence cases must be a list")
    for index, item in enumerate(cases):
        _exact_keys(item, {"case_id", "actual"}, f"online evidence case[{index}]")
        case_id = _nonempty_string(item["case_id"], f"online evidence case[{index}].case_id")
        if case_id in actual:
            raise ValueError(f"Duplicate online evidence case: {case_id}")
        if case_id not in expected:
            raise ValueError(f"Unknown online evidence case: {case_id}")
        expected_keys = set(expected[case_id])
        value = _exact_keys(item["actual"], expected_keys, f"online evidence {case_id}.actual")
        actual[case_id] = value
    if set(actual) != set(expected):
        raise ValueError(f"Online failure evidence coverage mismatch: {sorted(set(expected) - set(actual))}")
    mismatches: list[str] = []
    for case_id, expected_value in expected.items():
        for field, wanted in expected_value.items():
            got = actual[case_id][field]
            if got != wanted:
                mismatches.append(f"{case_id}.{field}: expected={wanted!r}, actual={got!r}")
    if mismatches:
        raise ValueError("Online failure evidence mismatch: " + "; ".join(mismatches))
    return {"passed": True, "case_count": len(expected), "producer_source_sha256": evidence["producer_source_sha256"]}


def make_failure_replay(failures_path: Path = DEFAULT_FAILURES) -> dict[str, Any]:
    """Return an explicitly synthetic smoke artifact, never formal evidence."""
    contract = load_json(failures_path)
    return {
        "schema_version": 2,
        "evidence_kind": "synthetic_failure_harness_smoke",
        "contract_sha256": sha256_file(failures_path),
        "cases": [{"case_id": case["id"], "observed_contract_shape": sorted(case["expected"])} for case in contract["cases"]],
    }


def verify_failure_replay(*_: Any, **__: Any) -> dict[str, Any]:
    raise ValueError("Copied expected-to-actual failure replay is not accepted evidence")


def _human_metric(summary: dict[str, Any], system_id: str, pair: str) -> dict[str, Any]:
    try:
        metric = summary["systems"][system_id][pair]
    except (KeyError, TypeError) as error:
        raise ValueError(f"Human summary misses {system_id}/{pair}") from error
    return _exact_keys(
        metric,
        {
            "system_evidence_sha256",
            "mean_adequacy",
            "mean_fluency",
            "critical_error_rate",
            "case_coverage",
            "minimum_ratings_per_output",
            "rating_count",
        },
        f"human summary {system_id}/{pair}",
    )


def _validate_human_summary_top(summary: dict[str, Any], corpus_release: str, fixture_hash: str) -> None:
    _exact_keys(
        summary,
        {"schema_version", "bundle_id", "sheet_sha256", "corpus_release", "fixture_sha256", "rater_count", "systems"},
        "human summary",
    )
    if summary["schema_version"] != 2:
        raise ValueError("Unsupported human summary schema")
    if summary["corpus_release"] != corpus_release or summary["fixture_sha256"] != fixture_hash:
        raise ValueError("Human summary targets another corpus")
    _integer(summary["rater_count"], "human summary.rater_count", minimum=1, maximum=100)
    if not isinstance(summary["systems"], dict):
        raise ValueError("Human summary systems must be an object")


def run_gate(
    edition: str,
    candidates: dict[str, dict[str, Any]],
    baselines: dict[str, dict[str, Any]],
    *,
    fixtures_path: Path = DEFAULT_FIXTURES,
    thresholds_path: Path = DEFAULT_THRESHOLDS,
    calibration_path: Path = DEFAULT_CALIBRATION,
    failures_path: Path = DEFAULT_FAILURES,
    failure_replay: dict[str, Any] | None = None,
    blind_sheet_path: Path | None = None,
    blind_key_path: Path | None = None,
    rating_paths: list[Path] | None = None,
    rubric_path: Path | None = None,
    candidate_system: str | None = None,
    baseline_system: str | None = None,
    automated_only_smoke: bool = False,
) -> dict[str, Any]:
    if automated_only_smoke:
        raise ValueError("Formal gate never accepts automated-only smoke; use the smoke command")
    if failure_replay is not None:
        raise ValueError("Formal gate rejects failure replay; provide Kotlin production-path evidence")
    fixtures = load_json(fixtures_path)
    thresholds = load_json(thresholds_path)
    validate_thresholds(thresholds, fixtures["corpus_release"], calibration_path)
    if edition not in thresholds["editions"]:
        raise ValueError(f"Unknown edition: {edition}")
    if set(candidates) != {"en-zh", "ja-zh"} or set(baselines) != {"en-zh", "ja-zh"}:
        raise ValueError("Gate requires en-zh and ja-zh candidate and baseline evidence")
    suites = suite_map(fixtures)
    fixture_hash = sha256_file(fixtures_path)
    pair_reports: dict[str, Any] = {}
    candidate_hashes: dict[str, str] = {}
    baseline_hashes: dict[str, str] = {}
    candidate_cases_by_pair: dict[str, list[dict[str, Any]]] = {}
    baseline_cases_by_pair: dict[str, list[dict[str, Any]]] = {}
    for pair in ("en-zh", "ja-zh"):
        candidate_cases, candidate_hash = validate_candidate_against_suite(
            candidates[pair], pair, suites[pair], fixtures["corpus_release"], fixture_hash, formal=True
        )
        baseline_cases, baseline_hash = validate_candidate_against_suite(
            baselines[pair], pair, suites[pair], fixtures["corpus_release"], fixture_hash, formal=True
        )
        candidate_hashes[pair] = candidate_hash
        baseline_hashes[pair] = baseline_hash
        candidate_cases_by_pair[pair] = candidate_cases
        baseline_cases_by_pair[pair] = baseline_cases
        pair_reports[pair] = evaluate_pair_gate(
            candidate_cases,
            baseline_cases,
            thresholds["editions"][edition][pair],
            thresholds["comparison"],
        )

    failure_report: dict[str, Any] = {"required": edition == "online"}
    if edition == "online":
        if failures_path.resolve() != DEFAULT_FAILURES.resolve():
            raise ValueError("Formal Online gate requires the canonical failure contract")
    else:
        failure_report["passed"] = True

    if (
        blind_sheet_path is None
        or blind_key_path is None
        or rating_paths is None
        or rubric_path is None
        or not candidate_system
        or not baseline_system
    ):
        raise ValueError(
            "Release gate requires a blind sheet, repository-external identity key, "
            "raw rating documents, rubric, candidate system id, and baseline system id"
        )
    if candidate_system == baseline_system:
        raise ValueError("Candidate and baseline blind system ids must be distinct")
    human_config = thresholds["human_review"]
    minimum_raters = human_config["minimum_raters_per_output"]
    if len(rating_paths) < minimum_raters:
        raise ValueError(
            f"Release gate requires at least {minimum_raters} raw rating documents; "
            f"received {len(rating_paths)}"
        )
    resolved_rating_paths = [path.expanduser().resolve() for path in rating_paths]
    if len(set(resolved_rating_paths)) != len(resolved_rating_paths):
        raise ValueError("Release gate requires distinct raw rating document paths")

    sheet = load_json(blind_sheet_path.expanduser().resolve())
    external_key_path = ensure_external_blind_key_path(blind_key_path)
    key = load_json(external_key_path)
    rating_documents = [load_json(path) for path in resolved_rating_paths]
    rubric = load_json(rubric_path.expanduser().resolve())
    validate_human_rubric(rubric)
    if canonical_json_sha256(rubric) != canonical_json_sha256(load_json(DEFAULT_RUBRIC)):
        raise ValueError("Formal gate requires the canonical repository human-rating rubric")
    _validate_blind_system_bindings(
        sheet,
        key,
        {
            candidate_system: candidate_cases_by_pair,
            baseline_system: baseline_cases_by_pair,
        },
        {
            candidate_system: candidate_hashes,
            baseline_system: baseline_hashes,
        },
        fixtures_path,
    )
    human_summary = score_human_ratings(
        sheet,
        key,
        rating_documents,
        rubric,
        fixtures_path,
    )
    _validate_human_summary_top(human_summary, fixtures["corpus_release"], fixture_hash)
    if human_summary["rater_count"] < minimum_raters:
        raise ValueError(
            f"Release gate requires at least {minimum_raters} unique raters; "
            f"received {human_summary['rater_count']}"
        )
    human_checks: list[dict[str, Any]] = []
    for pair in ("en-zh", "ja-zh"):
        metrics = _human_metric(human_summary, candidate_system, pair)
        if metrics["system_evidence_sha256"] != candidate_hashes[pair]:
            raise ValueError(f"Human scores are not bound to candidate evidence for {pair}")
        _finite_number(metrics["mean_adequacy"], f"{pair}.mean_adequacy", minimum=1, maximum=5)
        _finite_number(metrics["mean_fluency"], f"{pair}.mean_fluency", minimum=1, maximum=5)
        _finite_number(metrics["critical_error_rate"], f"{pair}.critical_error_rate", minimum=0, maximum=1)
        _finite_number(metrics["case_coverage"], f"{pair}.case_coverage", minimum=0, maximum=1)
        _integer(metrics["minimum_ratings_per_output"], f"{pair}.minimum_ratings_per_output", minimum=1, maximum=100)
        _integer(metrics["rating_count"], f"{pair}.rating_count", minimum=1, maximum=1_000_000)
        pair_config = thresholds["editions"][edition][pair]
        human_checks.extend(
            [
                _check(f"{pair} minimum adequacy", metrics["mean_adequacy"], ">=", pair_config["minimum_mean_adequacy"]),
                _check(f"{pair} minimum fluency", metrics["mean_fluency"], ">=", pair_config["minimum_mean_fluency"]),
                _check(f"{pair} critical human error rate", metrics["critical_error_rate"], "<=", human_config["maximum_critical_error_rate"]),
                _check(f"{pair} raters per output", metrics["minimum_ratings_per_output"], ">=", human_config["minimum_raters_per_output"]),
                _check(f"{pair} case coverage", metrics["case_coverage"], ">=", human_config["minimum_case_coverage"]),
            ]
        )
    human_score_checks_passed = all(check["passed"] for check in human_checks)
    reviewer_authenticity = {
        "passed": False,
        "status": "pseudonymous_ids_only_not_authenticated_people",
        "reason": (
            "Distinct rater_id strings and complete documents do not prove distinct "
            "human reviewers. Trusted reviewer signatures or protected approvals are required."
        ),
    }
    human_report = {
        "passed": False,
        "score_checks_passed": human_score_checks_passed,
        "status": "recomputed_from_structurally_valid_unauthenticated_raw_ratings",
        "reviewer_authenticity": reviewer_authenticity,
        "checks": human_checks,
        "evidence": {
            "bundle_id": human_summary["bundle_id"],
            "sheet_sha256": human_summary["sheet_sha256"],
            "blind_key_canonical_sha256": canonical_json_sha256(key),
            "rubric_canonical_sha256": canonical_json_sha256(rubric),
            "raw_rating_document_sha256": [
                canonical_json_sha256(document) for document in rating_documents
            ],
            "raw_rating_document_count": len(rating_documents),
            "unique_rater_count": human_summary["rater_count"],
        },
    }
    runner_provenance = {
        "passed": False,
        "status": "unattested_structural_runner_records",
        "producer_id": FORMAL_PRODUCER_ID,
        "producer_source_sha256": sha256_file(DEFAULT_CANDIDATE_RUNNER_SOURCE),
        "reason": (
            "Candidate JSON and its public hashes are structurally self-consistent but "
            "are not a fresh gate-owned runner response or a verified external attestation."
        ),
    }
    baseline_admission = {
        "passed": False,
        "status": "unadmitted_caller_supplied_incumbent_records",
        "evidence_sha256": baseline_hashes,
        "reason": (
            "The supplied baseline is structurally valid but is not bound to a canonical "
            "edition incumbent manifest and repository pin."
        ),
    }
    if edition == "online":
        fresh_online = online_evidence.run_fresh_online_failure_evidence()
        verification = fresh_online["verification"]
        if (
            verification.get("passed") is not True
            or verification.get("fresh") is not True
            or verification.get("status") != "fresh_kotlin_policy_execution"
            or verification.get("trust_boundary")
            != "fresh_hash_pinned_current_checkout_not_attestation"
            or re.fullmatch(
                r"[0-9a-f]{64}",
                str(verification.get("execution_chain_sha256", "")),
            )
            is None
        ):
            raise ValueError("Online production-path evidence was not freshly verified")
        failure_report.update(verification)
        failure_report["evidence_canonical_sha256"] = canonical_json_sha256(
            fresh_online["evidence"]
        )
    metric_checks_passed = all(report["passed"] for report in pair_reports.values()) and bool(failure_report["passed"])
    automated_passed = metric_checks_passed and bool(baseline_admission["passed"])
    release_ready_blockers = []
    if not metric_checks_passed:
        release_ready_blockers.append("automated_quality_or_online_policy_gate_failed")
    if not baseline_admission["passed"]:
        release_ready_blockers.append("canonical_incumbent_admission_required")
    if not human_report["score_checks_passed"]:
        release_ready_blockers.append("blind_human_review_gate_failed")
    if not human_report["reviewer_authenticity"]["passed"]:
        release_ready_blockers.append("authenticated_independent_human_reviewers_required")
    if not runner_provenance["passed"]:
        release_ready_blockers.append("fresh_or_attested_candidate_runner_provenance_required")
    return {
        "schema_version": 2,
        "report_kind": "translation_quality_release_gate",
        "edition": edition,
        "corpus_release": fixtures["corpus_release"],
        "fixture_sha256": fixture_hash,
        "candidate_evidence_sha256": candidate_hashes,
        "metric_checks_passed": metric_checks_passed,
        "automated_passed": automated_passed,
        "release_ready": not release_ready_blockers,
        "release_ready_reason": (
            "all_formal_gates_passed"
            if not release_ready_blockers
            else ",".join(release_ready_blockers)
        ),
        "release_ready_blockers": release_ready_blockers,
        "pairs": pair_reports,
        "failure_contract": failure_report,
        "human_review": human_report,
        "runner_provenance": runner_provenance,
        "baseline_admission": baseline_admission,
    }


def parse_system_specifications(specifications: Iterable[str]) -> dict[str, dict[str, Path]]:
    systems: dict[str, dict[str, Path]] = defaultdict(dict)
    for specification in specifications:
        left, separator, path_text = specification.partition("=")
        if not separator or ":" not in left:
            raise ValueError("System must use SYSTEM:PAIR=RESULT.json")
        system_id, pair = left.split(":", 1)
        if not re.fullmatch(r"[a-zA-Z0-9_.-]+", system_id):
            raise ValueError(f"Invalid system id: {system_id}")
        if pair not in {"en-zh", "ja-zh"}:
            raise ValueError(f"Invalid pair: {pair}")
        if pair in systems[system_id]:
            raise ValueError(f"Duplicate system pair: {system_id}/{pair}")
        systems[system_id][pair] = Path(path_text)
    if len(systems) < 2:
        raise ValueError("Blind comparison requires at least two systems")
    if any(set(pairs) != {"en-zh", "ja-zh"} for pairs in systems.values()):
        raise ValueError("Every blind system must provide en-zh and ja-zh")
    return dict(systems)


def _hmac_id(secret: bytes, label: str, length: int = 24) -> str:
    return hmac.new(secret, label.encode("utf-8"), hashlib.sha256).hexdigest()[:length]


def make_blind_bundle(
    systems: dict[str, dict[str, dict[str, Any]]],
    secret: bytes | None = None,
    fixtures_path: Path = DEFAULT_FIXTURES,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    if secret is not None and (not isinstance(secret, bytes) or len(secret) < 32):
        raise ValueError("Blind secret must contain at least 32 random bytes")
    secret = secret or secrets.token_bytes(32)
    fixtures = load_json(fixtures_path)
    suites = suite_map(fixtures)
    fixture_hash = sha256_file(fixtures_path)
    validated: dict[str, dict[str, tuple[list[dict[str, Any]], str]]] = defaultdict(dict)
    for system_id, pairs in systems.items():
        if not re.fullmatch(r"[a-zA-Z0-9_.-]+", system_id):
            raise ValueError(f"Invalid blind system id: {system_id}")
        if set(pairs) != {"en-zh", "ja-zh"}:
            raise ValueError("Every blind system must provide both language pairs")
        for pair in ("en-zh", "ja-zh"):
            validated[system_id][pair] = validate_candidate_against_suite(
                pairs[pair], pair, suites[pair], fixtures["corpus_release"], fixture_hash, formal=True
            )
    if len(validated) < 2:
        raise ValueError("Blind comparison requires at least two systems")

    bundle_id = _hmac_id(secret, f"bundle\0{fixtures['corpus_release']}\0{fixture_hash}", 32)
    sheet_items: list[dict[str, Any]] = []
    key_entries: list[dict[str, Any]] = []
    for pair in ("en-zh", "ja-zh"):
        for index, fixture in enumerate(suites[pair]["cases"]):
            item_id = _hmac_id(secret, f"item\0{pair}\0{fixture['id']}")
            ranked = sorted(validated, key=lambda system_id: _hmac_id(secret, f"order\0{item_id}\0{system_id}", 64))
            outputs = []
            for position, system_id in enumerate(ranked, start=1):
                cases, evidence_hash = validated[system_id][pair]
                output_id = _hmac_id(secret, f"output\0{item_id}\0{position}\0{system_id}")
                outputs.append({"output_id": output_id, "position": position, "text": cases[index]["translation_raw"]["output_text"]})
                key_entries.append(
                    {
                        "item_id": item_id,
                        "output_id": output_id,
                        "system_id": system_id,
                        "pair": pair,
                        "case_id": fixture["id"],
                        "system_evidence_sha256": evidence_hash,
                    }
                )
            sheet_items.append(
                {
                    "item_id": item_id,
                    "pair": pair,
                    "source_text": fixture["source_text"],
                    "category": fixture["category"],
                    "risk": fixture.get("risk", "general"),
                    "outputs": outputs,
                }
            )
    sheet = {
        "schema_version": 2,
        "bundle_id": bundle_id,
        "corpus_release": fixtures["corpus_release"],
        "fixture_sha256": fixture_hash,
        "instructions": "Rate without attempting to identify systems; the identity key is stored separately.",
        "items": sheet_items,
    }
    sheet_hash = canonical_json_sha256(sheet)
    key = {
        "schema_version": 2,
        "bundle_id": bundle_id,
        "sheet_sha256": sheet_hash,
        "corpus_release": fixtures["corpus_release"],
        "fixture_sha256": fixture_hash,
        "entries": key_entries,
    }
    rating_template = {
        "schema_version": 2,
        "bundle_id": bundle_id,
        "sheet_sha256": sheet_hash,
        "rater_id": "REPLACE_WITH_PSEUDONYMOUS_RATER_ID",
        "ratings": [
            {
                "item_id": entry["item_id"],
                "output_id": entry["output_id"],
                "adequacy": None,
                "fluency": None,
                "critical_error": "none",
                "notes": "",
            }
            for entry in key_entries
        ],
    }
    return sheet, key, rating_template


def _validate_blind_sheet_and_key(
    sheet: dict[str, Any],
    key: dict[str, Any],
    fixtures_path: Path,
) -> dict[tuple[str, str], dict[str, Any]]:
    _exact_keys(sheet, {"schema_version", "bundle_id", "corpus_release", "fixture_sha256", "instructions", "items"}, "blind sheet")
    _exact_keys(key, {"schema_version", "bundle_id", "sheet_sha256", "corpus_release", "fixture_sha256", "entries"}, "blind key")
    if sheet["schema_version"] != 2 or key["schema_version"] != 2:
        raise ValueError("Unsupported blind bundle schema")
    if key["sheet_sha256"] != canonical_json_sha256(sheet):
        raise ValueError("Blind key does not match the supplied sheet")
    for field in ("bundle_id", "corpus_release", "fixture_sha256"):
        if key[field] != sheet[field]:
            raise ValueError(f"Blind key/sheet {field} mismatch")
    fixtures = load_json(fixtures_path)
    if sheet["corpus_release"] != fixtures["corpus_release"] or sheet["fixture_sha256"] != sha256_file(fixtures_path):
        raise ValueError("Blind sheet targets another canonical corpus")
    canonical: dict[tuple[str, str], dict[str, Any]] = {}
    canonical_by_source: dict[tuple[str, str], tuple[str, str]] = {}
    for pair, suite in suite_map(fixtures).items():
        for case in suite["cases"]:
            route = (pair, case["id"])
            canonical[route] = case
            source_key = (pair, case["source_text"])
            if source_key in canonical_by_source:
                raise ValueError(f"Canonical corpus has duplicate blind source: {pair}")
            canonical_by_source[source_key] = route
    output_to_item: dict[tuple[str, str], tuple[str, str]] = {}
    seen_items: set[str] = set()
    sheet_routes: set[tuple[str, str]] = set()
    for index, item in enumerate(sheet["items"]):
        _exact_keys(item, {"item_id", "pair", "source_text", "category", "risk", "outputs"}, f"blind sheet item[{index}]")
        item_id = _nonempty_string(item["item_id"], f"blind sheet item[{index}].item_id")
        if item_id in seen_items:
            raise ValueError(f"Duplicate blind item id: {item_id}")
        seen_items.add(item_id)
        route = canonical_by_source.get((item["pair"], item["source_text"]))
        fixture = canonical.get(route) if route is not None else None
        if fixture is None or route in sheet_routes:
            raise ValueError(f"Blind sheet has unknown or duplicate canonical case: {route}")
        sheet_routes.add(route)
        if item["source_text"] != fixture["source_text"] or item["category"] != fixture["category"] or item["risk"] != fixture.get("risk", "general"):
            raise ValueError(f"Blind sheet canonical join mismatch: {route}")
        outputs = item["outputs"]
        if not isinstance(outputs, list) or len(outputs) < 2:
            raise ValueError(f"Blind item has fewer than two outputs: {route}")
        for position, output in enumerate(outputs, start=1):
            _exact_keys(output, {"output_id", "position", "text"}, f"blind output {route}/{position}")
            if output["position"] != position:
                raise ValueError(f"Blind output positions are not contiguous: {route}")
            _output_string(output["text"], f"blind output {route}/{position}.text")
            output_key = (item_id, _nonempty_string(output["output_id"], "blind output id"))
            if output_key in output_to_item:
                raise ValueError(f"Duplicate blind output id: {output_key}")
            output_to_item[output_key] = route
    if sheet_routes != set(canonical):
        raise ValueError("Blind sheet does not cover the canonical corpus exactly")

    expected: dict[tuple[str, str], dict[str, Any]] = {}
    systems_by_route: dict[tuple[str, str], set[str]] = defaultdict(set)
    seen_system_routes: set[tuple[str, str, str]] = set()
    for index, entry in enumerate(key["entries"]):
        _exact_keys(
            entry,
            {"item_id", "output_id", "system_id", "pair", "case_id", "system_evidence_sha256"},
            f"blind key entry[{index}]",
        )
        output_key = (entry["item_id"], entry["output_id"])
        route = output_to_item.get(output_key)
        if route is None or route != (entry["pair"], entry["case_id"]):
            raise ValueError(f"Blind key entry does not match sheet output: {output_key}")
        if output_key in expected:
            raise ValueError(f"Duplicate blind key entry: {output_key}")
        _nonempty_string(entry["system_id"], f"blind key {output_key}.system_id")
        if not re.fullmatch(r"[0-9a-f]{64}", str(entry["system_evidence_sha256"])):
            raise ValueError(f"Invalid system evidence hash: {output_key}")
        system_route = (entry["pair"], entry["case_id"], entry["system_id"])
        if system_route in seen_system_routes:
            raise ValueError(f"Blind key repeats a system for one canonical case: {system_route}")
        seen_system_routes.add(system_route)
        expected[output_key] = entry
        systems_by_route[route].add(entry["system_id"])
    if set(expected) != set(output_to_item):
        raise ValueError("Blind key does not cover every sheet output exactly once")
    system_sets = {tuple(sorted(value)) for value in systems_by_route.values()}
    if len(system_sets) != 1:
        raise ValueError("Blind key system coverage varies between cases")
    if not system_sets or len(next(iter(system_sets))) < 2:
        raise ValueError("Blind comparison requires at least two distinct systems per case")
    return expected


def validate_human_rubric(rubric: dict[str, Any]) -> dict[str, Any]:
    _exact_keys(
        rubric,
        {
            "schema_version",
            "license_spdx",
            "instructions",
            "adequacy",
            "fluency",
            "critical_error_values",
        },
        "human-rating rubric",
    )
    if rubric["schema_version"] != 1:
        raise ValueError("Unsupported human-rating rubric schema")
    _nonempty_string(rubric["license_spdx"], "human-rating rubric.license_spdx")
    instructions = rubric["instructions"]
    if (
        not isinstance(instructions, list)
        or not instructions
        or any(not isinstance(value, str) or not value.strip() for value in instructions)
    ):
        raise ValueError("Human-rating rubric instructions must be non-empty strings")
    expected_scale = {str(value) for value in range(1, 6)}
    for dimension in ("adequacy", "fluency"):
        scale = rubric[dimension]
        if not isinstance(scale, dict) or set(scale) != expected_scale:
            raise ValueError(f"Human-rating rubric {dimension} must define exactly 1..5")
        for score, description in scale.items():
            _nonempty_string(description, f"human-rating rubric.{dimension}.{score}")
    errors = rubric["critical_error_values"]
    if (
        not isinstance(errors, list)
        or any(not isinstance(value, str) or not value.strip() for value in errors)
        or len(errors) != len(set(errors))
        or "none" not in errors
    ):
        raise ValueError("Human rubric has no valid critical-error vocabulary")
    return rubric


def _validate_blind_system_bindings(
    sheet: dict[str, Any],
    key: dict[str, Any],
    system_cases: dict[str, dict[str, list[dict[str, Any]]]],
    system_hashes: dict[str, dict[str, str]],
    fixtures_path: Path,
) -> None:
    expected = _validate_blind_sheet_and_key(sheet, key, fixtures_path)
    if set(system_cases) != set(system_hashes) or len(system_cases) != 2:
        raise ValueError("Formal blind gate requires exactly candidate and baseline systems")
    actual_systems = {entry["system_id"] for entry in expected.values()}
    if actual_systems != set(system_cases):
        raise ValueError(
            "Blind key systems must equal the formal candidate and baseline ids exactly"
        )
    sheet_outputs = {
        (item["item_id"], output["output_id"]): output["text"]
        for item in sheet["items"]
        for output in item["outputs"]
    }
    expected_outputs = {
        system_id: {
            (pair, case["id"]): case["translation_raw"]["output_text"]
            for pair, cases in pairs.items()
            for case in cases
        }
        for system_id, pairs in system_cases.items()
    }
    actual_routes: dict[str, set[tuple[str, str]]] = defaultdict(set)
    for output_key, entry in expected.items():
        system_id = entry["system_id"]
        route = (entry["pair"], entry["case_id"])
        if route in actual_routes[system_id]:
            raise ValueError(f"Blind key repeats {system_id} output for {route}")
        actual_routes[system_id].add(route)
        if entry["system_evidence_sha256"] != system_hashes[system_id].get(entry["pair"]):
            raise ValueError(
                f"Blind key is not hash-bound to {system_id} evidence for {entry['pair']}"
            )
        if sheet_outputs[output_key] != expected_outputs[system_id].get(route):
            raise ValueError(
                f"Blind sheet output is not bound to {system_id} evidence for {route}"
            )
    for system_id, outputs in expected_outputs.items():
        if actual_routes[system_id] != set(outputs):
            missing = sorted(set(outputs) - actual_routes[system_id])
            raise ValueError(
                f"Blind bundle does not cover {system_id} exactly; missing={missing}"
            )


def score_human_ratings(
    sheet: dict[str, Any],
    key: dict[str, Any],
    rating_documents: list[dict[str, Any]],
    rubric: dict[str, Any],
    fixtures_path: Path = DEFAULT_FIXTURES,
) -> dict[str, Any]:
    expected = _validate_blind_sheet_and_key(sheet, key, fixtures_path)
    validate_human_rubric(rubric)
    valid_errors = set(rubric["critical_error_values"])
    if not rating_documents:
        raise ValueError("At least one completed rating document is required")
    rater_ids: set[str] = set()
    grouped: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    counts_by_output: dict[tuple[str, str], int] = defaultdict(int)
    for document in rating_documents:
        _exact_keys(document, {"schema_version", "bundle_id", "sheet_sha256", "rater_id", "ratings"}, "rating document")
        if document["schema_version"] != 2 or document["bundle_id"] != sheet["bundle_id"] or document["sheet_sha256"] != canonical_json_sha256(sheet):
            raise ValueError("Rating document is not bound to the supplied blind sheet")
        rater_id = _nonempty_string(document["rater_id"], "rating document.rater_id").strip()
        if rater_id == "REPLACE_WITH_PSEUDONYMOUS_RATER_ID" or rater_id in rater_ids:
            raise ValueError(f"Missing or duplicate pseudonymous rater id: {rater_id}")
        rater_ids.add(rater_id)
        ratings = document["ratings"]
        if not isinstance(ratings, list):
            raise ValueError("Rating document ratings must be a list")
        actual_keys: set[tuple[str, str]] = set()
        for index, rating in enumerate(ratings):
            _exact_keys(
                rating,
                {"item_id", "output_id", "adequacy", "fluency", "critical_error", "notes"},
                f"rating {rater_id}[{index}]",
            )
            rating_key = (rating["item_id"], rating["output_id"])
            if rating_key in actual_keys:
                raise ValueError(f"Duplicate rating output: {rating_key}")
            actual_keys.add(rating_key)
            if rating_key not in expected:
                raise ValueError(f"Unknown rating output: {rating_key}")
            adequacy = rating["adequacy"]
            fluency = rating["fluency"]
            if isinstance(adequacy, bool) or adequacy not in {1, 2, 3, 4, 5}:
                raise ValueError(f"Adequacy outside 1..5: {rating_key}")
            if isinstance(fluency, bool) or fluency not in {1, 2, 3, 4, 5}:
                raise ValueError(f"Fluency outside 1..5: {rating_key}")
            if rating["critical_error"] not in valid_errors or not isinstance(rating["notes"], str):
                raise ValueError(f"Invalid critical error or notes: {rating_key}")
            entry = expected[rating_key]
            system_pair = (entry["system_id"], entry["pair"])
            grouped[system_pair].append(
                {
                    "adequacy": adequacy,
                    "fluency": fluency,
                    "critical": rating["critical_error"] != "none",
                    "case_id": entry["case_id"],
                    "evidence_hash": entry["system_evidence_sha256"],
                }
            )
            counts_by_output[rating_key] += 1
        if actual_keys != set(expected) or len(ratings) != len(expected):
            raise ValueError(f"Rater {rater_id} did not score every blind output exactly once")

    systems: dict[str, dict[str, Any]] = defaultdict(dict)
    expected_case_counts = {
        pair: sum(1 for item in sheet["items"] if item["pair"] == pair)
        for pair in ("en-zh", "ja-zh")
    }
    for (system_id, pair), ratings in grouped.items():
        evidence_hashes = {rating["evidence_hash"] for rating in ratings}
        if len(evidence_hashes) != 1:
            raise ValueError(f"Blind key mixes evidence hashes for {system_id}/{pair}")
        covered_cases = {rating["case_id"] for rating in ratings}
        output_counts = [
            count
            for output_key, count in counts_by_output.items()
            if expected[output_key]["system_id"] == system_id and expected[output_key]["pair"] == pair
        ]
        systems[system_id][pair] = {
            "system_evidence_sha256": next(iter(evidence_hashes)),
            "mean_adequacy": round(statistics.fmean(rating["adequacy"] for rating in ratings), 4),
            "mean_fluency": round(statistics.fmean(rating["fluency"] for rating in ratings), 4),
            "critical_error_rate": round(sum(rating["critical"] for rating in ratings) / len(ratings), 6),
            "case_coverage": round(len(covered_cases) / expected_case_counts[pair], 6),
            "minimum_ratings_per_output": min(output_counts),
            "rating_count": len(ratings),
        }
    return {
        "schema_version": 2,
        "bundle_id": sheet["bundle_id"],
        "sheet_sha256": canonical_json_sha256(sheet),
        "corpus_release": sheet["corpus_release"],
        "fixture_sha256": sheet["fixture_sha256"],
        "rater_count": len(rater_ids),
        "systems": dict(systems),
    }


def _smoke_pair_report(candidate: dict[str, Any], baseline: dict[str, Any], pair: str, fixtures: dict[str, Any], fixture_hash: str, thresholds: dict[str, Any], edition: str) -> dict[str, Any]:
    suite = suite_map(fixtures)[pair]
    candidate_cases, _ = validate_candidate_against_suite(candidate, pair, suite, fixtures["corpus_release"], fixture_hash, formal=False)
    baseline_cases, _ = validate_candidate_against_suite(baseline, pair, suite, fixtures["corpus_release"], fixture_hash, formal=False)
    return evaluate_pair_gate(candidate_cases, baseline_cases, thresholds["editions"][edition][pair], thresholds["comparison"])


def smoke_report(
    fixtures_path: Path = DEFAULT_FIXTURES,
    pin_path: Path = DEFAULT_PIN,
    failures_path: Path = DEFAULT_FAILURES,
    thresholds_path: Path = DEFAULT_THRESHOLDS,
    calibration_path: Path = DEFAULT_CALIBRATION,
) -> dict[str, Any]:
    validation = validate_fixtures(fixtures_path, pin_path, failures_path, thresholds_path, calibration_path)
    fixtures = load_json(fixtures_path)
    thresholds = load_json(thresholds_path)
    fixture_hash = sha256_file(fixtures_path)
    candidates = {pair: make_reference_replay(pair, "candidate-smoke", fixtures_path) for pair in ("en-zh", "ja-zh")}
    baselines = {pair: make_reference_replay(pair, "baseline-smoke", fixtures_path) for pair in ("en-zh", "ja-zh")}
    editions = {}
    for edition in ("lite", "full", "online"):
        pair_reports = {
            pair: _smoke_pair_report(candidates[pair], baselines[pair], pair, fixtures, fixture_hash, thresholds, edition)
            for pair in ("en-zh", "ja-zh")
        }
        editions[edition] = {
            "report_kind": "harness_smoke_not_model_evidence",
            "automated_passed": all(report["passed"] for report in pair_reports.values()),
            "release_ready": False,
            "pairs": pair_reports,
            "failure_contract": {
                "required": edition == "online",
                "passed": True,
                "status": "contract_schema_only_not_production_execution",
            },
            "human_review": {"passed": False, "status": "not_run_in_harness_smoke"},
        }
    return {
        "schema_version": 2,
        "report_kind": "deterministic_harness_smoke_not_model_evidence",
        "validation": validation,
        "editions": editions,
        "passed": all(report["automated_passed"] for report in editions.values()),
        "release_ready": False,
        "release_ready_reason": "Synthetic references validate plumbing only; real inference, Kotlin failure evidence and blind ratings are absent.",
    }


def parse_pair_paths(values: Iterable[str]) -> dict[str, Path]:
    parsed: dict[str, Path] = {}
    for value in values:
        pair, separator, path = value.partition("=")
        if not separator or pair not in {"en-zh", "ja-zh"}:
            raise ValueError("Result path must use en-zh=FILE or ja-zh=FILE")
        if pair in parsed:
            raise ValueError(f"Duplicate result path for {pair}")
        parsed[pair] = Path(path)
    if set(parsed) != {"en-zh", "ja-zh"}:
        raise ValueError("Both en-zh and ja-zh result paths are required")
    return parsed


def ensure_external_blind_key_path(path: Path) -> Path:
    resolved = path.expanduser().resolve()
    try:
        resolved.relative_to(ROOT.resolve())
    except ValueError:
        return resolved
    raise ValueError("Blind identity keys must be written outside the repository")


def default_blind_key_path(bundle_id: str) -> Path:
    return DEFAULT_BLIND_KEY_DIRECTORY / f"{bundle_id}.blind-key.json"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate_parser = subparsers.add_parser("validate", help="Validate corpus, pins, contracts and thresholds")
    validate_parser.add_argument("--output", type=Path)

    replay_parser = subparsers.add_parser("replay", help="Emit synthetic harness input (never release evidence)")
    replay_parser.add_argument("--pair", choices=("en-zh", "ja-zh"), required=True)
    replay_parser.add_argument("--engine-id", required=True)
    replay_parser.add_argument("--output", type=Path, required=True)

    blind_parser = subparsers.add_parser("blind", help="Build a blinded comparison bundle")
    blind_parser.add_argument("--system", action="append", required=True, help="SYSTEM:PAIR=RESULT.json")
    blind_parser.add_argument("--sheet", type=Path, required=True)
    blind_parser.add_argument("--key", type=Path, help="Identity key path outside the repository; defaults under the user profile")
    blind_parser.add_argument("--rating-template", type=Path, required=True)

    human_parser = subparsers.add_parser("score-human", help="Aggregate strict blind ratings")
    human_parser.add_argument("--sheet", type=Path, required=True)
    human_parser.add_argument("--key", type=Path, required=True)
    human_parser.add_argument("--ratings", type=Path, action="append", required=True)
    human_parser.add_argument("--rubric", type=Path, default=DEFAULT_RUBRIC)
    human_parser.add_argument("--output", type=Path, required=True)

    gate_parser = subparsers.add_parser("gate", help="Evaluate real candidate evidence")
    gate_parser.add_argument("--edition", choices=("lite", "full", "online"), required=True)
    gate_parser.add_argument("--candidate", action="append", required=True)
    gate_parser.add_argument("--baseline", action="append", required=True)
    gate_parser.add_argument("--blind-sheet", type=Path, required=True)
    gate_parser.add_argument("--blind-key", type=Path, required=True)
    gate_parser.add_argument("--ratings", type=Path, action="append", required=True)
    gate_parser.add_argument("--rubric", type=Path, required=True)
    gate_parser.add_argument("--candidate-system", required=True)
    gate_parser.add_argument("--baseline-system", required=True)
    gate_parser.add_argument("--output", type=Path, required=True)

    online_parser = subparsers.add_parser(
        "audit-legacy-online-evidence",
        help="Audit legacy schema-v2 evidence; never a formal release-gate input",
    )
    online_parser.add_argument("--evidence", type=Path, required=True)
    online_parser.add_argument("--output", type=Path)

    smoke_parser = subparsers.add_parser("smoke", help="Run non-model deterministic harness smoke")
    smoke_parser.add_argument("--output", type=Path, required=True)

    args = parser.parse_args()
    if args.command == "validate":
        result = validate_fixtures()
        if args.output:
            write_json(args.output, result)
    elif args.command == "replay":
        result = make_reference_replay(args.pair, args.engine_id)
        write_json(args.output, result)
    elif args.command == "blind":
        system_paths = parse_system_specifications(args.system)
        systems = {
            system_id: {pair: load_json(path) for pair, path in pair_paths.items()}
            for system_id, pair_paths in system_paths.items()
        }
        sheet, key, template = make_blind_bundle(systems)
        key_path = ensure_external_blind_key_path(args.key or default_blind_key_path(sheet["bundle_id"]))
        write_json(args.sheet, sheet)
        write_json(key_path, key)
        write_json(args.rating_template, template)
        result = {"sheet": str(args.sheet), "key": str(key_path), "rating_template": str(args.rating_template)}
    elif args.command == "score-human":
        result = score_human_ratings(
            load_json(args.sheet),
            load_json(ensure_external_blind_key_path(args.key)),
            [load_json(path) for path in args.ratings],
            load_json(args.rubric),
        )
        write_json(args.output, result)
    elif args.command == "gate":
        candidate_paths = parse_pair_paths(args.candidate)
        baseline_paths = parse_pair_paths(args.baseline)
        result = run_gate(
            args.edition,
            {pair: load_json(path) for pair, path in candidate_paths.items()},
            {pair: load_json(path) for pair, path in baseline_paths.items()},
            blind_sheet_path=args.blind_sheet,
            blind_key_path=args.blind_key,
            rating_paths=args.ratings,
            rubric_path=args.rubric,
            candidate_system=args.candidate_system,
            baseline_system=args.baseline_system,
        )
        write_json(args.output, result)
        if not result["release_ready"]:
            raise SystemExit(1)
    elif args.command == "audit-legacy-online-evidence":
        audit = verify_failure_evidence(
            load_json(args.evidence),
            load_json(DEFAULT_FAILURES),
        )
        result = {
            **audit,
            "formal_gate_eligible": False,
            "status": "legacy_audit_only_not_fresh_release_evidence",
        }
        if args.output:
            write_json(args.output, result)
    elif args.command == "smoke":
        result = smoke_report()
        write_json(args.output, result)
        if not result["passed"] or result["release_ready"]:
            raise SystemExit(1)
    else:
        raise AssertionError(args.command)
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
