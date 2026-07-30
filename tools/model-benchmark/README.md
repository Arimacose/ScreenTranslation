# Model benchmark

This harness measures OCR and translation separately before comparing complete
screen-to-translation output. That separation prevents an OCR typo from being
misdiagnosed as a translation-model failure.

## Layers

1. **OCR**: generated screen-like PNG to recognized English; report CER, WER,
   exact cases, and inference latency.
2. **Translation raw**: gold source text directly to the translation engine.
3. **Translation pipeline**: gold source text through the app's current
   `ClauseSplitter`, then to the translation engine.
4. **End to end**: rendered PNG through OCR, block merging, clause splitting,
   and translation.

The benchmark build has the application ID
`com.screentranslation.app.benchmark`, so it installs beside a production APK.
The activity and exported benchmark entrypoint only exist in the `benchmark`
source set.

## Build and run

```powershell
.\gradlew.bat :app:assembleBenchmark
adb install -r .\app\build\outputs\apk\benchmark\app-benchmark.apk
adb shell am start -W -n `
  com.screentranslation.app.benchmark/com.screentranslation.app.benchmark.ModelBenchmarkActivity

adb pull `
  /sdcard/Android/data/com.screentranslation.app.benchmark/files/model-benchmark `
  .\app\build\model-benchmark\device-run
```

The default run is OCR-only so a first-time ML Kit translation-model download
does not block OCR comparison. It writes `baseline-mlkit.json` and
`candidate-ppocrv6-small-android.json`. The run is complete when
`baseline-mlkit.done` appears. A failed run writes `baseline-mlkit-error.txt`.

Add `--ez include_translation true` to the `am start` command when the paired
ML Kit translation model is already prepared and the full pipeline is needed.

To isolate translation from OCR and produce the paired Android baseline for a
candidate runtime:

```powershell
adb shell am start -W -n `
  com.screentranslation.app.benchmark/com.screentranslation.app.benchmark.ModelBenchmarkActivity `
  --ez translation_only true
```

This mode loads the requested suite from
`app/src/benchmark/assets/translation-fixtures.json`, uses its gold source
strings, and records model preparation, warm-up, translation latency, and
process memory snapshots. The retained suites are:

- `en-zh-diverse-v2`: 40 English-to-Chinese cases;
- `ja-zh-diverse-v1`: 40 Japanese-to-Chinese cases.

The default writes `translation-mlkit-en-zh-android.json`. Run Japanese to
Chinese explicitly:

```powershell
adb shell am start -W -n `
  com.screentranslation.app.benchmark/com.screentranslation.app.benchmark.ModelBenchmarkActivity `
  --ez translation_only true `
  --es source_language ja `
  --es target_language zh `
  --es fixture_suite ja-zh-diverse-v1 `
  --ei translation_repetitions 3
```

The Japanese completion and error files are
`translation-mlkit-ja-zh.done` and
`translation-mlkit-ja-zh-error.txt`; each language pair has independent
result markers.

The default is three repetitions. A sustained run accepts up to 100:

```powershell
adb shell am start -W -n `
  com.screentranslation.app.benchmark/com.screentranslation.app.benchmark.ModelBenchmarkActivity `
  --ez translation_only true `
  --ei translation_repetitions 100
```

## Score

```powershell
python -m pip install -r .\tools\model-benchmark\requirements.txt
python .\tools\model-benchmark\score.py `
  .\app\build\model-benchmark\device-run\baseline-mlkit.json
```

The scorer emits:

- OCR corpus and per-case CER/WER;
- Chinese BLEU and chrF++ for raw, split-pipeline, and end-to-end output;
- latency summaries;
- per-category translation scores and critical meaning checks for negation,
  conditions, identifiers, quantities, dates, safety-domain text, idioms,
  politeness, and word sense.

BLEU/chrF++ use every available project-authored reference for a fixture and
remain directional comparison signals rather than standalone acceptance
criteria. Critical checks and human review remain release gates.

## Candidate-engine contract

A candidate adapter should emit the same JSON schema and reuse the exact PNG
fixtures and gold strings from `baseline-mlkit.json`. Do not redraw or reflow
fixtures between engines. Model packaging, warm-up, thread count, precision,
device build, and repetitions must be recorded in the result metadata.

## PP-OCRv6 host reference

Install PaddleOCR in an isolated environment, then reuse the PNG paths already
recorded in the device baseline:

```powershell
python .\tools\model-benchmark\run_ppocr.py `
  .\app\build\model-benchmark\device-run\baseline-mlkit.json `
  --tier small `
  --repetitions 3 `
  --output .\app\build\model-benchmark\ppocr-small\candidate.json
