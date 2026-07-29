#!/usr/bin/env bash
set -euo pipefail

BERGAMOT_COMMIT="${BERGAMOT_COMMIT:-9271618ebbdc5d21ac4dc4df9e72beb7ce644774}"
BERGAMOT_REPOSITORY="${BERGAMOT_REPOSITORY:-https://github.com/browsermt/bergamot-translator.git}"
ANDROID_API="${ANDROID_API:-28}"
ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
JOBS="${JOBS:-2}"

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_DIRECTORY="$(cd "$SCRIPT_DIRECTORY/../.." && pwd)"
OUTPUT_ROOT="${OUTPUT_ROOT:-$REPOSITORY_DIRECTORY/app/build/bergamot-android-poc}"
SOURCE_DIRECTORY="${BERGAMOT_SOURCE:-$OUTPUT_ROOT/source/bergamot-translator}"
BUILD_DIRECTORY="${BUILD_DIRECTORY:-$OUTPUT_ROOT/build/$ANDROID_ABI}"
OUTPUT_DIRECTORY="$OUTPUT_ROOT/bin/$ANDROID_ABI"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  printf '%s\n' "ANDROID_NDK_HOME must point to Android NDK r23b (23.1.7779620)." >&2
  exit 2
fi
if [[ ! -f "$ANDROID_NDK_HOME/source.properties" ]]; then
  printf 'NDK source.properties is missing: %s\n' "$ANDROID_NDK_HOME" >&2
  exit 2
fi
if ! grep -q 'Pkg.Revision = 23.1.7779620' "$ANDROID_NDK_HOME/source.properties"; then
  printf '%s\n' "This PoC is pinned to Android NDK r23b (23.1.7779620)." >&2
  cat "$ANDROID_NDK_HOME/source.properties" >&2
  exit 2
fi

mkdir -p "$(dirname "$SOURCE_DIRECTORY")" "$BUILD_DIRECTORY" "$OUTPUT_DIRECTORY"

if [[ ! -d "$SOURCE_DIRECTORY/.git" ]]; then
  git clone "$BERGAMOT_REPOSITORY" "$SOURCE_DIRECTORY"
fi

if [[ -n "$(git -C "$SOURCE_DIRECTORY" status --porcelain)" ]]; then
  printf 'Bergamot checkout has local changes: %s\n' "$SOURCE_DIRECTORY" >&2
  exit 2
fi
if ! git -C "$SOURCE_DIRECTORY" cat-file -e "$BERGAMOT_COMMIT^{commit}" 2>/dev/null; then
  git -C "$SOURCE_DIRECTORY" fetch --depth 1 origin "$BERGAMOT_COMMIT"
fi
git -C "$SOURCE_DIRECTORY" checkout --detach "$BERGAMOT_COMMIT"
git -C "$SOURCE_DIRECTORY" submodule sync --recursive
git -C "$SOURCE_DIRECTORY" submodule update --init --recursive

cmake \
  -S "$SCRIPT_DIRECTORY" \
  -B "$BUILD_DIRECTORY" \
  -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DANDROID_ARM_NEON=TRUE \
  -DANDROID_STL=c++_static \
  -DBERGAMOT_SOURCE="$SOURCE_DIRECTORY" \
  -DBERGAMOT_COMMIT="$BERGAMOT_COMMIT"

cmake \
  --build "$BUILD_DIRECTORY" \
  --target bergamot-android-benchmark \
  --parallel "$JOBS"

UNSTRIPPED="$BUILD_DIRECTORY/bergamot-android-benchmark"
OUTPUT="$OUTPUT_DIRECTORY/bergamot-android-benchmark"
STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

cp "$UNSTRIPPED" "$OUTPUT"
"$STRIP" --strip-unneeded "$OUTPUT"

SOURCE_HEAD="$(git -C "$SOURCE_DIRECTORY" rev-parse HEAD)"
OUTPUT_BYTES="$(stat --printf='%s' "$OUTPUT")"
OUTPUT_SHA256="$(sha256sum "$OUTPUT" | cut -d ' ' -f 1)"

cat >"$OUTPUT_DIRECTORY/build-manifest.json" <<EOF
{
  "bergamot_repository": "$BERGAMOT_REPOSITORY",
  "bergamot_commit": "$SOURCE_HEAD",
  "ndk_revision": "23.1.7779620",
  "android_abi": "$ANDROID_ABI",
  "android_api": $ANDROID_API,
  "binary": {
    "name": "bergamot-android-benchmark",
    "size_bytes": $OUTPUT_BYTES,
    "sha256": "$OUTPUT_SHA256"
  }
}
EOF

file "$OUTPUT"
cat "$OUTPUT_DIRECTORY/build-manifest.json"
