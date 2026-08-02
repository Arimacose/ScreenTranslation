#!/usr/bin/env python3
"""Run Hy-MT2 through a pinned llama.cpp server on translation fixtures."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import platform
import statistics
import subprocess
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any

try:
    import psutil
except ImportError:  # Memory telemetry is optional; inference does not need it.
    psutil = None


DEFAULT_MODEL_REPO = "tencent/Hy-MT2-1.8B-GGUF"
DEFAULT_MODEL_REVISION = "1cd5208700acedef4ef93019b6cfc148b8522d45"
DEFAULT_MODEL_SHA256 = (
    "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
)
DEFAULT_LLAMA_TAG = "b10181"
DEFAULT_LLAMA_COMMIT = "caa596ab3"
TRAILING_MARKERS = (
    "[end of text]",
    "<|endoftext|>",
    "<｜hy_end▁of▁sentence｜>",
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_prompt(source_text: str, target_language: str = "Chinese") -> str:
    return (
        f"Translate the following text into {target_language}. "
        "Note that you should only output the translated result without any "
        "additional explanation:\n\n"
        f"{source_text}"
    )


def clean_generation(value: str) -> str:
    cleaned = value.strip()
    changed = True
    while changed:
        changed = False
        for marker in TRAILING_MARKERS:
            if cleaned.endswith(marker):
                cleaned = cleaned[: -len(marker)].rstrip()
                changed = True
    return cleaned


def pipeline_parts(case: dict[str, Any]) -> list[str]:
    parts = case.get("translation_pipeline", {}).get("parts")
    if parts:
        return [str(part) for part in parts]
    return [str(case["source_text"])]


def default_runtime_scope(external_server: bool) -> str:
    if external_server:
        return (
            "Android ARM64 standalone llama-server quality and latency "
            "benchmark over an ADB-forwarded loopback connection. "
            "The STQ 1.25-bit runtime remains a separate deployment gate."
        )
    return (
        "Windows x86_64 CPU quality and host-resource benchmark. "
        "Android ARM64 latency, thermal behavior, and the STQ "
        "1.25-bit runtime remain separate deployment gates."
    )


def memory_snapshot(process: subprocess.Popen[bytes]) -> dict[str, Any] | None:
    if psutil is None:
        return None
    info = psutil.Process(process.pid).memory_info()
    system = psutil.virtual_memory()
    return {
        "rss_bytes": info.rss,
        "vms_bytes": info.vms,
        "peak_working_set_bytes": getattr(info, "peak_wset", None),
        "private_bytes": getattr(info, "private", None),
        "system_available_bytes": system.available,
        "system_total_bytes": system.total,
    }


class LlamaServer:
    def __init__(
        self,
        executable: Path | None,
        model: Path,
        host: str,
        port: int,
        threads: int,
        context_size: int,
        log_directory: Path,
        external_base_url: str | None = None,
    ) -> None:
        self.executable = executable.resolve() if executable is not None else None
        self.model = model.resolve()
        self.host = host
        self.port = port
        self.threads = threads
        self.context_size = context_size
        self.log_directory = log_directory.resolve()
        self.external_base_url = (
            external_base_url.rstrip("/") if external_base_url else None
        )
        self.process: subprocess.Popen[bytes] | None = None
        self._stdout_file: Any = None
        self._stderr_file: Any = None
        self.initialization_ms = 0.0

    @property
    def base_url(self) -> str:
        if self.external_base_url is not None:
            return self.external_base_url
        return f"http://{self.host}:{self.port}"

    @property
    def stdout_path(self) -> Path:
        return self.log_directory / "llama-server.stdout.log"

    @property
    def stderr_path(self) -> Path:
        return self.log_directory / "llama-server.stderr.log"

    def start(self, timeout_seconds: float = 120.0) -> None:
        started = time.perf_counter_ns()
        if self.external_base_url is None:
            if self.executable is None:
                raise RuntimeError("local server executable was not provided")
            self.log_directory.mkdir(parents=True, exist_ok=True)
            self._stdout_file = self.stdout_path.open("wb")
            self._stderr_file = self.stderr_path.open("wb")
            command = [
                str(self.executable),
                "--model",
                str(self.model),
                "--host",
                self.host,
                "--port",
                str(self.port),
                "--ctx-size",
                str(self.context_size),
                "--parallel",
                "1",
                "--threads",
                str(self.threads),
                "--threads-batch",
                str(self.threads),
                "--gpu-layers",
                "0",
                "--jinja",
                "--no-webui",
                "--metrics",
            ]
            self.process = subprocess.Popen(
                command,
                stdin=subprocess.DEVNULL,
                stdout=self._stdout_file,
                stderr=self._stderr_file,
            )
        deadline = time.monotonic() + timeout_seconds
        last_error: Exception | None = None
        while time.monotonic() < deadline:
            if self.process is not None and self.process.poll() is not None:
                raise RuntimeError(
                    f"llama-server exited with {self.process.returncode}; "
                    f"inspect {self.stderr_path}"
                )
            try:
                with urllib.request.urlopen(
                    f"{self.base_url}/health",
                    timeout=1.0,
                ) as response:
                    payload = json.loads(response.read().decode("utf-8"))
                    if response.status == 200 and payload.get("status") == "ok":
                        self.initialization_ms = (
                            time.perf_counter_ns() - started
                        ) / 1_000_000
                        return
            except (OSError, ValueError, urllib.error.URLError) as error:
                last_error = error
            time.sleep(0.25)
        raise TimeoutError(
            f"llama-server did not become healthy: {last_error}; "
            f"inspect {self.stderr_path}"
        )

    def translate(
        self,
        source_text: str,
        *,
        target_language: str,
        temperature: float,
        top_k: int,
        top_p: float,
        repeat_penalty: float,
        seed: int,
        max_tokens: int,
        timeout_seconds: float,
    ) -> tuple[str, float, dict[str, Any]]:
        payload = {
            "model": self.model.stem,
            "messages": [
                {
                    "role": "user",
                    "content": build_prompt(source_text, target_language),
                }
            ],
            "temperature": temperature,
            "top_k": top_k,
            "top_p": top_p,
            "repeat_penalty": repeat_penalty,
            "seed": seed,
            "max_tokens": max_tokens,
            "stream": False,
        }
        request_body = json.dumps(
            payload,
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}/v1/chat/completions",
            data=request_body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        started = time.perf_counter_ns()
        try:
            with urllib.request.urlopen(
                request,
                timeout=timeout_seconds,
            ) as response:
                response_body = response.read()
                result = json.loads(response_body.decode("utf-8"))
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            raise RuntimeError(f"llama-server HTTP {error.code}: {body}") from error
        latency_ms = (time.perf_counter_ns() - started) / 1_000_000
        output = clean_generation(result["choices"][0]["message"]["content"])
        return output, latency_ms, {
            "usage": result.get("usage"),
            "timings": result.get("timings"),
            "network_body_bytes": {
                "request": len(request_body),
                "response": len(response_body),
                "scope": (
                    "HTTP bodies only; headers, TLS, and transport "
                    "overhead excluded"
                ),
            },
        }

    def close(self) -> None:
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        if self._stdout_file is not None:
            self._stdout_file.close()
        if self._stderr_file is not None:
            self._stderr_file.close()


def translate_parts(
    server: LlamaServer,
    parts: list[str],
    settings: dict[str, Any],
) -> tuple[list[str], float, list[dict[str, Any]]]:
    outputs: list[str] = []
    details: list[dict[str, Any]] = []
    total_latency_ms = 0.0
    for part in parts:
        output, latency_ms, request_details = server.translate(part, **settings)
        outputs.append(output)
        total_latency_ms += latency_ms
        details.append(request_details)
    return outputs, total_latency_ms, details


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline_json", type=Path)
    parser.add_argument("--server-executable", type=Path)
    parser.add_argument(
        "--external-server-url",
        help="Use an already running llama-server instead of starting one.",
    )
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--model-repo", default=DEFAULT_MODEL_REPO)
    parser.add_argument("--model-revision", default=DEFAULT_MODEL_REVISION)
    parser.add_argument("--expected-model-sha256", default=DEFAULT_MODEL_SHA256)
    parser.add_argument(
        "--quantization",
        default="Q4_K_M",
        help="Quantization label recorded in result metadata.",
    )
    parser.add_argument("--llama-tag", default=DEFAULT_LLAMA_TAG)
    parser.add_argument("--llama-commit", default=DEFAULT_LLAMA_COMMIT)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18082)
    parser.add_argument("--threads", type=int, default=16)
    parser.add_argument("--context-size", type=int, default=2048)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--temperature", type=float, default=0.0)
    parser.add_argument("--top-k", type=int, default=1)
    parser.add_argument("--top-p", type=float, default=1.0)
    parser.add_argument("--repeat-penalty", type=float, default=1.05)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--max-tokens", type=int, default=256)
    parser.add_argument("--request-timeout", type=float, default=300.0)
    parser.add_argument("--target-language-name", default="Chinese")
    parser.add_argument(
        "--runtime-scope",
        default="",
        help=(
            "Override the recorded runtime scope, for example when an "
            "external server is a desktop GPU rather than Android."
        ),
    )
    parser.add_argument("--log-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.repetitions < 1:
        parser.error("--repetitions must be at least 1")
    if args.external_server_url is None:
        if args.server_executable is None or not args.server_executable.is_file():
            parser.error(
                "--server-executable must point to llama-server when "
                "--external-server-url is absent"
            )
    elif args.server_executable is not None:
        parser.error(
            "choose either --server-executable or --external-server-url"
        )
    if not args.model.is_file():
        parser.error(f"missing model: {args.model}")

    model_sha256 = sha256_file(args.model)
    if (
        args.expected_model_sha256
        and model_sha256.lower() != args.expected_model_sha256.lower()
    ):
        parser.error(
            f"model SHA-256 is {model_sha256}, expected "
            f"{args.expected_model_sha256}"
        )

    baseline = json.loads(args.baseline_json.read_text(encoding="utf-8"))
    settings = {
        "target_language": args.target_language_name,
        "temperature": args.temperature,
        "top_k": args.top_k,
        "top_p": args.top_p,
        "repeat_penalty": args.repeat_penalty,
        "seed": args.seed,
        "max_tokens": args.max_tokens,
        "timeout_seconds": args.request_timeout,
    }

    output_cases: list[dict[str, Any]] = []
    server = LlamaServer(
        executable=args.server_executable,
        model=args.model,
        host=args.host,
        port=args.port,
        threads=args.threads,
        context_size=args.context_size,
        log_directory=args.log_directory,
        external_base_url=args.external_server_url,
    )
    try:
        server.start()
        process = server.process
        memory_after_load = (
            memory_snapshot(process) if process is not None else None
        )
        _, warmup_ms, warmup_details = server.translate(
            "Warm-up sentence.",
            **settings,
        )
        memory_after_warmup = (
            memory_snapshot(process) if process is not None else None
        )

        for index, baseline_case in enumerate(baseline["cases"], start=1):
            source = str(baseline_case["source_text"])
            raw_latencies: list[float] = []
            raw_details: list[dict[str, Any]] = []
            raw_repetition_outputs: list[str] = []
            raw_output = ""
            for _ in range(args.repetitions):
                raw_output, latency_ms, details = server.translate(
                    source,
                    **settings,
                )
                raw_repetition_outputs.append(raw_output)
                raw_latencies.append(latency_ms)
                raw_details.append(details)
            raw_consistent = len(set(raw_repetition_outputs)) == 1
            raw_output = Counter(raw_repetition_outputs).most_common(1)[0][0]
            if not raw_consistent:
                print(
                    f"warning: {baseline_case['id']} raw output changed between "
                    "repetitions",
                    flush=True,
                )

            parts = pipeline_parts(baseline_case)
            pipeline_latencies: list[float] = []
            pipeline_details: list[list[dict[str, Any]]] = []
            pipeline_repetition_outputs: list[list[str]] = []
            part_outputs: list[str] = []
            for _ in range(args.repetitions):
                part_outputs, latency_ms, details = translate_parts(
                    server,
                    parts,
                    settings,
                )
                pipeline_repetition_outputs.append(part_outputs)
                pipeline_latencies.append(latency_ms)
                pipeline_details.append(details)
            pipeline_variants = [
                tuple(outputs) for outputs in pipeline_repetition_outputs
            ]
            pipeline_consistent = len(set(pipeline_variants)) == 1
            part_outputs = list(Counter(pipeline_variants).most_common(1)[0][0])
            if not pipeline_consistent:
                print(
                    f"warning: {baseline_case['id']} pipeline output changed "
                    "between repetitions",
                    flush=True,
                )

            output_case = {
                key: copy.deepcopy(value)
                for key, value in baseline_case.items()
                if key
                not in {
                    "translation_raw",
                    "translation_pipeline",
                    "end_to_end",
                }
            }
            output_case["translation_raw"] = {
                "output_text": raw_output,
                "latencies_ms": raw_latencies,
                "median_latency_ms": statistics.median(raw_latencies),
                "repetition_outputs": raw_repetition_outputs,
                "outputs_consistent_across_repetitions": raw_consistent,
                "request_details": raw_details,
            }
            output_case["translation_pipeline"] = {
                "parts": parts,
                "part_outputs": part_outputs,
                "output_text": " ".join(part_outputs),
                "latencies_ms": pipeline_latencies,
                "median_latency_ms": statistics.median(pipeline_latencies),
                "repetition_outputs": pipeline_repetition_outputs,
                "outputs_consistent_across_repetitions": pipeline_consistent,
                "request_details": pipeline_details,
            }
            output_cases.append(output_case)
            print(
                f"[{index}/{len(baseline['cases'])}] "
                f"{baseline_case['id']}: {raw_output}",
                flush=True,
            )

        memory_after_suite = (
            memory_snapshot(process) if process is not None else None
        )
    finally:
        server.close()

    result: dict[str, Any] = {
        "schema_version": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "device": (
            {
                **copy.deepcopy(baseline["device"]),
                "kind": "android_arm64_standalone",
            }
            if args.external_server_url
            else {
                "kind": "host_preintegration",
                "platform": platform.platform(),
                "processor": platform.processor(),
                "machine": platform.machine(),
            }
        ),
        "engines": {
            "ocr": baseline["engines"]["ocr"],
            "translation": (
                f"{args.model_repo} {args.quantization} / "
                f"llama.cpp {args.llama_tag}"
            ),
            "source_language": baseline["engines"]["source_language"],
            "target_language": baseline["engines"]["target_language"],
        },
        "method": {
            "fixture_suite": baseline.get("method", {}).get("fixture_suite"),
            "model_repo": args.model_repo,
            "model_revision": args.model_revision,
            "model_file": {
                "path": str(args.model.resolve()),
                "bytes": args.model.stat().st_size,
                "sha256": model_sha256,
                "quantization": args.quantization,
            },
            "llama_cpp": {
                "tag": args.llama_tag,
                "commit": args.llama_commit,
                "server_executable": (
                    str(args.server_executable.resolve())
                    if args.server_executable is not None
                    else None
                ),
                "external_server_url": args.external_server_url,
            },
            "prompt_template": build_prompt(
                "{source_text}",
                args.target_language_name,
            ),
            "decoding": {
                "temperature": args.temperature,
                "top_k": args.top_k,
                "top_p": args.top_p,
                "repeat_penalty": args.repeat_penalty,
                "seed": args.seed,
                "max_tokens": args.max_tokens,
            },
            "threads": args.threads,
            "context_size": args.context_size,
            "repetitions": args.repetitions,
            "server_initialization_ms": server.initialization_ms,
            "warmup_ms": warmup_ms,
            "warmup_details": warmup_details,
            "memory": {
                "after_model_load": memory_after_load,
                "after_warmup": memory_after_warmup,
                "after_suite": memory_after_suite,
            },
            "logs": {
                "stdout": (
                    str(server.stdout_path)
                    if args.external_server_url is None
                    else None
                ),
                "stderr": (
                    str(server.stderr_path)
                    if args.external_server_url is None
                    else None
                ),
            },
            "network_measurement": (
                "Per-request UTF-8 HTTP body bytes; headers, TLS, and "
                "transport overhead are excluded."
            ),
            "runtime_scope": (
                args.runtime_scope.strip()
                or default_runtime_scope(args.external_server_url is not None)
            ),
        },
        "cases": output_cases,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"Wrote {args.output.resolve()}", flush=True)


if __name__ == "__main__":
    main()
