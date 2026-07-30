# Corresponding source for the Lite Bergamot executable

The Lite APK distributes `libbergamot_runner.so`, an Android ARM64 executable
compiled from unchanged Bergamot Translator source plus the ScreenTranslation
wrapper.

## MPL-covered source

- Repository: <https://github.com/browsermt/bergamot-translator>
- Exact revision:
  <https://github.com/browsermt/bergamot-translator/tree/9271618ebbdc5d21ac4dc4df9e72beb7ce644774>
- License: Mozilla Public License 2.0
- Recursive source dependencies are fixed by that revision's gitlinks and are
  enumerated in `STATIC_DEPENDENCIES.md`.

This is the Source Code Form corresponding to the Bergamot portion of the
executable. It is made available under MPL-2.0. The source revision is checked
out cleanly; `build-prebuilt.sh` rejects local upstream modifications.

## ScreenTranslation wrapper and reproducible build

- Wrapper source:
  <https://github.com/Arimacose/ScreenTranslation/blob/v0.2.0/app/src/lite/cpp/bergamot_runner.cpp>
- CMake build definition:
  <https://github.com/Arimacose/ScreenTranslation/blob/v0.2.0/app/src/lite/cpp/CMakeLists.txt>
- Pinned acquisition/build script:
  <https://github.com/Arimacose/ScreenTranslation/blob/v0.2.0/app/src/lite/cpp/build-prebuilt.sh>
- Build manifest:
  <https://github.com/Arimacose/ScreenTranslation/blob/v0.2.0/app/src/lite/cpp/prebuilt-manifest.json>

The wrapper and build files are ScreenTranslation source under Apache License
2.0; they are separate files in the larger work and contain no copied Bergamot
source.

Rebuild from the v0.2.0 source checkout:

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/23.1.7779620"
bash app/src/lite/cpp/build-prebuilt.sh
```

Expected output:

- ABI: `arm64-v8a`
- Android API: 28
- STL: `c++_static`
- Size: `8,416,304` bytes
- SHA-256:
  `40a764d5fbcd8b18c6c0bef6bcc6ef38f25beb6f2e6defcfa5c00d7e54407f75`

For source availability questions, open an issue at
<https://github.com/Arimacose/ScreenTranslation/issues>.
