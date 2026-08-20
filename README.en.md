# ScreenTranslation

[简体中文](README.md) | **English**

> v2.4.0 includes three switchable visual languages: an Apple-inspired default,
> MIUIX, and Material 3 with optional Monet dynamic colors. See
> [UI styles and implementation boundaries](docs/UI_STYLES.md).

![Static Apple, MIUIX, and Material 3 design preview](docs/assets/ui-style-comparison.png)

ScreenTranslation is a native screen OCR and translation app currently targeted at
**Android 16 (API 36), Xiaomi 15 Pro, and HyperOS**. A user explicitly starts each
session and approves Android's `MediaProjection` prompt. Screenshots are processed in
memory by PP-OCRv6-small; translation is provided by one of three isolated editions.

> Project status: `v2.4.0`, for the accepted single device/ROM baseline.
> `minSdk` and `targetSdk` are 36, `compileSdk` is
> 37, and release APKs are currently ARM64-only. Source code is Apache-2.0; bundled and
> downloaded third-party components retain their own licenses.

## Which APK should I install?

| Your need | Direct-install file | Notes |
|---|---|---|
| Offline English/Japanese translation with the smallest stable edition | `ScreenTranslation-v2.4.0-lite.apk` | Recommended for most users; upgrades the historical Lite application ID |
| Offline direct multilingual translation with a larger Experimental model | `ScreenTranslation-v2.4.0-full.apk` | Separate app; downloads the approximately 1.06 GiB HY-MT2 Q4 model |
| Your own OpenAI-compatible HTTPS API | `ScreenTranslation-v2.4.0-online.apk` | Separate app; OCR stays local and only stable text is sent to the selected service |

An **APK** installs directly on a phone. An **AAB** is a Play Store/developer upload artifact and
is not directly installed by a phone's file manager. Most users should choose an APK above and
verify it against `SHA256SUMS` from the same Release.

### Five-step first run

1. Install one APK, open it, and confirm its edition and data-flow description.
2. Prepare the local model, or fetch and select a model in Online settings.
3. Grant overlay permission, tap Start, and approve whole-screen sharing in Android's prompt.
4. Drag a region by default; switch to Full-screen incremental overlay (Experimental) for continuous reading.
5. Stop from the overlay controls or persistent notification; projection, frame reader, OCR, translation, and overlays are released together.

## Physical-device workflow evidence

![ScreenTranslation workflow preview](docs/assets/demo-preview.gif)

[Play the v2.4.0 Xiaomi 15 Pro acceptance recording (MP4)](docs/assets/v2.4.0-xiaomi15pro-demo.mp4)

