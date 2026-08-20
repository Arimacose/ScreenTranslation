# v2.4.0 signed physical-device acceptance

## Immutable candidate identity

| Field | Accepted value |
|---|---|
| Source commit | `SOURCE_COMMIT` |
| Annotated tag | `v2.4.0` |
| versionCode / base versionName | `10` / `2.4.0` |
| Device | Xiaomi 15 Pro, model `2410DPN6CC`, codename `haotian` |
| Android / API | Android 16 / API 36 |
| HyperOS build | `OS_BUILD` |
| Device serial in public artifacts | redacted to a one-way suffix |
| Signing certificate SHA-256 | `SIGNING_CERT_SHA256` |
| Acceptance directory | `ACCEPTANCE_PATH` |

The candidate passes only when the installed `base.apk` bytes match the signed APK copied into the acceptance bundle, all three APKs share the expected certificate lineage, and every evidence file is included in the final evidence hash manifest.

## Required matrix

### All editions

- clean install and same-signature upgrade from the public v2.3.0 package;
- Apple, MIUIX, and Material 3 at font scales 1.0, 1.3, and 2.0;
- overlay permission, actual Android MediaProjection consent, notification/Quick Settings launch;
- region selection with the complete Re-select control visible at portrait and landscape bounds;
- English, Japanese, mixed English/Japanese/Chinese, and pure-Kanji UI fixtures;
- Balanced, Small subtitle, and Document OCR profiles, including low-contrast/small-text evidence;
- original/translation atomic pending/failure states, copy actions, freeze/reselect, rotation, screen off/on;
- full-screen changed-block source match, overlay exclusion, pause/hide/read controls, and stop;
- post-stop proof: no active MediaProjection, VirtualDisplay, ImageReader, overlay window, OCR/backend process, or capture notification;
- privacy scan: no screenshot, OCR text, translation history, API key, Bearer header, or fixture content in app files, logs, or evidence metadata.

### Lite

- English direct and Japanese pivot model preparation, resume/cancel, hash verification, region/full-screen translation, and sustained observation.

### Full Experimental

- Q4 preparation, resume/cancel, fixed long-sentence self-test, process memory/thermal observation, region/full-screen translation, and repeated stop/start.

### Online BYOK

- model catalog load/search/exact-ID selection; explicit final endpoint URLs;
- success, 401, 403, 404, 408, 429, 5xx, DNS, TLS, timeout, and explicit cancellation fixtures;
- displayed content-free metrics and expandable redacted detail;
- 2–8 block success, malformed/missing/duplicate/unexpected response rejection, bounded split, no duplicate/stale publication;
- provider-side confirmation that requests contain text/opaque IDs only and never screenshot bytes or coordinates.

## Measured result table

| Gate | Lite | Full | Online | Evidence |
|---|---|---|---|---|
| Signed install/upgrade and byte identity | `PENDING` | `PENDING` | `PENDING` | `ARTIFACT` |
| UI/font-scale matrix | `PENDING` | `PENDING` | `PENDING` | `UI_REPORT` |
| Region mixed-script/small-text | `PENDING` | `PENDING` | `PENDING` | `REGION_REPORT` |
| Full-screen batching/source match | `PENDING` | `PENDING` | `PENDING` | `FULL_REPORT` |
| Lifecycle/stop/privacy | `PENDING` | `PENDING` | `PENDING` | `LIFECYCLE_REPORT` |
| Sustained/thermal/memory | `PENDING` | `PENDING` | `PENDING` | `ENDURANCE_REPORT` |

## Publication rule

The release workflow may publish only after this matrix is filled with verified evidence, the v2.4.0 milestone has zero open issues, required GitHub checks pass on the exact source commit, and the annotated tag points to that same commit. Promotion must use the already accepted signed bytes rather than rebuilding after acceptance.
