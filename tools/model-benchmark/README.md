# Model benchmark

This harness measures OCR and translation separately before comparing complete
screen-to-translation output. That separation prevents an OCR typo from being
misdiagnosed as a translation-model failure.

## Layers

1. **OCR**: generated screen-like PNG to recognized English; report CER, WER,
   exact cases, and inference latency.
2. **Translation raw**: gold English directly to the translation engine.
3. **Translation pipeline**: gold English through the app's current
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

This mode uses the gold source strings, adds dynamic alpha/beta samples and the
611-character Dickens fixture, and writes `translation-mlkit-android.json`.
It records model preparation, warm-up, translation latency, and process memory
snapshots.

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
- critical meaning checks for negation, offline operation, service recovery,
  identifiers, quantities, dates, and amounts.

BLEU/chrF++ use one reference per fixture, so they are directional comparison
signals rather than standalone acceptance criteria. Critical checks and human
review remain release gates.

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
Translations English-to-Chinese `base-memory` model rather than the older
Helsinki-NLP archive. Fetch its pinned package and produce a beam-4 Bergamot
configuration:

```powershell
python .\tools\model-benchmark\fetch_mozilla_model.py --beam-size 4
```

The fetcher verifies compressed downloads, decompressed runtime files, sizes,
hashes, and upstream metadata. It writes the model only under the ignored
`app/build/model-benchmark/mozilla-en-zh-base-memory-2026-07-28` directory.

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

## Bergamot Android ARM64 proof of concept

The native Android harness under `tools/bergamot-android-poc` consumes
`translation-mlkit-android.json`, runs the pinned Firefox Translations model on
the same device and inputs, and emits a scorer-compatible candidate. It verifies
local and remote hashes and rejects output that changes between repetitions.

See:

- [`tools/bergamot-android-poc/README.md`](../bergamot-android-poc/README.md)
  for the exact build and ADB workflow;
- [`docs/BERGAMOT_ANDROID_POC_2026-07-29.md`](../../docs/BERGAMOT_ANDROID_POC_2026-07-29.md)
  for the Xiaomi 15 Pro quality, memory, latency, thermal, and feasibility
  decision.
