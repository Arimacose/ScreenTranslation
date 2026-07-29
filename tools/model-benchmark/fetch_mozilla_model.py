#!/usr/bin/env python3
"""Fetch the pinned Firefox Translations English-to-Chinese model.

The model stays under an ignored build directory. Downloads and decompressed
artifacts are checked before publication so a partial or changed upstream file
never becomes benchmark input.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import shutil
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path


REGISTRY_URL = (
    "https://storage.googleapis.com/"
    "moz-fx-translations-data--303e-prod-translations-data/db/models.json"
)
MODEL_BASE_URL = (
    "https://storage.googleapis.com/"
    "moz-fx-translations-data--303e-prod-translations-data/"
    "models/en-zh/llmaat_finetune10M_qe8_f2_ByQcSxGXQRqGi-UTxYE43g/"
    "exported/"
)
MODEL_SNAPSHOT = "2026-07-28T00:37:27Z"
MODEL_DIRECTORY = "mozilla-en-zh-base-memory-2026-07-28"
CHUNK_SIZE = 1024 * 1024


@dataclass(frozen=True)
class CompressedAsset:
    compressed_name: str
    compressed_size: int
    compressed_sha256: str
    output_name: str
    output_size: int
    output_sha256: str


ASSETS = (
    CompressedAsset(
        compressed_name="model.enzh.intgemm.alphas.bin.gz",
        compressed_size=33_375_922,
        compressed_sha256=(
            "7f255403b3bb2502f08ac4d5ca397a8a5a13f899d2f2e987a4934e089d241d16"
        ),
        output_name="model.enzh.intgemm.alphas.bin",
        output_size=43_849_787,
        output_sha256=(
            "4e5accc141373565ddc8fa1565bceaa8d0c3482a82cab8131c719ebcc6c2157c"
        ),
    ),
    CompressedAsset(
        compressed_name="srcvocab.enzh.spm.gz",
        compressed_size=407_784,
        compressed_sha256=(
            "7846e3c236388390f4e5d321f8413d67f34c1bab5f066165eeb673bfd07607cc"
        ),
        output_name="srcvocab.enzh.spm",
        output_size=806_952,
        output_sha256=(
            "bd9b65504acc6d9726dd281f7defc2adb7c2c22d0688fe2f84697de25197c8c5"
        ),
    ),
    CompressedAsset(
        compressed_name="trgvocab.enzh.spm.gz",
        compressed_size=425_748,
        compressed_sha256=(
            "4d641ce165b1f8478ee2ffb5149d2d46fab3779dc8fa1e9b97f9af1d2206c091"
        ),
        output_name="trgvocab.enzh.spm",
        output_size=772_004,
        output_sha256=(
            "aded6993c36e440284d11cec3f6b8aef9c0e43188a772d80be342a713adf223d"
        ),
    ),
    CompressedAsset(
        compressed_name="lex.50.50.enzh.s2t.bin.gz",
        compressed_size=2_536_039,
        compressed_sha256=(
            "806f75821c0b838f4a8f4afe5bab3db8289cb7e5187753ba04c3bceadd75687a"
        ),
        output_name="lex.50.50.enzh.s2t.bin",
        output_size=4_485_184,
        output_sha256=(
            "8575d8daa10e2dbff316dcdf8e1ce475357bcc2c92bdc63b736a2d5add22f681"
        ),
    ),
)
METADATA_NAME = "metadata.json"
METADATA_SIZE = 2_176
METADATA_SHA256 = (
    "c39fc5948d0905bbc825ea8bd85c2ee7d43df3e1206d91c2f2c094c6741c096c"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(CHUNK_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def is_verified(path: Path, size: int, expected_sha256: str) -> bool:
    return (
        path.is_file()
        and path.stat().st_size == size
        and sha256(path) == expected_sha256
    )


def download_verified(
    *,
    url: str,
    target: Path,
    size: int,
    expected_sha256: str,
) -> None:
    if is_verified(target, size, expected_sha256):
        return

    partial = target.with_name(f"{target.name}.part")
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "ScreenTranslation-model-benchmark/0.1.0"},
    )
    with urllib.request.urlopen(request, timeout=180) as source:
        with partial.open("wb") as output:
            shutil.copyfileobj(source, output, CHUNK_SIZE)
    if not is_verified(partial, size, expected_sha256):
        raise RuntimeError(f"Downloaded artifact failed verification: {url}")
    os.replace(partial, target)


def decompress_verified(asset: CompressedAsset, directory: Path) -> None:
    source = directory / asset.compressed_name
    target = directory / asset.output_name
    if is_verified(target, asset.output_size, asset.output_sha256):
        return

    partial = target.with_name(f"{target.name}.part")
    with gzip.open(source, "rb") as compressed:
        with partial.open("wb") as output:
            shutil.copyfileobj(compressed, output, CHUNK_SIZE)
    if not is_verified(partial, asset.output_size, asset.output_sha256):
        raise RuntimeError(
            f"Decompressed artifact failed verification: {asset.output_name}"
        )
    os.replace(partial, target)


def write_config(directory: Path, beam_size: int) -> Path:
    config = directory / f"decoder.bergamot-beam{beam_size}.yml"
    config.write_text(
        "\n".join(
            (
                "relative-paths: true",
                "models:",
                "  - model.enzh.intgemm.alphas.bin",
                "vocabs:",
                "  - srcvocab.enzh.spm",
                "  - trgvocab.enzh.spm",
                "shortlist:",
                "  - lex.50.50.enzh.s2t.bin",
                "  - 50",
                "  - 50",
                f"beam-size: {beam_size}",
                "normalize: 1",
                "word-penalty: 0",
                "mini-batch: 1",
                "maxi-batch: 1",
                "maxi-batch-sort: src",
                "ssplit-mode: sentence",
                "max-length-break: 128",
                "mini-batch-words: 1024",
                "alignment: soft",
                "max-length-factor: 2.0",
                # Shifted GEMM is not selected on Android ARM64.
                "gemm-precision: int8Alpha",
                "",
            )
        ),
        encoding="utf-8",
    )
    return config


def validate_metadata(path: Path) -> None:
    metadata = json.loads(path.read_text(encoding="utf-8"))
    expected_model_hash = ASSETS[0].output_sha256
    checks = {
        "sourceLanguage": "en",
        "targetLanguage": "zh",
        "architecture": "base-memory",
        "byteSize": ASSETS[0].output_size,
        "hash": expected_model_hash,
    }
    for key, expected in checks.items():
        if metadata.get(key) != expected:
            raise RuntimeError(
                f"Unexpected metadata {key}: {metadata.get(key)!r}"
            )


def main() -> None:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=(
            repository
            / "app"
            / "build"
            / "model-benchmark"
            / MODEL_DIRECTORY
        ),
    )
    parser.add_argument("--beam-size", type=int, choices=(1, 4), default=4)
    args = parser.parse_args()

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    for asset in ASSETS:
        download_verified(
            url=MODEL_BASE_URL + asset.compressed_name,
            target=output / asset.compressed_name,
            size=asset.compressed_size,
            expected_sha256=asset.compressed_sha256,
        )
        decompress_verified(asset, output)

    metadata = output / METADATA_NAME
    download_verified(
        url=MODEL_BASE_URL + METADATA_NAME,
        target=metadata,
        size=METADATA_SIZE,
        expected_sha256=METADATA_SHA256,
    )
    validate_metadata(metadata)
    config = write_config(output, args.beam_size)

    files = sorted(
        (
            *(output / asset.output_name for asset in ASSETS),
            metadata,
            config,
        ),
        key=lambda path: path.name,
    )
    manifest = {
        "model_snapshot": MODEL_SNAPSHOT,
        "registry_url": REGISTRY_URL,
        "model_base_url": MODEL_BASE_URL,
        "beam_size": args.beam_size,
        "files": [
            {
                "name": path.name,
                "size_bytes": path.stat().st_size,
                "sha256": sha256(path),
            }
            for path in files
        ],
        "pinned_assets": [asdict(asset) for asset in ASSETS],
    }
    manifest_path = output / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "output": str(output),
                "config": str(config),
                "manifest": str(manifest_path),
                "model_assets_bytes": sum(
                    asset.output_size for asset in ASSETS
                ),
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
