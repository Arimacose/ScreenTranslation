# Bergamot Android PoC

This directory contains a reproducible Android ARM64 proof of concept for
pinned Firefox Translations models and the Bergamot runtime. It builds a native
command-line benchmark, runs one or more translation stages through `adb`, and
emits the same result schema used by the ML Kit translation benchmark.

The PoC proves that Bergamot's native translation core executes on the target
Android linker and CPU. It is intentionally separate from the production APK:
a JNI lifecycle wrapper, model delivery, and signed Release/R8 acceptance are
still required before it can become an application engine.

The initial English Xiaomi 15 Pro result is recorded in
[`docs/BERGAMOT_ANDROID_POC_2026-07-29.md`](../../docs/BERGAMOT_ANDROID_POC_2026-07-29.md).
The expanded English and Japanese comparison is recorded in
[`docs/TRANSLATION_BENCHMARK_EN_JA_ZH_2026-07-29.md`](../../docs/TRANSLATION_BENCHMARK_EN_JA_ZH_2026-07-29.md).

## Pinned inputs

| Input | Pin |
|---|---|
| Bergamot | `9271618ebbdc5d21ac4dc4df9e72beb7ce644774` (`v0.4.5+9271618`) |
| Android NDK | r23b / `23.1.7779620` |
| Android ABI | `arm64-v8a` |
| Android API used for native build | 28 |
| Firefox models | `en` → `zh` and `ja` → `en`, `base-memory`, `int8Alpha` |
| Decoder | beam size 4 |

The build follows Bergamot's own Android ARM64 configuration: Ruy, NEON, CPU
inference, and static libc++.

## Prerequisites

- Linux or WSL with Git, CMake, Ninja, and a C++ toolchain;
- Android NDK r23b extracted locally;
- Python 3 on Windows or Linux;
- an Android ARM64 device visible to `adb`;
- a translation-only ML Kit result produced by the benchmark APK.

The NDK directory is external to the repository. Model weights and all
generated results stay under ignored `app/build` directories.

## 1. Fetch and verify models

From PowerShell:

```powershell
python .\tools\model-benchmark\fetch_mozilla_model.py --pair en-zh --beam-size 4
python .\tools\model-benchmark\fetch_mozilla_model.py --pair ja-en --beam-size 4
```

The fetcher pins upstream metadata, verifies every compressed and decompressed
file, and creates:

```text
app/build/model-benchmark/mozilla-en-zh-base-memory-2026-07-28/
app/build/model-benchmark/mozilla-ja-en-base-memory-2026-07-29/
```

## 2. Build the Android ARM64 benchmark

From Linux or WSL at the repository root:

```bash
export ANDROID_NDK_HOME="$HOME/android-ndk-r23b"
export JOBS=2
tools/bergamot-android-poc/build.sh
```

The script checks the exact NDK revision, checks out Bergamot at the pinned
commit with recursive submodules, builds the target, strips it, and writes a
manifest. The retained binary is:

```text
app/build/bergamot-android-poc/bin/arm64-v8a/bergamot-android-benchmark
```

## 3. Produce the paired ML Kit baseline

Build and install the benchmark variant:

```powershell
.\gradlew.bat --no-daemon :app:testBenchmarkUnitTest :app:assembleBenchmark
adb install -r .\app\build\outputs\apk\benchmark\app-benchmark.apk
adb shell am start -W -n `
  com.screentranslation.app.benchmark/com.screentranslation.app.benchmark.ModelBenchmarkActivity `
  --ez translation_only true
```

Wait for `translation-mlkit-en-zh.done`, then pull the application benchmark
directory:

```powershell
adb pull `
  /sdcard/Android/data/com.screentranslation.app.benchmark/files/model-benchmark `
  .\app\build\model-benchmark\device-run
```

The paired baseline file is `translation-mlkit-en-zh-android.json`. It bypasses
OCR with gold source strings so translation quality and latency are isolated.
For Japanese, add:

```powershell
--es source_language ja `
--es target_language zh `
--es fixture_suite ja-zh-diverse-v1
```

and wait for `translation-mlkit-ja-zh.done`.

## 4. Run Bergamot on the same device and fixtures

```powershell
python .\tools\bergamot-android-poc\run_device.py `
  .\app\build\model-benchmark\device-run\translation-mlkit-en-zh-android.json `
  --binary `
    .\app\build\bergamot-android-poc\bin\arm64-v8a\bergamot-android-benchmark `
  --model-dir `
    .\app\build\model-benchmark\mozilla-en-zh-base-memory-2026-07-28 `
  --service blocking `
  --workers 1 `
  --repetitions 3 `
  --output `
    .\app\build\model-benchmark\device-run\translation-bergamot-en-zh-android.json
```

Mozilla's current production registry has no direct `ja` → `zh` model. Run the
measured `ja` → `en` → `zh` cascade by repeating `--model-dir` in translation
order:

```powershell
python .\tools\bergamot-android-poc\run_device.py `
  .\app\build\model-benchmark\device-run\translation-mlkit-ja-zh-android.json `
  --binary `
    .\app\build\bergamot-android-poc\bin\arm64-v8a\bergamot-android-benchmark `
  --model-dir `
    .\app\build\model-benchmark\mozilla-ja-en-base-memory-2026-07-29 `
  --model-dir `
    .\app\build\model-benchmark\mozilla-en-zh-base-memory-2026-07-28 `
  --service blocking `
  --workers 1 `
  --repetitions 3 `
  --output `
    .\app\build\model-benchmark\device-run\translation-bergamot-ja-en-zh-android.json
```

`run_device.py` performs the following checks before accepting a result:

1. validates every v2 model manifest, runtime file, size, and SHA-256 value;
2. requires the model stages to form the baseline language route exactly;
3. records the device identity and state;
4. pushes the binary, models, configs, and generated TSV groups;
5. verifies remote SHA-256 values;
6. runs raw and app-`ClauseSplitter` inputs;
7. requires every repetition to produce identical final and intermediate text;
8. records per-stage load, warm-up, inference, RSS, and high-water memory;
9. writes a candidate JSON compatible with the shared scorer.

Score both engines:

```powershell
python .\tools\model-benchmark\score.py `
  .\app\build\model-benchmark\device-run\translation-mlkit-en-zh-android.json
python .\tools\model-benchmark\score.py `
  .\app\build\model-benchmark\device-run\translation-bergamot-en-zh-android.json
```

For a sustained maximum-throughput pass, change `--repetitions` to `100` and
write to a separate output file.

## Service choice

`blocking` is the retained PoC mode. It uses one long-lived
`BlockingService` and serializes requests, which matches the intended
application-owned background worker.

The harness also exposes `--service async` for diagnosis. On the target device,
concurrent clause batches produced a different Dickens result on one of three
repetitions. That mode is therefore excluded from the candidate architecture
until its ordering and shared-state behavior are resolved.

## Production integration boundary

A production implementation should:

- expose a narrow JNI translation interface instead of spawning this binary;
- keep one engine on a single background dispatcher;
- load the model only while screen translation is active and unload it after
  an idle timeout;
- checksum downloaded or asset-delivered model files before activation;
- serialize OCR and translation pressure or isolate the translator process;
- add cancellation and a latest-frame queue rather than translating every
  captured frame;
- preserve the existing ML Kit engine behind a runtime feature flag;
- pass signed Release/R8, long-session, memory-pressure, and thermal tests.

## Licensing

Bergamot and the tested Firefox Translations model are distributed under the
Mozilla Public License 2.0. Bergamot also contains third-party submodules with
their own notices. Any shipped native library and model package must include
the applicable upstream license and notice material; see
[`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md).