This animation is a deterministic interaction preview. The MP4 was recorded with
`v2.4.0-lite-benchmark` on a Xiaomi 15 Pro running Android 16 / HyperOS
`OS3.0.304.0.WOBCNXM` as physical-device interaction pre-acceptance before signed candidates were
produced. Installed hashes, sustained-run evidence, device properties, and logs for the final signed
APKs are published with the Release
and in [`docs/V2_4_0_DEVICE_ACCEPTANCE.md`](docs/V2_4_0_DEVICE_ACCEPTANCE.md). Historical signed-Release
evidence and the complete device gate are documented in
[`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md). The final candidate is frozen as a signed
acceptance Artifact carrying its source SHA; sustained-capture evidence is recorded on Issues
#38 and #39,
and the release workflow promotes those exact bytes to GitHub Releases.

## Editions

| Edition | Application ID | Translation | Language scope |
|---|---|---|---|
| Lite | `com.screentranslation.app` | Bergamot / Firefox Translations | English → Chinese; Japanese → English → Chinese |
| Full Experimental | `com.screentranslation.app.full` | HY-MT2 1.8B Q4_K_M through llama.cpp | Supported source languages → Simplified Chinese |
| Online BYOK | `com.screentranslation.app.online` | User-selected OpenAI-compatible API model | Determined by the selected provider/model |

All three editions use the same PP-OCRv6-small ONNX OCR pipeline. OCR assets are bundled
in the APK; Lite and Full download pinned translation weights on demand. Online sends only
stable region text or stable changed blocks—not screenshots or coordinates—to the HTTPS
endpoint configured by the user.

Model preparation runs as a WorkManager task outside the Activity, with network constraints,
storage preflight, pause/resume/cancel, `.part` continuation, measured speed, and ETA. The home
screen exposes one readiness-driven next action. An optional idle notification and Quick
Settings tile provide user-initiated entry points, while the in-app trust center presents the
edition identity, data flow, licenses, privacy policy, and security policy offline.

Every backend now publishes a typed profile for language routes and pivots, input limits,
model storage, per-request cancellation, close-time PREEMPT/DRAIN behavior, route-keyed
latency/memory observations, and attribution. The daily
middle-tier thresholds and the fail-closed HY-MT2 STQ gate are documented in
[`docs/TRANSLATION_PROVIDER_PROFILES.md`](docs/TRANSLATION_PROVIDER_PROFILES.md). STQ remains
outside every edition factory while llama.cpp PR #22836 is OPEN. Promotion additionally
requires the repository verifier to prove that the real gitlink contains the eventual merge and
to hash the exact runnable GGUF and transformation manifest. The source declaration and the
strict record recomputed from live PR/gitlink/artifact state are pinned in
[`docs/evidence/hymt2-stq-admission-source-v1.json`](docs/evidence/hymt2-stq-admission-source-v1.json)
and [`docs/evidence/hymt2-stq-admission-v1.json`](docs/evidence/hymt2-stq-admission-v1.json).
CI regenerates the record, sidecar, and Kotlin source. Application code consumes only that fixed
record; caller-supplied ancestor/verified booleans and `NOT_MEASURED` strings are rejected, while
currently missing Release and thermal measurements remain JSON `null` and block selection.

## Current features

- User-approved default-display capture through `MediaProjection`; no Accessibility Service.
- **Region mode (default):** drag a transparent selector and read results in a compact,
  expandable overlay panel.
- **Full-screen incremental overlay (Experimental):** a `3 × 6` dirty-tile pass limits OCR
  to changed screen areas, block identity is tracked across frames, and translated text is
  positioned immediately above the corresponding recognized source box.
- Block-level two-observation stability and a single-active translation queue.
- Adaptive capture interval: returns to the configured active rate after changes and backs
  off toward 2 seconds on static content.
- Deterministic recovery for high-confidence standalone-block endings, paragraph boundaries,
  and missing closing punctuation while single visual line wraps remain unchanged; URLs,
  email addresses, dates, amounts, decimals, and versions
  are protected first and restored byte-for-byte.
- Copy original text and translated text independently from the region result panel.
- Model management screen with status, current/expected size, pinned revision, refresh,
  direct preparation of the currently selected language model, and deletion of downloaded
  or partial translation models. After preparation, the main action becomes a disabled
  **Ready** button; Activity recreation first re-observes the current app-private artifact or
  configuration identity, while a cold process and service startup still run the complete pinned
  SHA-256 verifier. Changing the language pair or Online configuration restores the action.
- Online model discovery through `GET /models`; users select an exact returned model ID.
- Online HTTP policy with actionable 401/403, 429, timeout, DNS, TLS, endpoint/model, and
  response-format errors. HTTP 408/429/502/503/504 and recoverable generic I/O failures
  receive one bounded retry; generation timeouts, DNS failures, and TLS failures do not.
- Foreground-service notification for stop/restart, Android 16 lifecycle handling, and
  target-specific HyperOS overlay and battery-policy settings paths.

## Architecture

```text
MediaProjection -> ImageReader -> latest frame
    Region mode:
      crop/mask -> PP-OCRv6 -> whole-region stability
                -> clause plan / latest-wins online coordinator
                -> compact overlay

    Full-screen Experimental:
      luminance tile diff -> dirty tiles + verification pass
      -> PP-OCRv6 line boxes -> cross-frame block tracker
      -> one changed block at a time -> geometry-aware secure overlay
```

The full-screen translation layer is non-touchable and uses `FLAG_SECURE` so it is designed
to stay visible to the user while remaining outside subsequent projection frames. Whether
the target HyperOS build honors that behavior for `TYPE_APPLICATION_OVERLAY` is an explicit
pending device gate; region mode remains the default rollback path.

Core design notes:

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/FULL_SCREEN_INCREMENTAL_DESIGN.md`](docs/FULL_SCREEN_INCREMENTAL_DESIGN.md)
- [`docs/ONLINE_TRANSLATION_DESIGN.md`](docs/ONLINE_TRANSLATION_DESIGN.md)
- [`docs/TRANSLATION_PROVIDER_PROFILES.md`](docs/TRANSLATION_PROVIDER_PROFILES.md)
- [`docs/TRANSLATION_QUALITY_REGRESSION.md`](docs/TRANSLATION_QUALITY_REGRESSION.md) —
  pinned public English/Japanese-to-Chinese fixtures, strict canonical-join candidate
  evidence with 90%-suite format-only replay detection, executable edition thresholds,
  category/tag protected-span gates, raw-rating recomputation bound to both candidate
  and incumbent outputs while reviewer identities remain unauthenticated, hash-pinned
  current-checkout Kotlin Online evidence (not external attestation), and fail-closed
  admission until a canonical incumbent pin, authenticated reviewers, and fresh/attested
  runner provenance all exist.
