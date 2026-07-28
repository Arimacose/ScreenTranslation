#!/usr/bin/env python3
"""Build Bergamot-compatible SentencePiece vocabs for an OPUS-MT model.

Older OPUS-MT archives can pair separate source/target SentencePiece models
with a shared Marian YAML vocabulary. Bergamot loads SentencePiece models
directly and therefore expects their piece IDs to match the Marian model IDs.
This tool preserves each side's tokenizer scores while reordering its pieces
to the shared YAML IDs.
"""

from __future__ import annotations

import argparse
import ast
import json
import re
from pathlib import Path
from typing import Any

import sentencepiece as spm
from sentencepiece import sentencepiece_model_pb2


VOCAB_LINE = re.compile(r"(.*): (\d+)")
LANGUAGE_TAG = re.compile(r">>[^<>\s]+<<")


def parse_marian_vocab(path: Path) -> list[str]:
    """Parse Marian's YAML-like vocabulary without coercing numeric tokens."""
    tokens_by_id: dict[int, str] = {}
    ids_by_token: dict[str, int] = {}
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        match = VOCAB_LINE.fullmatch(line)
        if match is None:
            raise ValueError(f"{path}:{line_number}: malformed vocabulary line")
        raw_token, raw_id = match.groups()
        token_id = int(raw_id)
        token = (
            ast.literal_eval(raw_token)
            if (
                len(raw_token) >= 2
                and raw_token[0] == raw_token[-1]
                and raw_token[0] in "\"'"
            )
            else raw_token
        )
        if not isinstance(token, str):
            raise TypeError(
                f"{path}:{line_number}: token did not decode to text: {raw_token}"
            )
        if token_id in tokens_by_id:
            raise ValueError(f"{path}:{line_number}: duplicate ID {token_id}")
        if token in ids_by_token:
            raise ValueError(
                f"{path}:{line_number}: duplicate token {token!r} at IDs "
                f"{ids_by_token[token]} and {token_id}"
            )
        tokens_by_id[token_id] = token
        ids_by_token[token] = token_id

    expected = set(range(len(tokens_by_id)))
    actual = set(tokens_by_id)
    if actual != expected:
        missing = sorted(expected - actual)
        raise ValueError(f"{path}: vocabulary IDs are not contiguous: {missing[:8]}")
    return [tokens_by_id[index] for index in range(len(tokens_by_id))]


def load_proto(path: Path) -> sentencepiece_model_pb2.ModelProto:
    proto = sentencepiece_model_pb2.ModelProto()
    proto.ParseFromString(path.read_bytes())
    return proto


def build_aligned_proto(
    template: sentencepiece_model_pb2.ModelProto,
    tokens: list[str],
    *,
    filler_score: float,
    leading_space_language_tags: bool,
) -> tuple[sentencepiece_model_pb2.ModelProto, dict[str, Any]]:
    original = {piece.piece: piece for piece in template.pieces}
    aligned = sentencepiece_model_pb2.ModelProto()
    aligned.CopyFrom(template)
    aligned.ClearField("pieces")

    copied = filler = language_tags = user_defined_fillers = unused_fillers = 0
    for token_id, token in enumerate(tokens):
        piece = aligned.pieces.add()
        is_language_tag = LANGUAGE_TAG.fullmatch(token) is not None
        piece.piece = (
            f"▁{token}"
            if is_language_tag and leading_space_language_tags
            else token
        )
        if token == "<unk>":
            piece.score = 0
            piece.type = sentencepiece_model_pb2.ModelProto.SentencePiece.UNKNOWN
        elif token == "</s>":
            piece.score = 0
            piece.type = sentencepiece_model_pb2.ModelProto.SentencePiece.CONTROL
        elif token in original:
            piece.CopyFrom(original[token])
            copied += 1
        else:
            is_user_defined = is_language_tag or len(token) == 1
            piece.score = 0 if is_user_defined else filler_score
            piece.type = (
                sentencepiece_model_pb2.ModelProto.SentencePiece.USER_DEFINED
                if is_user_defined
                else sentencepiece_model_pb2.ModelProto.SentencePiece.UNUSED
            )
            filler += 1
            language_tags += int(is_language_tag)
            user_defined_fillers += int(is_user_defined)
            unused_fillers += int(not is_user_defined)

        if token_id == 0 and token != "</s>":
            raise ValueError("Marian ID 0 must be </s>")
        if token_id == 1 and token != "<unk>":
            raise ValueError("Marian ID 1 must be <unk>")

    aligned.trainer_spec.vocab_size = len(tokens)
    aligned.trainer_spec.unk_id = 1
    aligned.trainer_spec.unk_piece = "<unk>"
    aligned.trainer_spec.eos_id = 0
    aligned.trainer_spec.eos_piece = "</s>"
    aligned.trainer_spec.bos_id = -1
    aligned.trainer_spec.pad_id = -1

    return aligned, {
        "original_pieces": len(template.pieces),
        "aligned_pieces": len(aligned.pieces),
        "copied_pieces": copied,
        "filler_pieces": filler,
        "language_tags": language_tags,
        "user_defined_fillers": user_defined_fillers,
        "unused_fillers": unused_fillers,
    }


