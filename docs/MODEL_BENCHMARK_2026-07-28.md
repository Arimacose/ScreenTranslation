# Model benchmark — 2026-07-28

This report compares the current ML Kit pipeline with PP-OCRv6 and an
OPUS-MT/Bergamot candidate. It is an initial seven-fixture gate, not a final
model-selection benchmark.

## Scope

- Current OCR and translation baseline: ML Kit on Xiaomi 15 Pro, Android 16 /
  SDK 36.
- OCR candidates: PP-OCRv6 small and tiny on the Windows host.
- Translation quality reference: `Helsinki-NLP/opus-mt-en-zh` through
  Transformers.
- Native runtime candidates: OPUS-MT float32 and quantized models through
  Bergamot `9271618ebbdc5d21ac4dc4df9e72beb7ce644774` in WSL2 x86_64.
- Seven identical screen-like PNG fixtures and source/reference strings are
  reused for every engine.
- The final benchmark APK rerun reproduced every ML Kit OCR and translation
  output byte-for-byte. Tables use its final latency measurements.

Host and Android latency values are listed to establish a baseline, but they
are not cross-hardware performance comparisons. PP-OCRv6 host inference ran
with oneDNN disabled. Candidate end-to-end latency is estimated by adding
separately measured OCR and translation time.

## Metrics

- OCR: corpus character error rate (CER), word error rate (WER), exact cases,
  and inference latency.
- Translation: single-reference Chinese BLEU, chrF++, and eight critical
  semantic/literal checks.
- Human review remains the deciding quality gate. BLEU and chrF++ are
  directional signals because each case has only one Chinese reference.

## OCR

| Engine | Runtime | CER | WER | Exact | Median | p95 | Model assets |
|---|---|---:|---:|---:|---:|---:|---:|
| ML Kit | Xiaomi 15 Pro | 0.731% | 1.053% | 2/7 | 53.206 ms | 85.271 ms | managed by ML Kit |
| PP-OCRv6 small | Windows host | **0.365%** | 1.053% | **5/7** | 1267.605 ms | 1792.903 ms | 30.02 MiB |
| PP-OCRv6 tiny | Windows host | 1.218% | 2.632% | 3/7 | 464.558 ms | 663.021 ms | 6.23 MiB |

PP-OCRv6 small halved CER and raised exact recognition from 2/7 to 5/7. The
tiny variant was faster than small on the host but regressed below ML Kit
quality. PP-OCRv6 small advances to an Android arm64 proof of concept; tiny
does not.

## Translation

### Raw gold English

| Engine | Runtime | BLEU | chrF++ | Critical | Median |
|---|---|---:|---:|---:|---:|
| ML Kit | Xiaomi 15 Pro | **41.935** | **34.327** | 7/8 | 57.110 ms |
| OPUS-MT float | Transformers / Windows | 23.265 | 21.109 | 7/8 | 699.332 ms |
| OPUS-MT float | Bergamot / WSL2 | 23.265 | 21.109 | 7/8 | 8077.793 ms |
| OPUS-MT int8shift | Bergamot / WSL2 x86 | 24.142 | 20.676 | 6/8 | 191.968 ms |
| OPUS-MT int8 | Bergamot / WSL2 ARM-like precision | 24.835 | 21.408 | 6/8 | 143.074 ms |

The float32 Transformers and Bergamot scores match exactly. This validates
the custom 65,000-ID SentencePiece alignment and shows that remaining float
quality differences are model behavior rather than conversion damage.

### Current clause-splitting pipeline

| Engine | Runtime | BLEU | chrF++ | Critical | Median |
|---|---|---:|---:|---:|---:|
| ML Kit | Xiaomi 15 Pro | **41.091** | **33.406** | **8/8** | 59.016 ms |
| OPUS-MT float | Transformers / Windows | 22.969 | 20.945 | 7/8 | 688.850 ms |
| OPUS-MT float | Bergamot / WSL2 | 22.969 | 20.945 | 7/8 | 7930.757 ms |
| OPUS-MT int8shift | Bergamot / WSL2 x86 | 21.463 | 19.866 | 6/8 | 207.468 ms |
| OPUS-MT int8 | Bergamot / WSL2 ARM-like precision | 22.844 | 20.850 | 6/8 | 165.255 ms |

