#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import struct
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("retag_legacy_stq_gguf.py")
SPEC = importlib.util.spec_from_file_location("retag_legacy_stq_gguf", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def _string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack("<Q", len(encoded)) + encoded


def _fixture(path: Path) -> bytes:
    metadata = (
        _string("general.architecture")
        + struct.pack("<I", MODULE.STRING)
        + _string("hunyuan-dense")
        + _string("general.file_type")
        + struct.pack("<II", MODULE.UINT32, MODULE.LEGACY_FILE_TYPE)
        + _string("general.alignment")
        + struct.pack("<II", MODULE.UINT32, 32)
    )
    tensors = b""
    for index in range(2):
        tensors += (
            _string(f"tensor.{index}")
            + struct.pack("<I", 2)
            + struct.pack("<QQ", 32, 32)
            + struct.pack("<I", MODULE.LEGACY_TENSOR_TYPE)
            + struct.pack("<Q", index * 16)
        )
    header = b"GGUF" + struct.pack("<IQQ", 3, 2, 3) + metadata + tensors
    padding = b"\x00" * ((32 - len(header) % 32) % 32)
    payload = bytes(range(64))
    path.write_bytes(header + padding + payload)
    return payload


class RetagLegacyStqTest(unittest.TestCase):
    def test_retag_changes_header_and_preserves_payload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory, "legacy.gguf")
            output = Path(directory, "current.gguf")
            payload = _fixture(source)

            result = MODULE.retag_legacy_stq(
                source,
                output,
                expected_source_sha256=None,
                expected_stq_tensor_count=2,
            )

            scan = MODULE.scan_gguf(output)
            self.assertEqual(
                scan.metadata["general.file_type"].value,
                MODULE.CURRENT_FILE_TYPE,
            )
            self.assertEqual(
                [tensor.tensor_type for tensor in scan.tensors],
                [MODULE.CURRENT_TENSOR_TYPE, MODULE.CURRENT_TENSOR_TYPE],
            )
            self.assertEqual(output.read_bytes()[scan.tensor_data_offset:], payload)
            self.assertEqual(result["tensor_type"]["patched_count"], 2)
            self.assertEqual(source.stat().st_size, output.stat().st_size)

    def test_wrong_architecture_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory, "legacy.gguf")
            output = Path(directory, "current.gguf")
            _fixture(source)
            data = source.read_bytes().replace(b"hunyuan-dense", b"not-hunyuanxx")
            source.write_bytes(data)
            with self.assertRaisesRegex(ValueError, "hunyuan-dense"):
                MODULE.retag_legacy_stq(
                    source,
                    output,
                    expected_source_sha256=None,
                    expected_stq_tensor_count=2,
                )


if __name__ == "__main__":
    unittest.main()
