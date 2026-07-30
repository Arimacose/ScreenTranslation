#!/usr/bin/env python3
"""Fetch and verify pinned Firefox Translations models used by the benchmark.

Models stay under ignored build directories. Downloads and decompressed
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
DATA_BASE_URL = (
    "https://storage.googleapis.com/"
    "moz-fx-translations-data--303e-prod-translations-data/"
)
CHUNK_SIZE = 1024 * 1024


@dataclass(frozen=True)
class CompressedAsset:
    compressed_name: str
    compressed_size: int
    compressed_sha256: str
    output_name: str
    output_size: int
    output_sha256: str


@dataclass(frozen=True)
class ModelProfile:
    pair: str
    source_language: str
    target_language: str
    architecture: str
    release_status: str
    model_snapshot: str
    model_directory: str
    model_base_url: str
    assets: tuple[CompressedAsset, ...]
    metadata_size: int
    metadata_sha256: str
    model_name: str
    vocab_names: tuple[str, str]
    shortlist_name: str


EN_ZH_ASSETS = (
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

JA_EN_ASSETS = (
    CompressedAsset(
        compressed_name="model.jaen.intgemm.alphas.bin.gz",
        compressed_size=32_577_435,
        compressed_sha256=(
            "ae56ffbb5556d8e4240b2f208a7c7a2449a4b627ac9d673981ed29eaadaab79d"
        ),
        output_name="model.jaen.intgemm.alphas.bin",
        output_size=43_977_787,
        output_sha256=(
            "3a603e20bfe1be86071913f9e23ab5129075bc0a8490151020ac4821e4f17302"
        ),
    ),
    CompressedAsset(
        compressed_name="vocab.jaen.spm.gz",
        compressed_size=746_616,
        compressed_sha256=(
            "12d693f5055525d5cc1e133c8c1b8ed787c77b9bb797400d9a14382ac69c1236"
        ),
        output_name="vocab.jaen.spm",
        output_size=1_443_222,
        output_sha256=(
            "5cb217758bae05877bb3f0c2f612e4e7c1e4cb03c10db11f4a47098d7ae62919"
        ),
    ),
    CompressedAsset(
        compressed_name="lex.50.50.jaen.s2t.bin.gz",
        compressed_size=4_819_610,
        compressed_sha256=(
            "438152f5ccd982edb43e88ef51305e3ae7c7b66ee5c20a8fa425e9f1822f9b9b"
        ),
        output_name="lex.50.50.jaen.s2t.bin",
        output_size=9_348_172,
        output_sha256=(
            "525f412f0d210536c2933c78ae395fa0bf2b5ee6cc5dda61ebc2e79410ebaee4"
        ),
    ),
)

PROFILES = {
    "en-zh": ModelProfile(
        pair="en-zh",
        source_language="en",
        target_language="zh",
        architecture="base-memory",
        release_status="Release Android",
        model_snapshot="2026-07-28T00:37:27Z",
        model_directory="mozilla-en-zh-base-memory-2026-07-28",
        model_base_url=(
            DATA_BASE_URL
            + "models/en-zh/"
            + "llmaat_finetune10M_qe8_f2_ByQcSxGXQRqGi-UTxYE43g/exported/"
        ),
        assets=EN_ZH_ASSETS,
        metadata_size=2_176,
        metadata_sha256=(
            "c39fc5948d0905bbc825ea8bd85c2ee7d43df3e1206d91c2f2c094c6741c096c"
        ),
        model_name="model.enzh.intgemm.alphas.bin",
        vocab_names=("srcvocab.enzh.spm", "trgvocab.enzh.spm"),
        shortlist_name="lex.50.50.enzh.s2t.bin",
    ),
    "ja-en": ModelProfile(
        pair="ja-en",
        source_language="ja",
        target_language="en",
        architecture="base-memory",
        release_status="Release Android",
        model_snapshot="2026-07-29T00:51:22Z",
        model_directory="mozilla-ja-en-base-memory-2026-07-29",
        model_base_url=(
            DATA_BASE_URL
            + "models/ja-en/"
            + "cjk_retrain_base-memory_NLRJLD_pQFyrvgKtbie2nA/exported/"
        ),
        assets=JA_EN_ASSETS,
        metadata_size=2_181,
        metadata_sha256=(
            "67dca5aa19ebc57de9cc588648a1e6948a8772d8fbd1ac21712e8cd2c2332358"
        ),
        model_name="model.jaen.intgemm.alphas.bin",
        vocab_names=("vocab.jaen.spm", "vocab.jaen.spm"),
        shortlist_name="lex.50.50.jaen.s2t.bin",
    ),
}


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


def write_config(
    directory: Path,
    profile: ModelProfile,
    beam_size: int,
) -> Path:
    config = directory / f"decoder.bergamot-beam{beam_size}.yml"
    config.write_text(
        "\n".join(
            (
                "relative-paths: true",
                "models:",
                f"  - {profile.model_name}",
                "vocabs:",
                *(f"  - {name}" for name in profile.vocab_names),
                "shortlist:",
                f"  - {profile.shortlist_name}",
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
        newline="\n",
    )
    return config


def validate_metadata(path: Path, profile: ModelProfile) -> None:
    metadata = json.loads(path.read_text(encoding="utf-8"))
    checks = {
        "sourceLanguage": profile.source_language,
        "targetLanguage": profile.target_language,
        "architecture": profile.architecture,
        "byteSize": profile.assets[0].output_size,
        "hash": profile.assets[0].output_sha256,
    }
    for key, expected in checks.items():
        if metadata.get(key) != expected:
            raise RuntimeError(
                f"Unexpected metadata {key}: {metadata.get(key)!r}"
            )


def main() -> None:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument("--pair", choices=tuple(PROFILES), default="en-zh")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--beam-size", type=int, choices=(1, 4), default=4)
    args = parser.parse_args()
    profile = PROFILES[args.pair]

    output = (
        args.output
        or repository
        / "app"
        / "build"
        / "model-benchmark"
        / profile.model_directory
    ).resolve()
    output.mkdir(parents=True, exist_ok=True)
    for asset in profile.assets:
        download_verified(
            url=profile.model_base_url + asset.compressed_name,
            target=output / asset.compressed_name,
            size=asset.compressed_size,
            expected_sha256=asset.compressed_sha256,
        )
        decompress_verified(asset, output)

    metadata = output / "metadata.json"
    download_verified(
        url=profile.model_base_url + metadata.name,
        target=metadata,
        size=profile.metadata_size,
        expected_sha256=profile.metadata_sha256,
    )
    validate_metadata(metadata, profile)
    config = write_config(output, profile, args.beam_size)

    files = sorted(
        (
            *(output / asset.output_name for asset in profile.assets),
            metadata,
            config,
        ),
        key=lambda path: path.name,
    )
    manifest = {
        "schema_version": 2,
        "pair": profile.pair,
        "source_language": profile.source_language,
        "target_language": profile.target_language,
        "architecture": profile.architecture,
        "release_status": profile.release_status,
        "model_snapshot": profile.model_snapshot,
        "registry_url": REGISTRY_URL,
        "model_base_url": profile.model_base_url,
        "beam_size": args.beam_size,
        "runtime": {
            "model": profile.model_name,
            "vocabs": list(profile.vocab_names),
            "shortlist": profile.shortlist_name,
            "config": config.name,
        },
        "files": [
            {
                "name": path.name,
                "size_bytes": path.stat().st_size,
                "sha256": sha256(path),
            }
            for path in files
        ],
        "pinned_assets": [asdict(asset) for asset in profile.assets],
    }
    manifest_path = output / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
        newline="\n",
    )
    print(
        json.dumps(
            {
                "pair": profile.pair,
                "output": str(output),
                "config": str(config),
                "manifest": str(manifest_path),
                "model_assets_bytes": sum(
                    asset.output_size for asset in profile.assets
                ),
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
