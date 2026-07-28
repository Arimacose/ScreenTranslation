# Model benchmark ? 2026-07-28

This report records the first migration gate from ML Kit OCR to PP-OCRv6 and
compares the current ML Kit translator with two Bergamot-compatible English to
Chinese model families. The corpus contains seven deterministic screen-like
fixtures, so the measurements are a focused regression gate rather than a
broad model-quality leaderboard.

## Test target and method

- Device: Xiaomi 15 Pro (`2410DPN6CC`).
- ROM: HyperOS `OS3.0.304.0.WOBCNXM`.
- OS: Android 16 / SDK 36, display build `BP2A.250605.031.A3`.
- OCR repetitions: three warm measurements per fixture after one warm-up.
- OCR metrics: corpus CER, WER, exact cases, median latency, p95 latency, and
  process high-water memory observed through ADB.
- Translation metrics: single-reference Chinese BLEU, chrF++, eight protected
  meaning/literal checks, latency, and human review.
- Android and WSL latency values describe different hardware and are shown in
  separate columns. The mixed end-to-end value adds the measured Android OCR
  time to the WSL translation time only as a planning estimate.

The production service still instantiates `MlKitOcrEngine`. PP-OCRv6 is isolated
in the `benchmark` source set while its latency, memory, and long-session thermal
behavior are optimized. The shared `OcrEngine` interface makes the eventual
production selection a small, reviewable change.

## PP-OCRv6 integration

The benchmark variant now contains a real Android implementation with:

- official PP-OCRv6 small detection and recognition ONNX models;
- pinned upstream revisions and SHA-256 verification during asset preparation;
- ONNX Runtime Android 1.26.0, arm64-v8a only;
- DB-style detection post-processing, perspective crops, dynamic-width
  recognition, CTC decoding, and reading-order reconstruction;
- CPU execution provider, four intra-op threads, recognition batch size 1;
- detector long-side cap of 640 px;
- ORT memory pattern and CPU arena disabled to reduce retained memory.

Pinned assets:

| Asset | Upstream revision | Size | SHA-256 |
|---|---|---:|---|
| Detection ONNX | `28fe5895c24fd108c19eb3e8479f4ab385fbfc62` | 9,880,512 B | `D73E0058B7A8086BBD57F3D10B8BCD4FF95363F67E06E2762B5E814FE9C9410E` |
| Recognition ONNX | `b8f84f0b80c529de40b4fbb3544b84fa7233a513` | 21,159,378 B | `5435FD747C9E0EFE15A96D0B378D5BD157E9492ED8FD80EDF08F30D02FA24634` |
| Recognition dictionary | same recognition revision | 74,947 B | `B5F2BFE2BDD9448429E3E82B51C789775D9B42F2403D082B00662EB77E401C5D` |

## OCR results on Xiaomi 15 Pro

| Engine/configuration | CER | WER | Exact | Median | p95 | Observed HWM |
|---|---:|---:|---:|---:|---:|---:|
| ML Kit Latin 16.0.1 | 0.7308% | 1.0526% | 2/7 | **39.362 ms** | **95.549 ms** | paired process |
| PP-OCRv6 small, CPU4, batch1, det640, arena off | **0.1218%** | **0.5263%** | **6/7** | 403.429 ms | 810.762 ms | 399,964 KiB |

PP-OCRv6 reduced CER by 83.3%, halved WER, and raised exact recognition from
2/7 to 6/7. Its median time is about 10.2 times the paired ML Kit median. The only
remaining fixture mismatch is the typographic em dash in the version fixture,
which becomes a hyphen while the version, amount, and date remain intact.

### Android optimization sequence

| PP-OCRv6 configuration | CER | Exact | Median | Observed HWM |
|---|---:|---:|---:|---:|
| CPU4, all crops batched, detector short side 736 | 0.3654% | 5/7 | 1001.525 ms | 1,068,256 KiB |
| CPU4, batch1, detector short side 736 | 0.2436% | 5/7 | 824.256 ms | 878,276 KiB |
| CPU4, batch1, no detector upscaling | 0.1218% | 6/7 | 522.951 ms | 627,232 KiB |
| CPU4, batch1, detector long side 640, arena on | 0.1218% | 6/7 | **365.622 ms** | 494,148 KiB |
| CPU4, batch1, detector long side 640, arena off, tuning run | 0.1218% | 6/7 | 403.970 ms | 412,384 KiB |
| Same retained configuration, clean-install acceptance rerun | 0.1218% | 6/7 | 403.429 ms | **399,964 KiB** |

XNNPACK 1.26 produced a native SIGSEGV during recognition inference on this
HyperOS build in two consecutive runs. The reproducible candidate therefore
uses ORT's CPU provider. Disabling the CPU arena traded about 38 ms median
latency for roughly 80 MiB lower observed high-water memory and is the retained
configuration.

## Translation results

