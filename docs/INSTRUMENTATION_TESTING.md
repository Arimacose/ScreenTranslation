# Android 16 instrumentation regression

## Purpose

The instrumentation suite protects the capture lifecycle state machine on an
Android 16 runtime. It exercises deterministic callback seams instead of
manufacturing a MediaProjection result token or bypassing the system consent UI.
It is a CI regression gate, not evidence that a signed Release/R8 artifact passed
the Xiaomi 15 Pro acceptance matrix.

## Test-only variant

`onlineInstrumentation` is the only enabled instrumentation variant:

- application ID: `com.screentranslation.app.online.instrumentation`;
- Debug signing and Debug code semantics;
- x86_64 ONNX Runtime for an API 36 Google APIs emulator;
- independent from the ARM64 Lite, Full, Online Debug, Benchmark, and Release APKs.

The ordinary product build types remain ARM64-only. Lite and Full instrumentation
variants are disabled so CI never tries to package their ARM64 native runtimes for
an x86_64 emulator.

## Covered contracts

1. `MainActivity` launches idle and maps the actual notification and overlay
   permission states to the correct UI strings.
2. Region mode advances through start, overlay ready, projection ready, model
   ready, region selection, running, user stop, and destruction.
3. Full-screen incremental mode becomes region-ready without a selection gesture.
4. Rotation invalidates a region selection but preserves full-screen readiness.
5. Screen off/on, captured-content visibility, expanded results, and task removal
   pause, resume, or preserve processing as designed.
6. Projection revocation records a fresh-consent requirement through service
   destruction.
7. App-private files, cache, no-backup, and code-cache roots contain no screenshot
   image or named OCR/capture-history artifact.

The production `ScreenTranslationService` dispatches these same state-machine
events. The tests therefore validate the processor gate used at runtime rather
than a separate test-only copy. Event dispatch is synchronized because display
resize arrives on the capture thread while power, projection, model, and overlay
callbacks may arrive on the main thread.

## Local run

Install an Android 16 / API 36 Google APIs x86_64 system image and start an
emulator, then run:

```bash
./gradlew :app:connectedOnlineInstrumentationAndroidTest
```

When physical devices are also connected, select the emulator explicitly.

Windows PowerShell:

```powershell
$env:ANDROID_SERIAL = "emulator-5554"
.\gradlew.bat :app:connectedOnlineInstrumentationAndroidTest
```

Linux/macOS:

```bash
ANDROID_SERIAL=emulator-5554 \
  ./gradlew :app:connectedOnlineInstrumentationAndroidTest
```

Build the two APKs without running a device:

```bash
./gradlew \
  :app:assembleOnlineInstrumentation \
  :app:assembleOnlineInstrumentationAndroidTest
```

Outputs:

```text
app/build/outputs/apk/online/instrumentation/app-online-instrumentation.apk
app/build/outputs/apk/androidTest/online/instrumentation/app-online-instrumentation-androidTest.apk
app/build/reports/androidTests/connected/instrumentation/flavors/online/
app/build/outputs/androidTest-results/connected/instrumentation/flavors/online/
```

## CI

`.github/workflows/ci.yml` runs the suite in a dedicated Ubuntu job with API 36,
Google APIs, and x86_64. Emulator-runner is pinned to the commit behind its
`v2.38.0` release. HTML and raw Android test results are uploaded even when the
job fails.