```

This is a pre-integration quality check. Host latency must not be compared with
Android latency.

## PP-OCRv6 Android production engine and ML Kit baseline

Debug and Release use the PP-OCRv6 Android engine. The `benchmark` variant adds
the former ML Kit OCR implementation so both engines can still run against the
same fixtures. `preparePpOcrv6Assets` downloads the official PP-OCRv6 small
detector and recognizer at pinned upstream revisions, verifies their SHA-256
values, and extracts the pinned recognition dictionary for every variant.

The retained Xiaomi 15 Pro configuration uses the ORT CPU provider, four
intra-op threads, recognition batch size 1, a 640 px detector long-side cap,
and disabled memory-pattern/CPU-arena allocation. The report in
`docs/MODEL_BENCHMARK_2026-07-28.md` records each tuning step and the native
XNNPACK failure observed on the target HyperOS build.

Score both paired outputs after pulling them:

```powershell
python .\tools\model-benchmark\score.py `
  .\app\build\model-benchmark\device-run\baseline-mlkit.json
python .\tools\model-benchmark\score.py `
  .\app\build\model-benchmark\device-run\candidate-ppocrv6-small-android.json
```

## OPUS-MT quality reference

`run_opus_mt.py` uses the Hugging Face float model to establish quality before
native conversion:

```powershell
python .\tools\model-benchmark\run_opus_mt.py `
  .\app\build\model-benchmark\device-run\baseline-mlkit.json `
  --ocr-json .\app\build\model-benchmark\ppocr-small\candidate.json `
  --threads 4 `
  --repetitions 3 `
  --output .\app\build\model-benchmark\opus-float\candidate.json
```

## Older OPUS archives and Bergamot

Older OPUS-MT archives can contain two 32k SentencePiece tokenizers plus one
shared Marian YAML vocabulary. Bergamot consumes SentencePiece IDs directly,
so the stock files are not interchangeable. Build source- and target-side
SentencePiece files aligned to all shared Marian IDs:

```powershell
python -m pip install sentencepiece protobuf
python .\tools\model-benchmark\build_bergamot_vocab.py `
  --vocab-yaml MODEL\vocab.yml `
  --source-spm MODEL\source.spm `
  --target-spm MODEL\target.spm `
  --source-output MODEL\source.aligned.spm `
  --target-output MODEL\target.aligned.spm `
  --verify-json .\app\build\model-benchmark\device-run\baseline-mlkit.json
```

The command exits non-zero if aligned source IDs differ from the original
external SentencePiece-to-Marian mapping on any verification fixture.

After building Bergamot's Python binding on Linux, `run_bergamot.py` keeps one
model instance alive so per-request measurements exclude model loading:

```bash
python tools/model-benchmark/run_bergamot.py \
  app/build/model-benchmark/device-run/baseline-mlkit.json \
  --ocr-json app/build/model-benchmark/ppocr-small/candidate.json \
  --binding-dir BERGAMOT/build-native/bindings/python \
  --config MODEL/decoder.bergamot.yml \
  --model-files MODEL/model.intgemm8.bin \
    MODEL/source.aligned.spm MODEL/target.aligned.spm \
  --workers 1 \
  --repetitions 3 \
  --output app/build/model-benchmark/bergamot/candidate.json
```

## Current Firefox Translations candidate

The preferred self-hosted candidate is the current Mozilla Firefox
Translations `base-memory` models rather than the older Helsinki-NLP archive.
Fetch a pinned package and produce a beam-4 Bergamot configuration:

