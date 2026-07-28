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
  com.screentranslation.app.benchmark/.ModelBenchmarkActivity

adb pull `
  /sdcard/Android/data/com.screentranslation.app.benchmark/files/model-benchmark `
  .\app\build\model-benchmark\device-run
```

The run is complete when `baseline-mlkit.done` appears. A failed run writes
`baseline-mlkit-error.txt`.

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

Do not commit downloaded models, generated fixtures, JSON results, or runtime
logs. Keep them under ignored build directories and commit only the harness
and summarized benchmark report.