- [`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md)
- [`docs/ROADMAP.md`](docs/ROADMAP.md)
- [`PRIVACY.md`](PRIVACY.md)

## Toolchain

| Component | Version/configuration |
|---|---|
| Android Gradle Plugin | 9.3.1 |
| Gradle Wrapper | 9.6.1 |
| JDK | 17 |
| compile / min / target SDK | 37 / 36 / 36 |
| Production ABI | `arm64-v8a` |
| OCR runtime | ONNX Runtime Android 1.28.0 |
| Lite runtime | Bergamot `v0.4.5+9271618` |
| Full runtime | llama.cpp pinned submodule + HY-MT2 Q4 |
| Online client | OkHttp 5.4.0 |

## Build

Prerequisites:

1. JDK 17.
2. Android SDK with API 36 and 37 build tools.
3. Android NDK r23b for the pinned Bergamot runner and NDK r29/CMake 3.31.6 for Full.
4. Git submodules initialized for Full builds.

Windows PowerShell:

```powershell
git clone --recurse-submodules https://github.com/Arimacose/ScreenTranslation.git
cd ScreenTranslation
.\gradlew.bat --console=plain testLiteDebugUnitTest testFullDebugUnitTest testOnlineDebugUnitTest
.\gradlew.bat --console=plain lintLiteRelease lintFullRelease lintOnlineRelease
.\gradlew.bat --console=plain assembleLiteDebug assembleFullDebug assembleOnlineDebug
```

Release matrix:

```powershell
.\gradlew.bat --console=plain `
  testLiteDebugUnitTest testFullDebugUnitTest testOnlineDebugUnitTest `
  lintLiteRelease lintFullRelease lintOnlineRelease `
  generateReleaseSboms assembleOnlineContributor `
  assembleLiteRelease assembleFullRelease assembleOnlineRelease `
  bundleLiteRelease bundleFullRelease bundleOnlineRelease
```

Local Release outputs are unsigned unless `keystore.properties` is configured. The release
workflow first builds, R8-shrinks, signs, hash-lists, and checks a 16 KiB-aligned acceptance
Artifact. After target-device acceptance, a separate promotion operation re-verifies and
publishes the exact same twelve files—including three per-edition CycloneDX SBOMs—without
rebuilding them.

Contributor/emulator build:

```powershell
.\gradlew.bat --console=plain :app:assembleOnlineContributor
```

This produces `app/build/outputs/apk/online/contributor/app-online-contributor.apk` with the
x86_64 ONNX Runtime for PP-OCRv6 testing. Translation uses Online BYOK; Lite Bergamot and Full
HY-MT2 do not expose x86_64 local runtimes. Production Release APKs remain ARM64-only.

## First use

1. Choose source and target languages.
2. Choose **Region** or **Full-screen incremental overlay (Experimental)**.
3. Prepare the current translation model, or configure the Online BYOK endpoint.
4. Grant notification and display-over-other-apps permissions.
5. On HyperOS, set the app battery policy to **Unrestricted**.
6. Tap **Start**, approve the Android screen-sharing dialog, then select a region if Region
   mode is active. Full-screen mode starts its tile scan directly.
7. Stop from the overlay/control bar, app, or notification.

## Online BYOK configuration

Enter an HTTPS Base URL and API Key, confirm the data-flow notice, then fetch the provider's
model list. The app appends `/models` and `/chat/completions` when the Base URL is a root or
version prefix. Exact returned model IDs—including spaces and hyphens—are selected from a
list rather than retyped.

The API Key is encrypted with Android Keystore-backed AES-GCM. It is redacted from logs and
UI summaries. Deleting the key removes both the ciphertext and its Keystore alias.

## Privacy boundaries

- No screenshot, OCR history, or translation history is persisted by default.
- Region/full-screen pixels are held only for the current in-memory frame pipeline.
- Lite and Full do not upload OCR text or translated text.
- Online uploads stable OCR text only to the user-configured endpoint after explicit setup.
- `FLAG_SECURE`, DRM, work-profile, and enterprise capture restrictions are respected.

See [`PRIVACY.md`](PRIVACY.md) and [`SECURITY.md`](SECURITY.md) for the complete policy.

## Known limits

- The supported and repeatedly measured device baseline remains Xiaomi 15 Pro / Android 16 /
  HyperOS. Other ROM work is intentionally out of scope for the current milestone.
- Full HY-MT2 Q4 has a GiB-class model and roughly 2.3 GiB measured process memory; it is a
  quality experiment, not the daily default.
- Lite Japanese → Chinese is a two-hop route with higher latency and weaker context fidelity.
- Full-screen incremental mode remains Experimental. Its plane-first signature path skips
  full-frame Bitmap construction for unchanged frames, and label placement avoids system
  insets, the control bar, and previously placed translations. The signed 15-minute region and
  full-screen endurance evidence is bound to Issues #38/#39 and the release notes.
- The app does not bypass secure-window, DRM, or enterprise policies.

## Contributing and releases

Please use the issue and pull-request templates. Keep edition boundaries explicit, add tests
for pure concurrency/text/geometry logic, and attach target-device evidence before changing a
feature from Experimental to supported. Release procedure: [`docs/RELEASING.md`](docs/RELEASING.md).

- [Contributing guide](CONTRIBUTING.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)
- [Changelog](CHANGELOG.md)
- [License](LICENSE)
- [v2.4.0 milestone](https://github.com/Arimacose/ScreenTranslation/milestone/6)
