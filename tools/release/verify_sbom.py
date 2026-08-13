#!/usr/bin/env python3
"""Validate ScreenTranslation edition SBOMs without accepting local path leakage."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


LOCAL_PATH = re.compile(r"(?i)([a-z]:\\|/home/|/users/|\\\\)")
REQUIRED_COMMON = {
    "PP-OCRv6-small detection",
    "PP-OCRv6-small recognition",
    "onnxruntime-android",
}
REQUIRED_BY_EDITION = {
    "lite": {"Bergamot Translator Android runner", "Firefox Translations en-zh and ja-en routes"},
    "full": {"llama.cpp Android runtime", "HY-MT2 1.8B Q4_K_M"},
    "online": {"User-configured OpenAI-compatible model", "okhttp"},
}


def verify(path: Path, edition: str) -> None:
    raw = path.read_text(encoding="utf-8")
    if LOCAL_PATH.search(raw):
        raise ValueError(f"{path}: contains a local filesystem path")
    document = json.loads(raw)
    if document.get("bomFormat") != "CycloneDX" or document.get("specVersion") != "1.5":
        raise ValueError(f"{path}: expected CycloneDX 1.5")
    metadata = document.get("metadata", {})
    properties = {item.get("name"): item.get("value") for item in metadata.get("properties", [])}
    if properties.get("screentranslation:edition") != edition:
        raise ValueError(f"{path}: edition mismatch")
    components = document.get("components", [])
    names = {component.get("name") for component in components}
    missing = (REQUIRED_COMMON | REQUIRED_BY_EDITION[edition]) - names
    if missing:
        raise ValueError(f"{path}: missing components: {sorted(missing)}")
    refs = [component.get("bom-ref") for component in components]
    if any(not ref for ref in refs) or len(refs) != len(set(refs)):
        raise ValueError(f"{path}: empty or duplicate bom-ref")
    for component in components:
        licenses = component.get("licenses", [])
        if not licenses:
            raise ValueError(f"{path}: missing license coordinate for {component.get('name')}")
        if not all(
            item.get("expression") or item.get("license", {}).get("id")
            for item in licenses
        ):
            raise ValueError(f"{path}: malformed license coordinate for {component.get('name')}")
    application_ref = metadata.get("component", {}).get("bom-ref")
    dependency_roots = document.get("dependencies", [])
    if dependency_roots != [{"ref": application_ref, "dependsOn": refs}]:
        raise ValueError(f"{path}: application dependency inventory is incomplete or reordered")
    for component in components:
        for digest in component.get("hashes", []):
            if digest.get("alg") != "SHA-256" or not re.fullmatch(
                r"[0-9A-F]{64}", digest.get("content", "")
            ):
                raise ValueError(f"{path}: invalid SHA-256 entry for {component.get('name')}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    for edition in ("lite", "full", "online"):
        verify(
            args.directory / f"ScreenTranslation-{args.version}-{edition}.cdx.json",
            edition,
        )


if __name__ == "__main__":
    main()