### Raw gold English

| Engine | Runtime | BLEU | chrF++ | Critical | Median |
|---|---|---:|---:|---:|---:|
| ML Kit Translate 17.0.3 | Xiaomi 15 Pro | **41.935** | **34.327** | 7/8 | 57.110 ms |
| Firefox Translations en-zh base-memory, beam 4 | Bergamot / WSL2 x86_64 | 36.390 | 30.121 | **8/8** | **22.183 ms** |
| Firefox Translations en-zh base-memory, beam 1 | Bergamot / WSL2 x86_64 | 34.243 | 27.970 | **8/8** | 16.589 ms |
| Legacy `Helsinki-NLP/opus-mt-en-zh`, float | Transformers / Windows | 23.265 | 21.109 | 7/8 | 699.332 ms |
| Legacy OPUS-MT, int8Alpha | Bergamot / WSL2 x86_64 | 24.835 | 21.408 | 6/8 | 143.074 ms |

### Current clause-splitting pipeline

| Engine | Runtime | BLEU | chrF++ | Critical | Median |
|---|---|---:|---:|---:|---:|
| ML Kit Translate 17.0.3 | Xiaomi 15 Pro | **41.091** | **33.406** | **8/8** | 59.016 ms |
| Firefox Translations, beam 4 | Bergamot / WSL2 x86_64 | 36.260 | 29.849 | **8/8** | **21.452 ms** |
| Firefox Translations, beam 1 | Bergamot / WSL2 x86_64 | 34.118 | 27.909 | **8/8** | 15.956 ms |
| Legacy OPUS-MT, float | Transformers / Windows | 22.969 | 20.945 | 7/8 | 688.850 ms |
| Legacy OPUS-MT, int8Alpha | Bergamot / WSL2 x86_64 | 22.844 | 20.850 | 6/8 | 165.255 ms |

Beam 4 retains roughly 2.1 BLEU over beam 1 for about 6 ms additional host
latency and is the preferred Firefox configuration.

### End-to-end planning view

| Pipeline | BLEU | chrF++ | Critical | Median |
|---|---:|---:|---:|---:|
| ML Kit OCR + ML Kit translation, Android | **39.887** | **33.341** | 7/8 | **129.129 ms** |
| PP-OCRv6 Android + Firefox beam 4 WSL estimate | 35.565 | 29.489 | **8/8** | 429.685 ms |
| PP-OCRv6 host + legacy OPUS-MT float | 22.969 | 21.642 | 7/8 | 1943.443 ms |
| PP-OCRv6 host + legacy OPUS-MT int8 | 23.461 | 21.194 | 6/8 | 1401.718 ms |

## Firefox model package

The current Mozilla model registry snapshot generated at
`2026-07-28T00:37:27Z` identifies an English-to-Chinese `base-memory` release.
`fetch_mozilla_model.py` downloads the four compressed artifacts, verifies both
compressed and decompressed hashes and sizes, validates metadata, and creates a
Bergamot `int8Alpha` configuration.

| Item | Value |
|---|---:|
| Decompressed model | 43,849,787 B |
| Source vocabulary | 806,952 B |
| Target vocabulary | 772,004 B |
| Lexical shortlist | 4,485,184 B |
| Total runtime assets | 49,913,927 B / 47.60 MiB |
| Model SHA-256 | `4e5accc141373565ddc8fa1565bceaa8d0c3482a82cab8131c719ebcc6c2157c` |
| Bergamot initialization on WSL | 511.783 ms |
| Bergamot warm-up on WSL | 528.924 ms |
| WSL process peak RSS | 446,226,432 B / 425.55 MiB |

The repository fetcher intentionally keeps weights in an ignored build
location. Distribution inside an APK will follow after the exact model-weight
terms are recorded alongside the package metadata.

## Human review

| Fixture | ML Kit finding | Firefox beam-4 finding |
|---|---|---|
| Long offline explanation | Preserves the meaning after clause splitting, with repetition. | Fluent; preserves device-local text and offline operation. |
| Literary sentence | ?must endure a wife? reverses the idiom. | Correctly expresses that the wealthy single man wants a wife, though less literary than the reference. |
| Notification recovery | Raw output changes ?translating? to ?averaging?; splitting changes it to ?conversion?. | Correctly preserves service continuity and translation recovery. |
| Version/currency/date | Preserves every protected value. | Preserves `v0.1.0`, `?12,345.67`, and the date. |
| Order line | Natural UI phrasing. | Preserves identifiers and amounts, while ??????? is awkward. |
| Low-contrast quoted words | Preserves the two English quoted tokens. | Translates both quoted tokens to the same Chinese word, losing the contrast. |

The Firefox model is substantially better than the legacy OPUS archive and is
the first credible self-hosted replacement candidate. ML Kit still leads this
small corpus by 5.5 raw BLEU. Literal masking/restoration and an Android arm64
runtime test are required before the translation default changes.

