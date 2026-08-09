#!/usr/bin/env python3
"""Generate and verify the canonical translation-provider admission record.

The Android/Kotlin code never accepts a caller-created "verified" runtime gate.
This repository-side verifier derives the gate from the checked-out gitlink,
the live GitHub pull-request record, submodule ancestry, and real artifact
hashes.  It also validates compact score/device/release summaries when their
paths are supplied through the versioned source record's environment-variable
names.

The generated JSON is deterministic.  A sidecar pins its SHA-256, and a
generated Kotlin source embeds the exact JSON plus the same pin.  CI runs this
tool in ``--check`` mode so hand edits to the JSON, pin, generated Kotlin, PR
state, gitlink, corpus, verifier, or available artifacts fail closed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path
from typing import Any, Iterable


SOURCE_SCHEMA = "screen-translation-admission-source/v1"
CANONICAL_SCHEMA = "screen-translation-admission/v1"
SCORE_SCHEMA = "screen-translation-score-summary/v1"
DEVICE_SCHEMA = "screen-translation-device-summary/v1"
RELEASE_SCHEMA = "screen-translation-release-summary/v1"
EXPECTED_UPSTREAM_REPOSITORY = "ggml-org/llama.cpp"
EXPECTED_PULL_REQUEST_NUMBER = 22836
EXPECTED_SUBMODULE_PATH = "third_party/llama.cpp"

DEFAULT_SOURCE = "docs/evidence/hymt2-stq-admission-source-v1.json"
DEFAULT_OUTPUT = "docs/evidence/hymt2-stq-admission-v1.json"
DEFAULT_PIN = "docs/evidence/hymt2-stq-admission-v1.json.sha256"
DEFAULT_KOTLIN = (
    "app/src/main/java/com/screentranslation/app/ml/"
    "GeneratedTranslationAdmissionEvidence.kt"
)

SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA64 = re.compile(r"^[0-9a-f]{64}$")
IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._-]*$")
KOTLIN_INT_MAX = (1 << 31) - 1
KOTLIN_LONG_MAX = (1 << 63) - 1


class EvidenceError(ValueError):
    """Raised when a versioned or observed evidence record is malformed."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    return (json.dumps(
        value,
        ensure_ascii=False,
        indent=2,
        sort_keys=True,
        allow_nan=False,
    ) + "\n").encode(
        "utf-8"
    )