```powershell
python .\tools\model-benchmark\fetch_mozilla_model.py --pair en-zh --beam-size 4
python .\tools\model-benchmark\fetch_mozilla_model.py --pair ja-en --beam-size 4
```

The fetcher verifies compressed downloads, decompressed runtime files, sizes,
hashes, and upstream metadata. It writes v2 manifests with explicit runtime
file maps under ignored `app/build/model-benchmark` directories.

After building Bergamot's Python binding in WSL, run the candidate:

```bash
MODEL=app/build/model-benchmark/mozilla-en-zh-base-memory-2026-07-28
python tools/model-benchmark/run_bergamot.py \
  app/build/model-benchmark/device-run/baseline-mlkit.json \
  --ocr-json \
    app/build/model-benchmark/device-run/candidate-ppocrv6-small-android.json \
  --binding-dir BERGAMOT/build-native/bindings/python \
  --config "$MODEL/decoder.bergamot-beam4.yml" \
  --model-files \
    "$MODEL/model.enzh.intgemm.alphas.bin" \
    "$MODEL/srcvocab.enzh.spm" \
    "$MODEL/trgvocab.enzh.spm" \
    "$MODEL/lex.50.50.enzh.s2t.bin" \
  --engine-label \
    "Mozilla Firefox Translations en-zh base-memory int8Alpha / Bergamot" \
  --target-prefix "" \
  --bergamot-commit 9271618ebbdc5d21ac4dc4df9e72beb7ce644774 \
  --workers 1 \
  --repetitions 3 \
  --output \
    app/build/model-benchmark/firefox-en-zh-beam4/candidate.json
```

Run `score.py` against the emitted JSON and compare raw, clause-split, and
end-to-end sections. Beam 4 is the retained configuration; the seven-fixture
gate measured about 2.1 more raw BLEU than beam 1 for roughly 6 ms additional
WSL host latency.

Downloaded models, generated fixtures, JSON results, and runtime logs belong
under ignored build directories. Commit the harness and summarized benchmark
report only.

## Hy-MT2 multilingual-to-Chinese quality reference

`run_hymt2.py` starts one pinned `llama-server`, verifies the official model
SHA-256 before loading it, and evaluates the same raw and app
`ClauseSplitter` plans exported by the ML Kit Android benchmark. Model files
and generated JSON stay under ignored `app/build` or the shared D-drive model
cache; they are not committed.

The first mobile-oriented quality gate uses the official Apache-2.0
`tencent/Hy-MT2-1.8B-GGUF` Q4_K_M checkpoint:

- model revision `1cd5208700acedef4ef93019b6cfc148b8522d45`;
- model SHA-256
  `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`;
- llama.cpp tag `b10181`, commit `caa596ab3`;
- CPU-only host and Android ARM64 quality references, 2K context,
  deterministic greedy decoding.

Example for the English-to-Chinese suite:

```powershell
python .\tools\model-benchmark\run_hymt2.py `
  BASELINE\translation-mlkit-en-zh-android.json `
  --server-executable D:\DevTools\llama.cpp\b10181\cpu-x64\llama-server.exe `
  --model D:\DevCache\HuggingFace\manual\tencent\Hy-MT2-1.8B-GGUF\1cd5208700acedef4ef93019b6cfc148b8522d45\Hy-MT2-1.8B-Q4_K_M.gguf `
  --quantization Q4_K_M `
  --log-directory .\app\build\model-benchmark\hymt2\en-zh-logs `
  --output .\app\build\model-benchmark\hymt2\translation-hymt2-en-zh-host.json

python .\tools\model-benchmark\score.py `
    .\app\build\model-benchmark\hymt2\translation-hymt2-en-zh-host.json
```

For a server already running on Android, forward its loopback port and replace
`--server-executable` with:

```powershell
adb forward tcp:18086 tcp:18086

