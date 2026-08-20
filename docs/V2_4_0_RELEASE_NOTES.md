# ScreenTranslation v2.4.0

v2.4.0 completes the recognition and Online-quality milestone on top of the v2.3 continuous-reading release. The release source, signed Android packages, SBOMs, license archive, device evidence, and `SHA256SUMS` are bound to one commit and one certificate lineage.

## Which download should I use?

| Need | Direct-install APK | Package |
|---|---|---|
| Offline English/Japanese translation | `ScreenTranslation-v2.4.0-lite.apk` | `com.screentranslation.app` |
| Offline multilingual HY-MT2 Q4 (Experimental) | `ScreenTranslation-v2.4.0-full.apk` | `com.screentranslation.app.full` |
| Your own OpenAI-compatible API | `ScreenTranslation-v2.4.0-online.apk` | `com.screentranslation.app.online` |

APK files install directly on the supported ARM64 Android device. AAB files are store/developer delivery artifacts and are not direct-install packages.

## Recognition and routing

- Mixed English/Japanese/Chinese OCR is split into source, target, and protected spans in linear time; pure-Kanji Japanese UI terms use a bounded lexicon instead of being silently discarded.
- Strict sentinels protect URLs, paths, filenames, identifiers, dates, times, versions, money, and other literal tokens. Missing, duplicated, or changed sentinels reject the translation rather than corrupting visible text.
- Balanced, Small subtitle, and Document OCR profiles have frozen detector/recognizer settings per capture mode.
- A bounded second OCR pass improves small/low-confidence text, merges only non-duplicate blocks, and records profile/path/timing counters without OCR content.

## Online BYOK

- Fetched model catalogs retain exact model IDs and optional friendly name/owner metadata; up to 1,000 entries can be searched locally.
- The final `/models` and `/chat/completions` URLs are visible before use.
- Model fetch and translation test each have an explicit cancel action.
- The diagnostics view reports selected model, HTTP status, latency, input/output character counts, attempts, and provider token usage when present.
- User-facing failures have stable Chinese summaries; technical details are opt-in and redact Bearer values, API keys, tokens, and query secrets.
- Full-screen Online translation batches at most 8 blocks / 6,000 characters with opaque IDs. Missing, duplicate, unexpected, malformed, or blank results are rejected. Failed multi-block calls split at most three levels and publish only after a complete validated result, preserving latest-wins/source-match behavior.

## Verification

- Lite, Full, and Online execute the same injected start → capture → OCR → translate → overlay → copy → stop journey with fixed English, Japanese, and mixed-script fixtures.
- The journey proves release of projection, capture source, OCR engine, translation backend, and overlay host, and records only content-free counters.
- CI includes the accurately named `Injected capture E2E and host macrobenchmarks` job and machine-readable thresholds in `v2_4_macrobenchmark_thresholds.json`.
- Signed physical-device acceptance remains mandatory for Android MediaProjection consent, HyperOS overlay behavior, three UI styles at font scales 1.0/1.3/2.0, small-text OCR, stale-block rejection, stop cleanup, and privacy scans.

## Release assets

The Release contains exactly 12 flat files:

1. three signed APKs;
2. three signed AABs;
3. three edition CycloneDX SBOM JSON files;
4. `LICENSE`;
5. `ScreenTranslation-v2.4.0-THIRD-PARTY.zip`;
6. `SHA256SUMS`.

The real-device demonstration and complete raw acceptance directory are attached separately as evidence and referenced by the acceptance issue comment; they are not application binaries.

## Supported baseline and known boundaries

- Android 16 / API 36; release ABI `arm64-v8a`; compile SDK 37.
- Physical baseline: Xiaomi 15 Pro (`2410DPN6CC`) on the recorded HyperOS build.
- Full remains explicitly Experimental because of its model size, memory, and latency.
- Online behavior depends on the configured provider/model and sends recognized text to that endpoint; screenshots and coordinates remain local.
- Real MediaProjection consent cannot be injected by CI, so signed-device evidence is part of the release gate rather than inferred from JVM or emulator results.
