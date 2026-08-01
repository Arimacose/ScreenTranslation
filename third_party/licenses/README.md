# Packaged third-party license bundle

This directory is the version-controlled source for the license material
embedded in the Android packages and attached to the GitHub release.

- `common/` is included in Lite, Full, and Online.
- `lite/` is included only in Lite.
- `full/` is included only in Full.
- `online/` is included only in Online.

The Android asset roots are configured in `app/build.gradle.kts`. The files are
packaged below `assets/licenses/`. The release workflow archives this complete
directory together with the repository-level `THIRD_PARTY_NOTICES.md`.

Upstream license texts are copied from the exact revisions named by their
directory or companion manifest. Locally written index and source-coordinate
files record where each verbatim text came from.

`SHA256SUMS` covers every other file in this directory. CI and the signed
release workflow verify it before building every edition.