def verify_source_ids(
    *,
    original_path: Path,
    aligned_path: Path,
    tokens: list[str],
    prefix: str,
    texts: list[str],
) -> list[dict[str, Any]]:
    ids_by_token = {token: index for index, token in enumerate(tokens)}
    original = spm.SentencePieceProcessor(model_file=str(original_path))
    aligned = spm.SentencePieceProcessor(model_file=str(aligned_path))
    results = []
    for text in texts:
        original_pieces = original.encode(text, out_type=str)
        missing = [piece for piece in original_pieces if piece not in ids_by_token]
        expected = [ids_by_token[prefix]] + [
            ids_by_token.get(piece, 1) for piece in original_pieces
        ]
        actual = aligned.encode(f"{prefix} {text}", out_type=int)
        results.append(
            {
                "text": text,
                "matched": actual == expected,
                "expected_ids": expected,
                "actual_ids": actual,
                "original_pieces_missing_from_marian_vocab": missing,
            }
        )
    return results


def load_verification_texts(path: Path | None) -> list[str]:
    if path is None:
        return ["The model keeps working without a network connection."]
    source = json.loads(path.read_text(encoding="utf-8"))
    return [case["source_text"] for case in source["cases"]]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vocab-yaml", type=Path, required=True)
    parser.add_argument("--source-spm", type=Path, required=True)
    parser.add_argument("--target-spm", type=Path, required=True)
    parser.add_argument("--source-output", type=Path, required=True)
    parser.add_argument("--target-output", type=Path, required=True)
    parser.add_argument("--verify-json", type=Path)
    parser.add_argument("--target-prefix", default=">>cmn_Hans<<")
    parser.add_argument("--filler-score", type=float, default=-1000.0)
    args = parser.parse_args()

    tokens = parse_marian_vocab(args.vocab_yaml)
    if args.target_prefix not in tokens:
        raise ValueError(f"Target prefix is absent from vocabulary: {args.target_prefix}")

    source_proto, source_stats = build_aligned_proto(
        load_proto(args.source_spm),
        tokens,
        filler_score=args.filler_score,
        leading_space_language_tags=True,
    )
    target_proto, target_stats = build_aligned_proto(
        load_proto(args.target_spm),
        tokens,
        filler_score=args.filler_score,
        leading_space_language_tags=False,
    )
    args.source_output.parent.mkdir(parents=True, exist_ok=True)
    args.target_output.parent.mkdir(parents=True, exist_ok=True)
    args.source_output.write_bytes(source_proto.SerializeToString())
    args.target_output.write_bytes(target_proto.SerializeToString())

    verification = verify_source_ids(
        original_path=args.source_spm,
        aligned_path=args.source_output,
        tokens=tokens,
        prefix=args.target_prefix,
        texts=load_verification_texts(args.verify_json),
    )
    report = {
        "vocabulary_size": len(tokens),
        "target_prefix": args.target_prefix,
        "source": source_stats,
        "target": target_stats,
        "verification_cases": len(verification),
        "verification_matches": sum(item["matched"] for item in verification),
        "verification": verification,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report["verification_matches"] != report["verification_cases"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
