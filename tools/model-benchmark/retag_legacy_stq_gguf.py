#!/usr/bin/env python3
"""Retag the pinned legacy Hy-MT2 STQ GGUF for the rebased STQ runtime.

The first public Hy-MT2 1.25-bit GGUF used tensor type 42 and file type 41 for
STQ1_0. After llama.cpp added Q2_0, the STQ pull request moved those values to
43 and 42 respectively. This tool copies the file and changes only those GGUF
header fields. Tensor payload bytes are verified unchanged.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import struct
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO


PINNED_SOURCE_SHA256 = (
    "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93"
)
LEGACY_FILE_TYPE = 41
CURRENT_FILE_TYPE = 42
LEGACY_TENSOR_TYPE = 42
CURRENT_TENSOR_TYPE = 43

UINT8 = 0
INT8 = 1
UINT16 = 2
INT16 = 3
UINT32 = 4
INT32 = 5
FLOAT32 = 6
BOOL = 7
STRING = 8
ARRAY = 9
UINT64 = 10
INT64 = 11
FLOAT64 = 12

SCALAR_SIZES = {
    UINT8: 1,
    INT8: 1,
    UINT16: 2,
    INT16: 2,
    UINT32: 4,
    INT32: 4,
    FLOAT32: 4,
    BOOL: 1,
    UINT64: 8,
    INT64: 8,
    FLOAT64: 8,
}


@dataclass(frozen=True)
class MetadataField:
    value_type: int
    value: object | None
    value_offset: int


@dataclass(frozen=True)
class TensorField:
    name: str
    tensor_type: int
    type_offset: int


@dataclass(frozen=True)
class GgufScan:
    version: int
    tensor_count: int
    metadata_count: int
    metadata: dict[str, MetadataField]
    tensors: tuple[TensorField, ...]
    tensor_data_offset: int


def _read_exact(source: BinaryIO, size: int) -> bytes:
    value = source.read(size)
    if len(value) != size:
        raise ValueError("truncated GGUF")
    return value


def _read_u32(source: BinaryIO) -> int:
    return struct.unpack("<I", _read_exact(source, 4))[0]


def _read_u64(source: BinaryIO) -> int:
    return struct.unpack("<Q", _read_exact(source, 8))[0]


def _read_string(source: BinaryIO) -> str:
    length = _read_u64(source)
    return _read_exact(source, length).decode("utf-8")


def _read_metadata_value(
    source: BinaryIO,
    value_type: int,
    *,
    retain: bool,
) -> object | None:
    if value_type == STRING:
        value = _read_string(source)
        return value if retain else None
    if value_type == ARRAY:
        element_type = _read_u32(source)
        length = _read_u64(source)
        if element_type == ARRAY:
            raise ValueError("nested GGUF metadata arrays are invalid")
        if element_type in SCALAR_SIZES:
            size = SCALAR_SIZES[element_type] * length
            _read_exact(source, size)
        elif element_type == STRING:
            for _ in range(length):
                _read_string(source)
        else:
            raise ValueError(f"unsupported GGUF array element type {element_type}")
        return None
    if value_type not in SCALAR_SIZES:
        raise ValueError(f"unsupported GGUF metadata type {value_type}")

    raw = _read_exact(source, SCALAR_SIZES[value_type])
    if not retain:
        return None
    formats = {
        UINT8: "<B",
        INT8: "<b",
        UINT16: "<H",
        INT16: "<h",
        UINT32: "<I",
        INT32: "<i",
        FLOAT32: "<f",
        BOOL: "<?",
        UINT64: "<Q",
        INT64: "<q",
        FLOAT64: "<d",
    }
    return struct.unpack(formats[value_type], raw)[0]


def scan_gguf(path: Path) -> GgufScan:
    retained_keys = {
        "general.alignment",
        "general.architecture",
        "general.file_type",
        "general.name",
        "general.quantization_version",
    }
    with path.open("rb") as source:
        if _read_exact(source, 4) != b"GGUF":
            raise ValueError("not a GGUF file")
        version = _read_u32(source)
        if version != 3:
            raise ValueError(f"unsupported GGUF version {version}; expected 3")
        tensor_count = _read_u64(source)
        metadata_count = _read_u64(source)

        metadata: dict[str, MetadataField] = {}
        for _ in range(metadata_count):
            key = _read_string(source)
            value_type = _read_u32(source)
            value_offset = source.tell()
            value = _read_metadata_value(
                source,
                value_type,
                retain=key in retained_keys,
            )
            if key in retained_keys:
                metadata[key] = MetadataField(value_type, value, value_offset)

        tensors: list[TensorField] = []
        for _ in range(tensor_count):
            name = _read_string(source)
            dimension_count = _read_u32(source)
            _read_exact(source, dimension_count * 8)
            type_offset = source.tell()
            tensor_type = _read_u32(source)
            _read_u64(source)  # Relative tensor-data offset.
            tensors.append(TensorField(name, tensor_type, type_offset))

        alignment_field = metadata.get("general.alignment")
        alignment = int(alignment_field.value) if alignment_field else 32
        if alignment <= 0 or alignment & (alignment - 1):
            raise ValueError(f"invalid GGUF alignment {alignment}")
        tensor_info_end = source.tell()
        tensor_data_offset = (tensor_info_end + alignment - 1) // alignment * alignment

    if tensor_data_offset > path.stat().st_size:
        raise ValueError("GGUF tensor-data offset is past end of file")
    return GgufScan(
        version=version,
        tensor_count=tensor_count,
        metadata_count=metadata_count,
        metadata=metadata,
        tensors=tuple(tensors),
        tensor_data_offset=tensor_data_offset,
    )


def sha256_file(path: Path, *, start: int = 0) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        source.seek(start)
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def retag_legacy_stq(
    source_path: Path,
    output_path: Path,
    *,
    expected_source_sha256: str | None = PINNED_SOURCE_SHA256,
    expected_stq_tensor_count: int = 224,
    force: bool = False,
) -> dict[str, object]:
    source_path = source_path.resolve()
    output_path = output_path.resolve()
    if source_path == output_path:
        raise ValueError("output must differ from source")
    if output_path.exists() and not force:
        raise FileExistsError(f"output already exists: {output_path}")

    source_sha256 = sha256_file(source_path)
    if (
        expected_source_sha256 is not None
        and source_sha256.lower() != expected_source_sha256.lower()
    ):
        raise ValueError(
            "source SHA-256 mismatch: "
            f"expected {expected_source_sha256}, got {source_sha256}"
        )

    before = scan_gguf(source_path)
    architecture = before.metadata.get("general.architecture")
    if architecture is None or architecture.value != "hunyuan-dense":
        raise ValueError("expected a hunyuan-dense GGUF")
    file_type = before.metadata.get("general.file_type")
    if (
        file_type is None
        or file_type.value_type != UINT32
        or file_type.value != LEGACY_FILE_TYPE
    ):
        raise ValueError(
            f"expected legacy general.file_type={LEGACY_FILE_TYPE}"
        )

    type_counts_before = Counter(t.tensor_type for t in before.tensors)
    legacy_tensors = [
        tensor for tensor in before.tensors
        if tensor.tensor_type == LEGACY_TENSOR_TYPE
    ]
    if len(legacy_tensors) != expected_stq_tensor_count:
        raise ValueError(
            "legacy STQ tensor count mismatch: "
            f"expected {expected_stq_tensor_count}, got {len(legacy_tensors)}"
        )
    if type_counts_before[CURRENT_TENSOR_TYPE]:
        raise ValueError("input already contains current STQ tensor tags")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source_path, output_path)
    try:
        with output_path.open("r+b") as output:
            output.seek(file_type.value_offset)
            output.write(struct.pack("<I", CURRENT_FILE_TYPE))
            for tensor in legacy_tensors:
                output.seek(tensor.type_offset)
                output.write(struct.pack("<I", CURRENT_TENSOR_TYPE))

        after = scan_gguf(output_path)
        type_counts_after = Counter(t.tensor_type for t in after.tensors)
        patched_file_type = after.metadata["general.file_type"].value
        if patched_file_type != CURRENT_FILE_TYPE:
            raise ValueError("file type retag verification failed")
        if type_counts_after[LEGACY_TENSOR_TYPE]:
            raise ValueError("legacy tensor tags remain after retag")
        if type_counts_after[CURRENT_TENSOR_TYPE] != len(legacy_tensors):
            raise ValueError("current STQ tensor count verification failed")
        if source_path.stat().st_size != output_path.stat().st_size:
            raise ValueError("retag changed file size")

        source_payload_sha256 = sha256_file(
            source_path,
            start=before.tensor_data_offset,
        )
        output_payload_sha256 = sha256_file(
            output_path,
            start=after.tensor_data_offset,
        )
        if (
            before.tensor_data_offset != after.tensor_data_offset
            or source_payload_sha256 != output_payload_sha256
        ):
            raise ValueError("tensor payload changed during retag")

        return {
            "source": str(source_path),
            "output": str(output_path),
            "source_size_bytes": source_path.stat().st_size,
            "output_size_bytes": output_path.stat().st_size,
            "source_sha256": source_sha256,
            "output_sha256": sha256_file(output_path),
            "tensor_data_offset": before.tensor_data_offset,
            "tensor_payload_sha256": source_payload_sha256,
            "file_type": {
                "before": LEGACY_FILE_TYPE,
                "after": CURRENT_FILE_TYPE,
            },
            "tensor_type": {
                "before": LEGACY_TENSOR_TYPE,
                "after": CURRENT_TENSOR_TYPE,
                "patched_count": len(legacy_tensors),
            },
            "tensor_type_counts_before": dict(sorted(type_counts_before.items())),
            "tensor_type_counts_after": dict(sorted(type_counts_after.items())),
        }
    except Exception:
        output_path.unlink(missing_ok=True)
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--manifest",
        type=Path,
        help="Optional path for the JSON verification manifest.",
    )
    parser.add_argument(
        "--expected-source-sha256",
        default=PINNED_SOURCE_SHA256,
        help="Pinned legacy GGUF SHA-256; pass an empty string to skip.",
    )
    parser.add_argument("--expected-stq-tensors", type=int, default=224)
    parser.add_argument("--force", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    result = retag_legacy_stq(
        args.input,
        args.output,
        expected_source_sha256=args.expected_source_sha256 or None,
        expected_stq_tensor_count=args.expected_stq_tensors,
        force=args.force,
    )
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.manifest:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