## Other translation paths considered

| Candidate | Mobile fit | Current decision |
|---|---|---|
| Firefox Translations en-zh base-memory + Bergamot | 47.60 MiB assets; already int8Alpha and Bergamot-ready | **Advance to Android JNI/AAR proof of concept** |
| Compact Marian model + CTranslate2 int8 | ARM64 and int8 are supported by CTranslate2; model quality depends on the selected checkpoint | Keep as the second runtime path if Bergamot integration or quality stalls |
| NLLB-200 distilled 600M | Roughly 2.5 GB upstream artifact and CC-BY-NC-4.0 model terms | Research benchmark only for this open-source app target |
| TranslateGemma 4B | Roughly 8.6 GB of weights plus a gated Gemma license | Optional high-quality/server mode, outside continuous on-device capture |
| Legacy `opus-mt-en-zh` + Bergamot | 77.17 MiB int8 package, but 24.835 BLEU and 6/8 critical checks | Retire from the replacement shortlist |

Bergamot remains the shortest path for the Mozilla artifact. CTranslate2 is a
useful fallback runtime, especially for a future compact Marian checkpoint.
Large multilingual models offer broader coverage but have a much higher memory,
storage, and thermal cost than a dedicated English-to-Chinese model.

## Migration decision

1. Keep the new `OcrEngine` abstraction and PP-OCRv6 Android implementation.
2. Keep ML Kit OCR as the release default for the moment; use the benchmark
   build to reduce PP-OCRv6 latency and retained memory before a production
   switch.
3. Next OCR experiment: quantized detector/recognizer plus a Qualcomm QNN or
   tuned CPU build, measured for 15 minutes on the same Xiaomi 15 Pro.
4. Advance Firefox Translations beam 4 as the primary translation replacement
   candidate and build Bergamot for Android arm64.
5. Add literal masking/restoration before translator comparison, then expand to
   at least 100 UI, literary, subtitle, numeric, and low-contrast fixtures.
6. A release-default change requires stable repeated capture, acceptable
   thermal behavior, no critical-check regression, and a signed Release/R8
   device pass.

## Reproducibility

```powershell
# Android OCR candidate
.\gradlew.bat :app:testBenchmarkUnitTest :app:assembleBenchmark
adb install -r .\app\build\outputs\apk\benchmark\app-benchmark.apk
adb shell am start -W -n `
  com.screentranslation.app.benchmark/com.screentranslation.app.benchmark.ModelBenchmarkActivity
adb pull `
  /sdcard/Android/data/com.screentranslation.app.benchmark/files/model-benchmark `
  .\app\build\model-benchmark\device-run
python .\tools\model-benchmark\score.py `
  .\app\build\model-benchmark\device-run\candidate-ppocrv6-small-android.json

# Pinned Firefox model package
python .\tools\model-benchmark\fetch_mozilla_model.py --beam-size 4
```

Generated fixtures, weights, JSON, score files, and runtime logs remain under
ignored build directories. The clean-install acceptance rerun reproduced the
same PP-OCRv6 text and accuracy. Its artifacts are:

| Artifact | Size | SHA-256 |
|---|---:|---|
| `app-benchmark.apk` | 107,503,494 B / 102.523 MiB | `82F4650DEA0BA4368FA3E7D6AD97D1A83B3228E9D93D9F7FA37EC586CD498EC9` |
| `candidate-ppocrv6-small-android.json` | 8,234 B | `B7022B1C7D48556E1DC84A3A57B52D288361CEEB8A62884F3E0FC41C0F1BC350` |
| `candidate-ppocrv6-small-android.scores.json` | 3,606 B | `3736933551344CE446859F76D48FAE820F2F0416FBB0B12658DE546B805F5E70` |

The APK contains only `arm64-v8a`; both ONNX assets are stored uncompressed for
file-backed loading.

Upstream references:

- [PP-OCRv6 pipeline documentation](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/OCR.en.md)
- [PP-OCRv6 small detection ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [PP-OCRv6 small recognition ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)
- [ONNX Runtime Mobile](https://onnxruntime.ai/docs/tutorials/mobile/)
- [ONNX Runtime Android](https://onnxruntime.ai/docs/build/android.html)
- [Mozilla Translations](https://github.com/mozilla/translations)
- [Firefox translation models](https://mozilla.github.io/translations/firefox-models/)
- [Bergamot Translator](https://github.com/browsermt/bergamot-translator)
- [CTranslate2 quantization](https://opennmt.net/CTranslate2/quantization.html)
- [CTranslate2 hardware support](https://opennmt.net/CTranslate2/hardware_support.html)
- [NLLB-200 distilled 600M](https://huggingface.co/facebook/nllb-200-distilled-600M)
- [TranslateGemma 4B](https://huggingface.co/google/translategemma-4b-it)