python .\tools\model-benchmark\run_hymt2.py `
  BASELINE\translation-mlkit-en-zh-android.json `
  --external-server-url http://127.0.0.1:18086 `
  --model D:\DevCache\HuggingFace\manual\tencent\Hy-MT2-1.8B-GGUF\1cd5208700acedef4ef93019b6cfc148b8522d45\Hy-MT2-1.8B-Q4_K_M.gguf `
  --threads 8 `
  --quantization Q4_K_M `
  --log-directory .\app\build\model-benchmark\hymt2\en-zh-device-logs `
  --output .\app\build\model-benchmark\hymt2\translation-hymt2-en-zh-android.json
```

The verified Q4 Android result and the ML Kit/Bergamot comparison are in
[`docs/HY_MT2_TRANSLATION_BENCHMARK_2026-07-30.md`](../../docs/HY_MT2_TRANSLATION_BENCHMARK_2026-07-30.md).
The official 2-bit GGUF currently depends on draft llama.cpp PR `#19357` and
does not load in the pinned upstream release. The 1.25-bit STQ variant depends
on open PR `#22836`; treat its custom ARM runtime, translation quality,
latency, thermals, and lifecycle as separate acceptance gates.

### Legacy STQ GGUF compatibility

The official `tencent/Hy-MT2-1.8B-1.25Bit-GGUF` revision
`9df5c824a00a744fb0512a29c640466f4d97dfb0` was produced before the STQ pull
request was rebased over llama.cpp's new Q2_0 type. The official file uses
file/tensor types `41/42`; PR `#22836` head `7e74b829` expects STQ at `42/43`.
Loading the untouched file with that head therefore interprets its tensors as
Q2_0 and reports an offset mismatch.

`retag_legacy_stq_gguf.py` creates a separate compatibility copy. It:

- pins and verifies official source SHA-256
  `cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93`;
- requires the `hunyuan-dense` architecture, legacy file type, exactly 224
  legacy tensor tags, and no current STQ tags;
- changes only one file-type field and the 224 tensor-type fields;
- verifies identical file size and identical tensor-payload SHA-256;
- writes an optional JSON manifest containing all source/output hashes.

```powershell
python .\tools\model-benchmark\retag_legacy_stq_gguf.py `
  --input D:\MODELS\Hy-MT2-1.8B-1.25Bit.gguf `
  --output D:\MODELS\Hy-MT2-1.8B-1.25Bit-STQ1_0-type43.gguf `
  --manifest .\app\build\model-benchmark\hymt2-stq\retag-manifest.json
```

The pinned output used by the Android benchmark is 461,860,800 bytes with
SHA-256 `e482a38ceaaf8420573483c96ddc8449922b5f5de6a8023b70316e65d41e6de7`.
Its tensor payload SHA-256 remains
`5ab383ce54adddcbcfbb400aacb5b457005c43f71304291889a61074f5686b2d`.
Remove this compatibility step when the model publisher refreshes the GGUF for
the final upstream type IDs.

Run all benchmark utility tests, including the synthetic GGUF header/payload
checks:

```powershell
python -m unittest discover -s .\tools\model-benchmark -p "test_*.py" -v
```

## Bergamot Android ARM64 proof of concept

The native Android harness under `tools/bergamot-android-poc` consumes
one of the pair-specific ML Kit JSON files, runs one or more pinned Firefox
Translations stages on the same device and inputs, and emits a scorer-compatible
candidate. It verifies the complete model-language chain, local and remote
hashes, and rejects final or intermediate output that changes between
repetitions.

See:

- [`tools/bergamot-android-poc/README.md`](../bergamot-android-poc/README.md)
  for the exact build and ADB workflow;
- [`docs/BERGAMOT_ANDROID_POC_2026-07-29.md`](../../docs/BERGAMOT_ANDROID_POC_2026-07-29.md)
  for the Xiaomi 15 Pro quality, memory, latency, thermal, and feasibility
  decision from the initial English PoC;
- [`docs/TRANSLATION_BENCHMARK_EN_JA_ZH_2026-07-29.md`](../../docs/TRANSLATION_BENCHMARK_EN_JA_ZH_2026-07-29.md)
  for the expanded 40-case English and 40-case Japanese comparison.