### Estimated end to end

| Pipeline | BLEU | chrF++ | Critical | Median |
|---|---:|---:|---:|---:|
| ML Kit OCR + ML Kit translation, Android | **39.887** | **33.341** | 7/8 | 129.129 ms |
| PP-OCRv6 small + OPUS-MT float, host | 22.969 | 21.642 | 7/8 | 1943.443 ms |
| PP-OCRv6 small + Bergamot int8, host/WSL | 23.461 | 21.194 | 6/8 | 1401.718 ms |

## Human review

| Fixture | Current ML Kit | OPUS-MT float finding |
|---|---|---|
| Long offline explanation | Repeats “no network connection” but preserves the privacy/offline meaning after clause splitting. | More fluent and preserves both “text never leaves the phone” and offline operation. |
| Literary sentence | Produces “must endure a wife”, which reverses the intended idiom. | Produces “must lack a wife”; still stiff, but closer to the source. |
| Notification recovery | Raw path turns “translating” into “averaging”; clause splitting changes it to “conversion”. | Correctly says translation resumes, while mistranslating “foreground” as “ground”. |
| Version/currency/date | Preserves `v0.1.0`, `¥12,345.67`, and the date. | Loses the version zero and changes yen to pounds. |
| Offline status | Preserves `OFFLINE`, `10/10`, and `1.5`. | Float preserves them; both tested int8 paths change `OFFLINE` to `OFLINE`. |

OPUS-MT improves two difficult natural-language cases, but the current model
is materially weaker on UI terminology and protected technical literals.
The candidate is not a drop-in replacement for the current translator.

## Bergamot conversion findings

- Original float model plus aligned vocabs: 298.39 MiB.
- int8 model plus aligned vocabs: 77.17 MiB, a 74.14% reduction.
- WSL process peak RSS fell from about 1.50 GiB for float32 to about 643 MiB
  for int8.
- Uncalibrated int8 was much faster than this WSL float32 build, but added the
  `OFFLINE` corruption and reduced the critical gate from 7/8 to 6/8.
- A seven-fixture activation-alpha calibration was intentionally exploratory.
  It reduced the gate further to 5/8, showing that calibration requires a
  separate, representative corpus.
- The tested int16 conversion produced repeated-token output and scored 0/8;
  that path is rejected for this toolchain.
- Bergamot disables shifted GEMM on ARM, so Xiaomi 15 Pro acceptance must use
  an arm64 build and the effective unshifted/alpha path. x86 timings do not
  predict Snapdragon performance.

## Decision

1. **Proceed with PP-OCRv6 small** as an Android proof of concept.
2. **Keep ML Kit translation as the default** while evaluating newer or
   domain-adapted translation models.
3. **Keep Bergamot as a viable runtime experiment**, because float conversion
   is correct and the int8 package size is practical.
4. Before another translation candidate can advance:
   - mask and restore versions, identifiers, dates, amounts, currencies, and
     status tokens around translation;
   - calibrate quantization on a held-out screen-UI corpus;
   - expand the benchmark beyond seven fixtures;
   - build arm64 JNI/AAR artifacts and measure latency, RSS, and thermal
     behavior on Xiaomi 15 Pro;
   - require no regression in critical checks and human terminology review.

## Reproducibility

The tracked harness is under `tools/model-benchmark/`. Generated JSON, score
files, PNGs, models, and runtime logs stay under ignored build directories.
The final benchmark APK SHA-256 is
`9E4063591581FD02D1BCBB08214F2E7C1E806D4F46116CCB41726B9D9FA8D1C1`;
the final device JSON SHA-256 is
`D8A3570B03F5A199AFE7134310DDC8DCB407A62C337BD52B5753CC9EB7524C2A`.
Upstream references:

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)
- [OPUS-MT en-zh model card](https://huggingface.co/Helsinki-NLP/opus-mt-en-zh)
- [Bergamot Translator](https://github.com/browsermt/bergamot-translator)
- [OPUS-MT app quantization notes](https://github.com/Helsinki-NLP/OPUS-MT-app)