def require_object(value: Any, expected_keys: Iterable[str], context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvidenceError(f"{context} must be an object")
    expected = set(expected_keys)
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        raise EvidenceError(f"{context} keys mismatch: missing={missing}, extra={extra}")
    return value


def require_list(value: Any, context: str) -> list[Any]:
    if not isinstance(value, list):
        raise EvidenceError(f"{context} must be an array")
    return value


def require_string(value: Any, context: str, *, pattern: re.Pattern[str] | None = None) -> str:
    if not isinstance(value, str) or not value:
        raise EvidenceError(f"{context} must be a non-empty string")
    if pattern is not None and pattern.fullmatch(value) is None:
        raise EvidenceError(f"{context} has invalid format: {value!r}")
    return value


def optional_string(value: Any, context: str, *, pattern: re.Pattern[str] | None = None) -> str | None:
    if value is None:
        return None
    return require_string(value, context, pattern=pattern)


def require_bool(value: Any, context: str) -> bool:
    if type(value) is not bool:
        raise EvidenceError(f"{context} must be a boolean")
    return value


def require_int(
    value: Any,
    context: str,
    *,
    minimum: int = 0,
    maximum: int = KOTLIN_LONG_MAX,
) -> int:
    if type(value) is not int or value < minimum or value > maximum:
        raise EvidenceError(
            f"{context} must be an integer between {minimum} and {maximum}"
        )
    return value


def optional_int(
    value: Any,
    context: str,
    *,
    minimum: int = 0,
    maximum: int = KOTLIN_LONG_MAX,
) -> int | None:
    if value is None:
        return None
    return require_int(value, context, minimum=minimum, maximum=maximum)


def require_number(
    value: Any,
    context: str,
    *,
    minimum: float = 0.0,
    maximum: float | None = None,
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvidenceError(f"{context} must be a number")
    result = float(value)
    if not math.isfinite(result) or not (result >= minimum) or (
        maximum is not None and result > maximum
    ):
        upper = "" if maximum is None else f" and <= {maximum}"
        raise EvidenceError(f"{context} must be >= {minimum}{upper}")
    return result


def optional_number(
    value: Any,
    context: str,
    *,
    minimum: float = 0.0,
    maximum: float | None = None,
) -> float | None:
    if value is None:
        return None
    return require_number(value, context, minimum=minimum, maximum=maximum)


def unique_strings(value: Any, context: str, *, allow_empty: bool = False) -> list[str]:
    raw = require_list(value, context)
    result = [require_string(item, f"{context}[]", pattern=IDENTIFIER) for item in raw]
    if not allow_empty and not result:
        raise EvidenceError(f"{context} must not be empty")
    if len(result) != len(set(result)):
        raise EvidenceError(f"{context} contains duplicates")
    return result


def load_json(path: Path) -> Any:
    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise EvidenceError(f"duplicate JSON object key in {path}: {key}")
            result[key] = value
        return result

    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=reject_duplicate_keys,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise EvidenceError(f"cannot read JSON {path}: {error}") from error


def validate_artifact_source(
    value: Any,
    context: str,
    *,
    has_size: bool,
    extra_keys: set[str] | None = None,
) -> dict[str, Any]:
    keys = {"expected_sha256", "path_environment"}
    if has_size:
        keys |= {"expected_size_bytes", "revision"}
    keys |= extra_keys or set()
    obj = require_object(value, keys, context)
    require_string(obj["expected_sha256"], f"{context}.expected_sha256", pattern=SHA64)
    require_string(obj["path_environment"], f"{context}.path_environment")
    if has_size:
        require_int(obj["expected_size_bytes"], f"{context}.expected_size_bytes", minimum=1)
        require_string(obj["revision"], f"{context}.revision", pattern=SHA40)
    return obj


def validate_source(value: Any) -> dict[str, Any]:
    root = require_object(
        value,
        {
            "schema",
            "candidate_id",
            "recorded_date",
            "runtime",
            "artifacts",
            "declared_device",
            "routes",
        },
        "source",
    )
    if root["schema"] != SOURCE_SCHEMA:
        raise EvidenceError(f"source.schema must be {SOURCE_SCHEMA}")
    require_string(root["candidate_id"], "source.candidate_id", pattern=IDENTIFIER)
    require_string(root["recorded_date"], "source.recorded_date")

    runtime = require_object(
        root["runtime"],
        {
            "upstream_repository",
            "pull_request_number",
            "expected_pull_request_head",
            "submodule_path",
            "declared_runtime_commit",
        },
        "source.runtime",
    )
    if runtime["upstream_repository"] != EXPECTED_UPSTREAM_REPOSITORY:
        raise EvidenceError("source.runtime.upstream_repository is not the canonical upstream")
    if runtime["pull_request_number"] != EXPECTED_PULL_REQUEST_NUMBER:
        raise EvidenceError("source.runtime.pull_request_number is not the canonical PR")
    if runtime["submodule_path"] != EXPECTED_SUBMODULE_PATH:
        raise EvidenceError("source.runtime.submodule_path is not the canonical gitlink")
    require_string(
        runtime["expected_pull_request_head"],
        "source.runtime.expected_pull_request_head",
        pattern=SHA40,
    )
    require_string(
        runtime["declared_runtime_commit"],
        "source.runtime.declared_runtime_commit",
        pattern=SHA40,
    )

    artifacts = require_object(
        root["artifacts"],
        {
            "source_model",
            "runnable_model",
            "transformation_manifest",
            "corpus",
            "apk",
            "device_summary",
            "release_summary",
        },
        "source.artifacts",
    )
    validate_artifact_source(artifacts["source_model"], "source.artifacts.source_model", has_size=True)
    validate_artifact_source(artifacts["runnable_model"], "source.artifacts.runnable_model", has_size=False)
    validate_artifact_source(
        artifacts["transformation_manifest"],
        "source.artifacts.transformation_manifest",
        has_size=False,
        extra_keys={"transformation_id"},
    )
    require_string(
        artifacts["transformation_manifest"]["transformation_id"],
        "source.artifacts.transformation_manifest.transformation_id",
        pattern=IDENTIFIER,
    )

    corpus = require_object(
        artifacts["corpus"],
        {"corpus_id", "path", "expected_sha256", "suite_ids"},
        "source.artifacts.corpus",
    )
    require_string(corpus["corpus_id"], "source.artifacts.corpus.corpus_id", pattern=IDENTIFIER)
    require_string(corpus["path"], "source.artifacts.corpus.path")
    require_string(corpus["expected_sha256"], "source.artifacts.corpus.expected_sha256", pattern=SHA64)
    unique_strings(corpus["suite_ids"], "source.artifacts.corpus.suite_ids")

    apk = require_object(
        artifacts["apk"],
        {
            "edition",
            "expected_application_id",
            "expected_sha256",
            "expected_signer_cert_sha256",
            "path_environment",
        },
        "source.artifacts.apk",
    )
    require_string(apk["edition"], "source.artifacts.apk.edition", pattern=IDENTIFIER)
    require_string(apk["expected_application_id"], "source.artifacts.apk.expected_application_id")
    optional_string(apk["expected_sha256"], "source.artifacts.apk.expected_sha256", pattern=SHA64)
    optional_string(
        apk["expected_signer_cert_sha256"],
        "source.artifacts.apk.expected_signer_cert_sha256",
        pattern=SHA64,
    )
    require_string(apk["path_environment"], "source.artifacts.apk.path_environment")

    for key in ("device_summary", "release_summary"):
        summary = require_object(
            artifacts[key],
            {"expected_sha256", "path_environment"},
            f"source.artifacts.{key}",
        )
        optional_string(summary["expected_sha256"], f"source.artifacts.{key}.expected_sha256", pattern=SHA64)
        require_string(summary["path_environment"], f"source.artifacts.{key}.path_environment")

    device = require_object(
        root["declared_device"],
        {
            "model",
            "model_code",
            "codename",
            "android_api",
            "android_build",
            "rom",
            "abi",
            "execution",
        },
        "source.declared_device",
    )
    for key in ("model", "model_code", "codename", "android_build", "rom", "abi", "execution"):
        require_string(device[key], f"source.declared_device.{key}")
    require_int(
        device["android_api"],
        "source.declared_device.android_api",
        minimum=1,
        maximum=KOTLIN_INT_MAX,
    )

    routes = require_list(root["routes"], "source.routes")
    if len(routes) != 2:
        raise EvidenceError("source.routes must contain exactly en-zh and ja-zh")
    route_ids: list[str] = []
    for index, route_value in enumerate(routes):
        route = require_object(
            route_value,
            {
                "route_id",
                "corpus_suite_id",
                "expected_critical_check_count",
                "score_summary_environment",
                "expected_score_summary_sha256",
                "historical_score_artifact_sha256",
                "historical_raw_result_sha256",
            },
            f"source.routes[{index}]",
        )
        route_ids.append(require_string(route["route_id"], f"source.routes[{index}].route_id", pattern=IDENTIFIER))
        require_string(route["corpus_suite_id"], f"source.routes[{index}].corpus_suite_id", pattern=IDENTIFIER)
        require_int(
            route["expected_critical_check_count"],
            f"source.routes[{index}].expected_critical_check_count",
            minimum=1,
            maximum=KOTLIN_INT_MAX,
        )
        require_string(route["score_summary_environment"], f"source.routes[{index}].score_summary_environment")
        optional_string(
            route["expected_score_summary_sha256"],
            f"source.routes[{index}].expected_score_summary_sha256",
            pattern=SHA64,
        )
        require_string(
            route["historical_score_artifact_sha256"],
            f"source.routes[{index}].historical_score_artifact_sha256",
            pattern=SHA64,
        )
        require_string(
            route["historical_raw_result_sha256"],
            f"source.routes[{index}].historical_raw_result_sha256",
            pattern=SHA64,
        )
    if route_ids != ["en-zh", "ja-zh"]:
        raise EvidenceError("source.routes must be ordered as en-zh, ja-zh")
    return root


def validate_device_summary(value: Any, declared: dict[str, Any]) -> dict[str, Any]:
    obj = require_object(
        value,
        {
            "schema",
            "model",
            "model_code",
            "codename",
            "android_api",
            "android_build",
            "rom",
            "abi",
            "execution",
            "captured_at",
            "adb_serial_sha256",
        },
        "device_summary",
    )
    if obj["schema"] != DEVICE_SCHEMA:
        raise EvidenceError(f"device_summary.schema must be {DEVICE_SCHEMA}")
    for key in ("model", "model_code", "codename", "android_build", "rom", "abi", "execution"):
        require_string(obj[key], f"device_summary.{key}")
        if obj[key] != declared[key]:
            raise EvidenceError(f"device_summary.{key} does not match the declared device")
    require_int(
        obj["android_api"],
        "device_summary.android_api",
        minimum=1,
        maximum=KOTLIN_INT_MAX,
    )
    if obj["android_api"] != declared["android_api"]:
        raise EvidenceError("device_summary.android_api does not match the declared device")
    require_string(obj["captured_at"], "device_summary.captured_at")
    require_string(obj["adb_serial_sha256"], "device_summary.adb_serial_sha256", pattern=SHA64)
    return obj


def validate_score_summary(
    value: Any,
    expected_route: dict[str, Any],
    expected_critical_ids: list[str],
) -> dict[str, Any]:
    obj = require_object(
        value,
        {
            "schema",
            "candidate_id",
            "route_id",
            "corpus_suite_id",
            "corpus_sha256",
            "source_model_sha256",
            "runnable_model_sha256",
            "transformation_manifest_sha256",
            "apk_sha256",
            "signer_cert_sha256",
            "device_summary_sha256",
            "evaluation_run_id",
            "q4_bleu_retention_percent",
            "critical_evaluated_ids",
            "critical_regressed_ids",
            "raw_median_latency_ms",
            "pipeline",
        },
        "score_summary",
    )
    if obj["schema"] != SCORE_SCHEMA:
        raise EvidenceError(f"score_summary.schema must be {SCORE_SCHEMA}")
    require_string(obj["candidate_id"], "score_summary.candidate_id", pattern=IDENTIFIER)
    if obj["route_id"] != expected_route["route_id"]:
        raise EvidenceError("score_summary.route_id does not match its route binding")
    if obj["corpus_suite_id"] != expected_route["corpus_suite_id"]:
        raise EvidenceError("score_summary.corpus_suite_id does not match its route binding")
    for key in (
        "corpus_sha256",
        "source_model_sha256",
        "runnable_model_sha256",
        "transformation_manifest_sha256",
        "apk_sha256",
        "signer_cert_sha256",
        "device_summary_sha256",
    ):
        require_string(obj[key], f"score_summary.{key}", pattern=SHA64)
    require_string(obj["evaluation_run_id"], "score_summary.evaluation_run_id", pattern=IDENTIFIER)
    require_number(
        obj["q4_bleu_retention_percent"],
        "score_summary.q4_bleu_retention_percent",
        minimum=0.0,
        maximum=100.0,
    )
    evaluated = unique_strings(obj["critical_evaluated_ids"], "score_summary.critical_evaluated_ids")
    regressed = unique_strings(
        obj["critical_regressed_ids"],
        "score_summary.critical_regressed_ids",
        allow_empty=True,
    )
    if evaluated != expected_critical_ids:
        raise EvidenceError("score_summary critical evaluated IDs do not match the canonical corpus")
    if not set(regressed).issubset(evaluated):
        raise EvidenceError("score_summary regressed IDs must be a subset of evaluated IDs")
    require_number(obj["raw_median_latency_ms"], "score_summary.raw_median_latency_ms")
    pipeline = require_object(
        obj["pipeline"],
        {"median_latency_ms", "p95_latency_ms", "timeout_count"},
        "score_summary.pipeline",
    )
    require_number(pipeline["median_latency_ms"], "score_summary.pipeline.median_latency_ms")
    require_number(pipeline["p95_latency_ms"], "score_summary.pipeline.p95_latency_ms")
    require_int(
        pipeline["timeout_count"],
        "score_summary.pipeline.timeout_count",
        maximum=KOTLIN_INT_MAX,
    )
    return obj


def validate_release_summary(value: Any) -> dict[str, Any]:
    obj = require_object(
        value,
        {
            "schema",
            "candidate_id",
            "corpus_sha256",
            "source_model_sha256",
            "runnable_model_sha256",
            "transformation_manifest_sha256",
            "apk_sha256",
            "signer_cert_sha256",
            "device_summary_sha256",
            "evaluation_run_id",
            "route_ids",
            "score_summary_sha256_by_route",
            "process_pss_bytes",
            "process_high_water_bytes",
            "lmk_event_count",
            "thermal",
        },
        "release_summary",
    )
    if obj["schema"] != RELEASE_SCHEMA:
        raise EvidenceError(f"release_summary.schema must be {RELEASE_SCHEMA}")
    require_string(obj["candidate_id"], "release_summary.candidate_id", pattern=IDENTIFIER)
    for key in (
        "corpus_sha256",
        "source_model_sha256",
        "runnable_model_sha256",
        "transformation_manifest_sha256",
        "apk_sha256",
        "signer_cert_sha256",
        "device_summary_sha256",
    ):
        require_string(obj[key], f"release_summary.{key}", pattern=SHA64)
    require_string(obj["evaluation_run_id"], "release_summary.evaluation_run_id", pattern=IDENTIFIER)
    route_ids = unique_strings(obj["route_ids"], "release_summary.route_ids")
    if route_ids != ["en-zh", "ja-zh"]:
        raise EvidenceError("release_summary.route_ids must be ordered as en-zh, ja-zh")
    score_hashes = require_object(
        obj["score_summary_sha256_by_route"],
        {"en-zh", "ja-zh"},
        "release_summary.score_summary_sha256_by_route",
    )
    for route_id, digest in score_hashes.items():
        require_string(
            digest,
            f"release_summary.score_summary_sha256_by_route.{route_id}",
            pattern=SHA64,
        )
    pss = require_int(obj["process_pss_bytes"], "release_summary.process_pss_bytes", minimum=1)
    hwm = require_int(
        obj["process_high_water_bytes"],
        "release_summary.process_high_water_bytes",
        minimum=1,
    )
    if hwm < pss:
        raise EvidenceError("release_summary HWM must be >= PSS")
    require_int(
        obj["lmk_event_count"],
        "release_summary.lmk_event_count",
        maximum=KOTLIN_INT_MAX,
    )
    thermal = require_object(
        obj["thermal"],
        {"sustained_hot_run_minutes", "sample_interval_seconds", "samples"},
        "release_summary.thermal",
    )
    require_number(thermal["sustained_hot_run_minutes"], "release_summary.thermal.sustained_hot_run_minutes")
    require_number(
        thermal["sample_interval_seconds"],
        "release_summary.thermal.sample_interval_seconds",
        minimum=0.001,
    )
    samples = require_list(thermal["samples"], "release_summary.thermal.samples")
    for index, sample in enumerate(samples):
        require_int(
            sample,
            f"release_summary.thermal.samples[{index}]",
            maximum=KOTLIN_INT_MAX,
        )
    return obj


def run(command: list[str], cwd: Path, *, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def read_gitlink(repo_root: Path, submodule_path: str) -> str:
    output = run(["git", "ls-tree", "HEAD", "--", submodule_path], repo_root).stdout.strip()
    match = re.fullmatch(rf"160000 commit ([0-9a-f]{{40}})\t{re.escape(submodule_path)}", output)
    if match is None:
        raise EvidenceError(f"{submodule_path} is not one canonical gitlink in HEAD")
    return match.group(1)


def checked_out_submodule_commit(repo_root: Path, submodule_path: str) -> str | None:
    path = repo_root / submodule_path
    if not path.is_dir():
        return None
    result = run(["git", "rev-parse", "HEAD"], path, check=False)
    value = result.stdout.strip()
    return value if result.returncode == 0 and SHA40.fullmatch(value) else None


def merge_is_ancestor(repo_root: Path, submodule_path: str, merge_commit: str | None, runtime: str) -> bool | None:
    if merge_commit is None:
        return None
    path = repo_root / submodule_path
    present = run(["git", "cat-file", "-e", f"{merge_commit}^{{commit}}"], path, check=False)
    if present.returncode != 0:
        return False
    result = run(["git", "merge-base", "--is-ancestor", merge_commit, runtime], path, check=False)
    if result.returncode == 0:
        return True
    if result.returncode == 1:
        return False
    raise EvidenceError(f"git merge-base failed: {result.stderr.strip()}")


def fetch_pr(repository: str, number: int) -> dict[str, Any]:
    url = f"https://api.github.com/repos/{repository}/pulls/{number}"
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "ScreenTranslation-admission-verifier",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except Exception as error:  # urllib exposes several transport subclasses.
        raise EvidenceError(f"cannot read canonical GitHub PR {url}: {error}") from error


def validate_pr_payload(value: Any, repository: str, number: int) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvidenceError("GitHub PR response must be an object")
    expected_url = f"https://github.com/{repository}/pull/{number}"
    if value.get("number") != number:
        raise EvidenceError("GitHub PR number does not match the canonical PR")
    if value.get("html_url") != expected_url:
        raise EvidenceError("GitHub PR URL does not match the canonical upstream")
    base_repo = value.get("base", {}).get("repo", {}).get("full_name")
    if base_repo != repository:
        raise EvidenceError("GitHub PR base repository does not match the canonical upstream")
    state = value.get("state")
    if state not in {"open", "closed"}:
        raise EvidenceError("GitHub PR state must be open or closed")
    merged = require_bool(value.get("merged"), "GitHub PR merged")
    head = require_string(value.get("head", {}).get("sha"), "GitHub PR head.sha", pattern=SHA40)
    merge_commit = value.get("merge_commit_sha") if merged else None
    if merged:
        merge_commit = require_string(merge_commit, "GitHub PR merge_commit_sha", pattern=SHA40)
        if value.get("merged_at") is None:
            raise EvidenceError("merged GitHub PR is missing merged_at")
    updated_at = require_string(value.get("updated_at"), "GitHub PR updated_at")
    return {
        "state": "MERGED" if merged else ("OPEN" if state == "open" else "CLOSED_UNMERGED"),
        "merged": merged,
        "head_commit": head,
        "merge_commit": merge_commit,
        "updated_at": updated_at,
        "html_url": expected_url,
    }


def env_file(environment_name: str, repo_root: Path) -> Path | None:
    raw = os.environ.get(environment_name)
    if not raw:
        return None
    path = Path(raw)
    if not path.is_absolute():
        path = repo_root / path
    if not path.is_file():
        raise EvidenceError(f"{environment_name} points to a missing file: {path}")
    return path.resolve()


def artifact_observation(source: dict[str, Any], repo_root: Path) -> dict[str, Any]:
    path = env_file(source["path_environment"], repo_root)
    actual = sha256_file(path) if path else None
    expected = source["expected_sha256"]
    size = path.stat().st_size if path else None
    if size is not None and size <= 0:
        raise EvidenceError(f"{source['path_environment']} points to an empty artifact")
    size_matches = True
    if "expected_size_bytes" in source:
        size_matches = size == source["expected_size_bytes"]
    result = {
        "expected_sha256": expected,
        "actual_sha256": actual,
        "actual_size_bytes": size,
        "verified": actual == expected and size_matches,
    }
    if "revision" in source:
        result["revision"] = source["revision"]
        result["expected_size_bytes"] = source["expected_size_bytes"]
    if "transformation_id" in source:
        result["transformation_id"] = source["transformation_id"]
    return result


def inspect_corpus(
    source: dict[str, Any],
    repo_root: Path,
) -> tuple[dict[str, Any], dict[str, list[str]]]:
    path = (repo_root / source["path"]).resolve()
    if not path.is_file() or repo_root.resolve() not in path.parents:
        raise EvidenceError("corpus path is missing or outside the repository")
    actual_sha = sha256_file(path)
    document = load_json(path)
    if not isinstance(document, dict) or not isinstance(document.get("suites"), list):
        raise EvidenceError("corpus JSON is missing suites")
    suite_ids = []
    critical_ids_by_suite: dict[str, list[str]] = {}
    for index, suite in enumerate(document["suites"]):
        if not isinstance(suite, dict):
            raise EvidenceError(f"corpus suite {index} is not an object")
        suite_id = require_string(
            suite.get("id"),
            f"corpus.suites[{index}].id",
            pattern=IDENTIFIER,
        )
        suite_ids.append(suite_id)
        critical_ids: list[str] = []
        for case_index, case in enumerate(require_list(suite.get("cases"), f"corpus.{suite_id}.cases")):
            if not isinstance(case, dict):
                raise EvidenceError(f"corpus.{suite_id}.cases[{case_index}] must be an object")
            case_id = require_string(
                case.get("id"),
                f"corpus.{suite_id}.cases[{case_index}].id",
                pattern=IDENTIFIER,
            )
            for check_index, check in enumerate(
                require_list(
                    case.get("critical_checks"),
                    f"corpus.{suite_id}.{case_id}.critical_checks",
                ),
            ):
                if not isinstance(check, dict):
                    raise EvidenceError(
                        f"corpus.{suite_id}.{case_id}.critical_checks[{check_index}] "
                        "must be an object"
                    )
                require_string(
                    check.get("name"),
                    f"corpus.{suite_id}.{case_id}.critical_checks[{check_index}].name",
                )
                critical_ids.append(f"{case_id}.critical.{check_index + 1}")
        if len(critical_ids) != len(set(critical_ids)):
            raise EvidenceError(f"corpus suite {suite_id} has duplicate derived critical IDs")
        critical_ids_by_suite[suite_id] = critical_ids
    observation = {
        "corpus_id": source["corpus_id"],
        "path": source["path"],
        "expected_sha256": source["expected_sha256"],
        "actual_sha256": actual_sha,
        "expected_suite_ids": source["suite_ids"],
        "actual_suite_ids": suite_ids,
        "verified": actual_sha == source["expected_sha256"] and suite_ids == source["suite_ids"],
    }
    return observation, critical_ids_by_suite


def find_android_tool(name: str) -> Path | None:
    found = shutil.which(name)
    if found:
        return Path(found)
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home:
        return None
    root = Path(android_home)
    if name == "apkanalyzer":
        candidate = root / "cmdline-tools" / "latest" / "bin" / ("apkanalyzer.bat" if os.name == "nt" else "apkanalyzer")
        return candidate if candidate.is_file() else None
    candidates = sorted((root / "build-tools").glob(f"*/{name}{'.bat' if os.name == 'nt' else ''}"), reverse=True)
    return candidates[0] if candidates else None


def inspect_apk(source: dict[str, Any], repo_root: Path) -> dict[str, Any]:
    path = env_file(source["path_environment"], repo_root)
    actual_sha = sha256_file(path) if path else None
    application_id: str | None = None
    signer_sha: str | None = None
    if path:
        apkanalyzer = find_android_tool("apkanalyzer")
        apksigner = find_android_tool("apksigner")
        if apkanalyzer:
            result = run([str(apkanalyzer), "manifest", "application-id", str(path)], repo_root, check=False)
            if result.returncode == 0 and result.stdout.strip():
                application_id = result.stdout.strip()
        if apksigner:
            result = run([str(apksigner), "verify", "--print-certs", str(path)], repo_root, check=False)
            match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", result.stdout)
            if result.returncode == 0 and match:
                signer_sha = match.group(1).lower()
    expected_sha = source["expected_sha256"]
    expected_signer = source["expected_signer_cert_sha256"]
    return {
        "edition": source["edition"],
        "expected_application_id": source["expected_application_id"],
        "actual_application_id": application_id,
        "expected_sha256": expected_sha,
        "actual_sha256": actual_sha,
        "expected_signer_cert_sha256": expected_signer,
        "actual_signer_cert_sha256": signer_sha,
        "apk_verified": (
            expected_sha is not None
            and actual_sha == expected_sha
            and application_id == source["expected_application_id"]
        ),
        "signer_verified": expected_signer is not None and signer_sha == expected_signer,
    }


def inspect_summary(
    source: dict[str, Any],
    repo_root: Path,
    validator: Any,
    *validator_args: Any,
) -> tuple[dict[str, Any] | None, str | None, bool]:
    path = env_file(source["path_environment"], repo_root)
    if path is None:
        return None, None, False
    digest = sha256_file(path)
    parsed = validator(load_json(path), *validator_args)
    expected = source["expected_sha256"]
    return parsed, digest, expected is not None and digest == expected


def score_bindings_match(
    summary: dict[str, Any],
    *,
    candidate_id: str,
    corpus_sha: str | None,
    source_sha: str | None,
    runnable_sha: str | None,
    manifest_sha: str | None,
    apk_sha: str | None,
    signer_sha: str | None,
    device_sha: str | None,
) -> bool:
    return (
        summary["candidate_id"] == candidate_id
        and summary["corpus_sha256"] == corpus_sha
        and summary["source_model_sha256"] == source_sha
        and summary["runnable_model_sha256"] == runnable_sha
        and summary["transformation_manifest_sha256"] == manifest_sha
        and summary["apk_sha256"] == apk_sha
        and summary["signer_cert_sha256"] == signer_sha
        and summary["device_summary_sha256"] == device_sha
    )


def release_bindings_match(
    summary: dict[str, Any],
    *,
    score_summary_sha256_by_route: dict[str, str | None],
    score_run_ids: list[str | None],
    **bindings: Any,
) -> bool:
    if not score_bindings_match(summary, **bindings):
        return False
    if any(value is None for value in score_summary_sha256_by_route.values()):
        return False
    if summary["score_summary_sha256_by_route"] != score_summary_sha256_by_route:
        return False
    return bool(
        score_run_ids
        and all(run_id == summary["evaluation_run_id"] for run_id in score_run_ids)
    )


POLICY = {
    "required_route_ids": ["en-zh", "ja-zh"],
    "minimum_q4_bleu_retention_percent": 95.0,
    "maximum_critical_regressions": 0,
    "maximum_raw_median_latency_ms_exclusive": 350.0,
    "maximum_pipeline_median_latency_ms_exclusive": 750.0,
    "maximum_pipeline_p95_latency_ms_exclusive": 1500.0,
    "maximum_pipeline_timeout_count": 0,
    "maximum_process_pss_bytes_exclusive": 1_073_741_824,
    "maximum_process_high_water_bytes_exclusive": 1_288_490_189,
    "maximum_lmk_event_count": 0,
    "minimum_sustained_hot_run_minutes": 30.0,
    "minimum_thermal_sample_count": 30,
    "maximum_thermal_sample_interval_seconds": 60.0,
    "maximum_thermal_status": 1,
}


FAILURE_ORDER = [
    "RUNTIME_SUPPORT_NOT_MERGED_AND_PINNED",
    "CORPUS_ARTIFACT_NOT_VERIFIED",
    "SOURCE_MODEL_ARTIFACT_NOT_VERIFIED",
    "RUNNABLE_MODEL_ARTIFACT_NOT_VERIFIED",
    "TRANSFORMATION_MANIFEST_NOT_VERIFIED",
    "APK_ARTIFACT_NOT_VERIFIED",
    "SIGNER_NOT_VERIFIED",
    "DEVICE_ROM_EVIDENCE_NOT_VERIFIED",
    "REQUIRED_ROUTE_MISSING",
    "DUPLICATE_ROUTE_MEASUREMENT",
    "UNEXPECTED_ROUTE_MEASUREMENT",
    "SCORE_ARTIFACT_NOT_VERIFIED",
    "QUALITY_MEASUREMENT_MISSING",
    "QUALITY_RETENTION_BELOW_THRESHOLD",
    "CRITICAL_CHECK_IDS_MISSING",
    "CRITICAL_CHECK_REGRESSION",
    "RAW_LATENCY_MEASUREMENT_MISSING",
    "RAW_MEDIAN_LATENCY_ABOVE_THRESHOLD",
    "APP_PIPELINE_MEASUREMENT_MISSING",
    "APP_PIPELINE_MEDIAN_LATENCY_ABOVE_THRESHOLD",
    "APP_PIPELINE_P95_LATENCY_ABOVE_THRESHOLD",
    "APP_PIPELINE_TIMEOUTS_ABOVE_THRESHOLD",
    "INTEGRATED_RELEASE_MEASUREMENT_MISSING",
    "PROCESS_PSS_MEASUREMENT_MISSING",
    "PROCESS_PSS_ABOVE_THRESHOLD",
    "HIGH_WATER_MEMORY_MEASUREMENT_MISSING",
    "HIGH_WATER_MEMORY_ABOVE_THRESHOLD",
    "LMK_MEASUREMENT_MISSING",
    "LMK_EVENTS_ABOVE_THRESHOLD",
    "HOT_RUN_MEASUREMENT_MISSING",
    "HOT_RUN_TOO_SHORT",
    "THERMAL_CADENCE_MISSING",
    "THERMAL_CADENCE_ABOVE_THRESHOLD",
    "THERMAL_SAMPLING_INSUFFICIENT",
    "THERMAL_STATUS_ABOVE_THRESHOLD",
]


def evaluate_canonical(record: dict[str, Any]) -> list[str]:
    found: set[str] = set()
    runtime = record["runtime_gate"]
    bindings = record["bindings"]
    if not runtime["satisfied"]:
        found.add("RUNTIME_SUPPORT_NOT_MERGED_AND_PINNED")
    if not bindings["corpus"]["verified"]:
        found.add("CORPUS_ARTIFACT_NOT_VERIFIED")
    if not bindings["candidate"]["source_model"]["verified"]:
        found.add("SOURCE_MODEL_ARTIFACT_NOT_VERIFIED")
    if not bindings["candidate"]["runnable_model"]["verified"]:
        found.add("RUNNABLE_MODEL_ARTIFACT_NOT_VERIFIED")
    if not bindings["candidate"]["transformation_manifest"]["verified"]:
        found.add("TRANSFORMATION_MANIFEST_NOT_VERIFIED")
    if not bindings["apk"]["apk_verified"]:
        found.add("APK_ARTIFACT_NOT_VERIFIED")
    if not bindings["apk"]["signer_verified"]:
        found.add("SIGNER_NOT_VERIFIED")
    if not bindings["device"]["verified"]:
        found.add("DEVICE_ROM_EVIDENCE_NOT_VERIFIED")

    routes = record["routes"]
    route_ids = [route["route_id"] for route in routes]
    required = POLICY["required_route_ids"]
    if any(route_id not in route_ids for route_id in required):
        found.add("REQUIRED_ROUTE_MISSING")
    if len(route_ids) != len(set(route_ids)):
        found.add("DUPLICATE_ROUTE_MEASUREMENT")
    if any(route_id not in required for route_id in route_ids):
        found.add("UNEXPECTED_ROUTE_MEASUREMENT")
    for route in routes:
        if not route["score_verified"]:
            found.add("SCORE_ARTIFACT_NOT_VERIFIED")
        quality = route["q4_bleu_retention_percent"]
        if quality is None:
            found.add("QUALITY_MEASUREMENT_MISSING")
        elif quality < POLICY["minimum_q4_bleu_retention_percent"]:
            found.add("QUALITY_RETENTION_BELOW_THRESHOLD")
        evaluated = route["critical_evaluated_ids"]
        regressed = route["critical_regressed_ids"]
        if evaluated is None or regressed is None:
            found.add("CRITICAL_CHECK_IDS_MISSING")
        elif len(regressed) > POLICY["maximum_critical_regressions"]:
            found.add("CRITICAL_CHECK_REGRESSION")
        raw = route["raw_median_latency_ms"]
        if raw is None:
            found.add("RAW_LATENCY_MEASUREMENT_MISSING")
        elif raw >= POLICY["maximum_raw_median_latency_ms_exclusive"]:
            found.add("RAW_MEDIAN_LATENCY_ABOVE_THRESHOLD")
        pipeline = route["pipeline"]
        if any(pipeline[key] is None for key in ("median_latency_ms", "p95_latency_ms", "timeout_count")):
            found.add("APP_PIPELINE_MEASUREMENT_MISSING")
        else:
            if pipeline["median_latency_ms"] >= POLICY["maximum_pipeline_median_latency_ms_exclusive"]:
                found.add("APP_PIPELINE_MEDIAN_LATENCY_ABOVE_THRESHOLD")
            if pipeline["p95_latency_ms"] >= POLICY["maximum_pipeline_p95_latency_ms_exclusive"]:
                found.add("APP_PIPELINE_P95_LATENCY_ABOVE_THRESHOLD")
            if pipeline["timeout_count"] > POLICY["maximum_pipeline_timeout_count"]:
                found.add("APP_PIPELINE_TIMEOUTS_ABOVE_THRESHOLD")

    integrated = record["integrated_release"]
    if not integrated["summary_verified"]:
        found.add("INTEGRATED_RELEASE_MEASUREMENT_MISSING")
    pss = integrated["process_pss_bytes"]
    if pss is None:
        found.add("PROCESS_PSS_MEASUREMENT_MISSING")
    elif pss >= POLICY["maximum_process_pss_bytes_exclusive"]:
        found.add("PROCESS_PSS_ABOVE_THRESHOLD")
    hwm = integrated["process_high_water_bytes"]
    if hwm is None:
        found.add("HIGH_WATER_MEMORY_MEASUREMENT_MISSING")
    elif hwm >= POLICY["maximum_process_high_water_bytes_exclusive"]:
        found.add("HIGH_WATER_MEMORY_ABOVE_THRESHOLD")
    lmk = integrated["lmk_event_count"]
    if lmk is None:
        found.add("LMK_MEASUREMENT_MISSING")
    elif lmk > POLICY["maximum_lmk_event_count"]:
        found.add("LMK_EVENTS_ABOVE_THRESHOLD")
    thermal = integrated["thermal"]
    hot = thermal["sustained_hot_run_minutes"]
    if hot is None:
        found.add("HOT_RUN_MEASUREMENT_MISSING")
    elif hot < POLICY["minimum_sustained_hot_run_minutes"]:
        found.add("HOT_RUN_TOO_SHORT")
    interval = thermal["sample_interval_seconds"]
    samples = thermal["samples"]
    if interval is None:
        found.add("THERMAL_CADENCE_MISSING")
    elif interval > POLICY["maximum_thermal_sample_interval_seconds"]:
        found.add("THERMAL_CADENCE_ABOVE_THRESHOLD")
    if samples is None or len(samples) < POLICY["minimum_thermal_sample_count"]:
        found.add("THERMAL_SAMPLING_INSUFFICIENT")
    elif max(samples) > POLICY["maximum_thermal_status"]:
        found.add("THERMAL_STATUS_ABOVE_THRESHOLD")
    return [failure for failure in FAILURE_ORDER if failure in found]


def build_canonical(
    source: dict[str, Any],
    *,
    repo_root: Path,
    source_path: Path,
    pr_payload: dict[str, Any],
) -> dict[str, Any]:
    runtime_source = source["runtime"]
    artifacts = source["artifacts"]
    gitlink = read_gitlink(repo_root, runtime_source["submodule_path"])
    checked_out = checked_out_submodule_commit(repo_root, runtime_source["submodule_path"])
    pr = validate_pr_payload(
        pr_payload,
        runtime_source["upstream_repository"],
        runtime_source["pull_request_number"],
    )
    ancestor = merge_is_ancestor(
        repo_root,
        runtime_source["submodule_path"],
        pr["merge_commit"],
        gitlink,
    )
    source_model = artifact_observation(artifacts["source_model"], repo_root)
    runnable = artifact_observation(artifacts["runnable_model"], repo_root)
    manifest = artifact_observation(artifacts["transformation_manifest"], repo_root)
    corpus, critical_ids_by_suite = inspect_corpus(artifacts["corpus"], repo_root)
    apk = inspect_apk(artifacts["apk"], repo_root)

    device_summary, device_sha, device_pin_matches = inspect_summary(
        artifacts["device_summary"],
        repo_root,
        validate_device_summary,
        source["declared_device"],
    )
    device_verified = device_summary is not None and device_pin_matches

    common_bindings = {
        "candidate_id": source["candidate_id"],
        "corpus_sha": corpus["actual_sha256"] if corpus["verified"] else None,
        "source_sha": source_model["actual_sha256"] if source_model["verified"] else None,
        "runnable_sha": runnable["actual_sha256"] if runnable["verified"] else None,
        "manifest_sha": manifest["actual_sha256"] if manifest["verified"] else None,
        "apk_sha": apk["actual_sha256"] if apk["apk_verified"] else None,
        "signer_sha": apk["actual_signer_cert_sha256"] if apk["signer_verified"] else None,
        "device_sha": device_sha if device_verified else None,
    }

    score_observations: list[dict[str, Any]] = []
    for route_source in source["routes"]:
        expected_critical_ids = critical_ids_by_suite.get(route_source["corpus_suite_id"])
        if expected_critical_ids is None:
            raise EvidenceError("route corpus suite is missing from the canonical corpus")
        if len(expected_critical_ids) != route_source["expected_critical_check_count"]:
            raise EvidenceError(
                "source route critical count differs from IDs derived from the canonical corpus"
            )
        score_source = {
            "expected_sha256": route_source["expected_score_summary_sha256"],
            "path_environment": route_source["score_summary_environment"],
        }
        score, score_sha, pin_matches = inspect_summary(
            score_source,
            repo_root,
            validate_score_summary,
            route_source,
            expected_critical_ids,
        )
        score_base_verified = bool(
            score is not None
            and pin_matches
            and score_bindings_match(score, **common_bindings)
        )
        score_observations.append({
            "source": route_source,
            "expected_critical_ids": expected_critical_ids,
            "summary": score,
            "actual_sha256": score_sha,
            "base_verified": score_base_verified,
        })

    release, release_sha, release_pin_matches = inspect_summary(
        artifacts["release_summary"],
        repo_root,
        validate_release_summary,
    )
    release_verified = bool(
        release is not None
        and release_pin_matches
        and release_bindings_match(
            release,
            score_summary_sha256_by_route={
                observation["source"]["route_id"]: (
                    observation["actual_sha256"] if observation["base_verified"] else None
                )
                for observation in score_observations
            },
            score_run_ids=[
                observation["summary"]["evaluation_run_id"]
                if observation["base_verified"] else None
                for observation in score_observations
            ],
            **common_bindings,
        )
    )
    route_records = []
    for observation in score_observations:
        route_source = observation["source"]
        score = observation["summary"]
        score_verified = bool(observation["base_verified"] and release_verified)
        route_records.append({
            "route_id": route_source["route_id"],
            "corpus_suite_id": route_source["corpus_suite_id"],
            "expected_critical_check_count": route_source["expected_critical_check_count"],
            "expected_critical_check_ids": observation["expected_critical_ids"],
            "historical_artifacts": {
                "raw_result_sha256": route_source["historical_raw_result_sha256"],
                "score_sha256": route_source["historical_score_artifact_sha256"],
            },
            "expected_score_summary_sha256": route_source["expected_score_summary_sha256"],
            "actual_score_summary_sha256": observation["actual_sha256"],
            "evaluation_run_id": score["evaluation_run_id"] if score_verified else None,
            "score_verified": score_verified,
            "q4_bleu_retention_percent": score["q4_bleu_retention_percent"] if score_verified else None,
            "critical_evaluated_ids": score["critical_evaluated_ids"] if score_verified else None,
            "critical_regressed_ids": score["critical_regressed_ids"] if score_verified else None,
            "raw_median_latency_ms": score["raw_median_latency_ms"] if score_verified else None,
            "pipeline": {
                "median_latency_ms": score["pipeline"]["median_latency_ms"] if score_verified else None,
                "p95_latency_ms": score["pipeline"]["p95_latency_ms"] if score_verified else None,
                "timeout_count": score["pipeline"]["timeout_count"] if score_verified else None,
            },
        })
    integrated = {
        "expected_summary_sha256": artifacts["release_summary"]["expected_sha256"],
        "actual_summary_sha256": release_sha,
        "summary_verified": release_verified,
        "evaluation_run_id": release["evaluation_run_id"] if release_verified else None,
        "score_summary_sha256_by_route": (
            release["score_summary_sha256_by_route"] if release_verified else None
        ),
        "process_pss_bytes": release["process_pss_bytes"] if release_verified else None,
        "process_high_water_bytes": release["process_high_water_bytes"] if release_verified else None,
        "lmk_event_count": release["lmk_event_count"] if release_verified else None,
        "thermal": {
            "sustained_hot_run_minutes": release["thermal"]["sustained_hot_run_minutes"] if release_verified else None,
            "sample_interval_seconds": release["thermal"]["sample_interval_seconds"] if release_verified else None,
            "samples": release["thermal"]["samples"] if release_verified else None,
        },
    }

    runtime_satisfied = bool(
        pr["state"] == "MERGED"
        and pr["merge_commit"] is not None
        and pr["head_commit"] == runtime_source["expected_pull_request_head"]
        and gitlink == runtime_source["declared_runtime_commit"]
        and checked_out == gitlink
        and ancestor is True
        and runnable["verified"]
        and manifest["verified"]
    )
    script_path = Path(__file__).resolve()
    record: dict[str, Any] = {
        "schema": CANONICAL_SCHEMA,
        "candidate_id": source["candidate_id"],
        "recorded_date": source["recorded_date"],
        "source_record": {
            "path": source_path.relative_to(repo_root).as_posix(),
            "sha256": sha256_file(source_path),
        },
        "verifier": {
            "path": script_path.relative_to(repo_root).as_posix(),
            "sha256": sha256_file(script_path),
        },
        "runtime_gate": {
            "upstream_repository": runtime_source["upstream_repository"],
            "pull_request_number": runtime_source["pull_request_number"],
            "pull_request_url": pr["html_url"],
            "pull_request_state": pr["state"],
            "pull_request_updated_at": pr["updated_at"],
            "expected_pull_request_head": runtime_source["expected_pull_request_head"],
            "observed_pull_request_head": pr["head_commit"],
            "merge_commit": pr["merge_commit"],
            "submodule_path": runtime_source["submodule_path"],
            "declared_runtime_commit": runtime_source["declared_runtime_commit"],
            "repository_gitlink_commit": gitlink,
            "checked_out_submodule_commit": checked_out,
            "merge_ancestor_of_runtime": ancestor,
            "runnable_model_expected_sha256": runnable["expected_sha256"],
            "runnable_model_actual_sha256": runnable["actual_sha256"],
            "runnable_model_verified": runnable["verified"],
            "manifest_expected_sha256": manifest["expected_sha256"],
            "manifest_actual_sha256": manifest["actual_sha256"],
            "manifest_verified": manifest["verified"],
            "satisfied": runtime_satisfied,
        },
        "bindings": {
            "corpus": corpus,
            "candidate": {
                "source_model": source_model,
                "runnable_model": runnable,
                "transformation_manifest": manifest,
            },
            "apk": apk,
            "device": {
                "declared": source["declared_device"],
                "expected_summary_sha256": artifacts["device_summary"]["expected_sha256"],
                "actual_summary_sha256": device_sha,
                "verified": device_verified,
            },
        },
        "routes": route_records,
        "integrated_release": integrated,
        "policy": POLICY,
    }
    failures = evaluate_canonical(record)
    record["evaluation"] = {"failures": failures, "satisfied": not failures}
    return record


def kotlin_source(json_text: str, digest: str) -> str:
    if '"""' in json_text or "$" in json_text:
        raise EvidenceError("canonical JSON contains a sequence unsafe for Kotlin raw strings")
    return (
        "// Generated by tools/provider-admission/verify_translation_admission.py.\n"
        "// Do not hand-edit; CI regenerates and compares this source.\n"
        "package com.screentranslation.app.ml\n\n"
        "internal object GeneratedTranslationAdmissionEvidence {\n"
        f"    const val SHA256 = \"{digest}\"\n"
        "    val JSON: String = \"\"\""
        f"{json_text}"
        "\"\"\"\n"
        "}\n"
    )


def pin_text(output_path: Path, digest: str) -> str:
    return f"{digest}  {output_path.name}\n"


def compare(path: Path, expected: bytes) -> None:
    if not path.is_file():
        raise EvidenceError(f"missing generated artifact: {path}")
    actual = path.read_bytes()
    if actual != expected:
        raise EvidenceError(
            f"generated artifact is stale: {path}; run "
            "python tools/provider-admission/verify_translation_admission.py --write"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--source", type=Path, default=Path(DEFAULT_SOURCE))
    parser.add_argument("--output", type=Path, default=Path(DEFAULT_OUTPUT))
    parser.add_argument("--pin", type=Path, default=Path(DEFAULT_PIN))
    parser.add_argument("--kotlin-output", type=Path, default=Path(DEFAULT_KOTLIN))
    parser.add_argument("--github-pr-json", type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)

    repo_root = args.repo_root.resolve()
    source_path = (repo_root / args.source).resolve() if not args.source.is_absolute() else args.source.resolve()
    output_path = (repo_root / args.output).resolve() if not args.output.is_absolute() else args.output.resolve()
    pin_path = (repo_root / args.pin).resolve() if not args.pin.is_absolute() else args.pin.resolve()
    kotlin_path = (
        (repo_root / args.kotlin_output).resolve()
        if not args.kotlin_output.is_absolute()
        else args.kotlin_output.resolve()
    )
    source = validate_source(load_json(source_path))
    if args.github_pr_json:
        pr_path = (
            (repo_root / args.github_pr_json).resolve()
            if not args.github_pr_json.is_absolute()
            else args.github_pr_json.resolve()
        )
        pr_payload = load_json(pr_path)
    else:
        pr_payload = fetch_pr(
            source["runtime"]["upstream_repository"],
            source["runtime"]["pull_request_number"],
        )
    record = build_canonical(
        source,
        repo_root=repo_root,
        source_path=source_path,
        pr_payload=pr_payload,
    )
    output_bytes = canonical_json_bytes(record)
    digest = sha256_bytes(output_bytes)
    pin_bytes = pin_text(output_path, digest).encode("utf-8")
    kotlin_bytes = kotlin_source(output_bytes.decode("utf-8"), digest).encode("utf-8")

    if args.write:
        for path in (output_path, pin_path, kotlin_path):
            path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(output_bytes)
        pin_path.write_bytes(pin_bytes)
        kotlin_path.write_bytes(kotlin_bytes)
        print(f"wrote {output_path.relative_to(repo_root)} sha256={digest}")
    else:
        compare(output_path, output_bytes)
        compare(pin_path, pin_bytes)
        compare(kotlin_path, kotlin_bytes)
        print(f"verified canonical admission evidence sha256={digest}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except EvidenceError as error:
        print(f"admission evidence error: {error}", file=sys.stderr)
        raise SystemExit(2)
