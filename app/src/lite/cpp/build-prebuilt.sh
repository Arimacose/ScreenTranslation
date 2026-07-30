#!/usr/bin/env bash
set -euo pipefail

BERGAMOT_COMMIT="9271618ebbdc5d21ac4dc4df9e72beb7ce644774"
BERGAMOT_REPOSITORY="https://github.com/browsermt/bergamot-translator.git"
NDK_REVISION="23.1.7779620"

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_DIRECTORY="$(cd "$SCRIPT_DIRECTORY/../../../.." && pwd)"
OUTPUT_ROOT="${OUTPUT_ROOT:-$REPOSITORY_DIRECTORY/app/build/bergamot-lite-prebuilt}"
SOURCE_DIRECTORY="$OUTPUT_ROOT/source/bergamot-translator"
BUILD_DIRECTORY="$OUTPUT_ROOT/build/arm64-v8a"
JNI_DIRECTORY="$REPOSITORY_DIRECTORY/app/src/lite/jniLibs/arm64-v8a"
OUTPUT="$JNI_DIRECTORY/libbergamot_runner.so"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  printf '%s\n' \
    "ANDROID_NDK_HOME must point to Android NDK r23b ($NDK_REVISION)." >&2
  exit 2
fi
if ! grep -q "Pkg.Revision = $NDK_REVISION" \
  "$ANDROID_NDK_HOME/source.properties"; then
  printf 'Expected NDK %s at %s\n' "$NDK_REVISION" "$ANDROID_NDK_HOME" >&2
  exit 2
fi

mkdir -p "$(dirname "$SOURCE_DIRECTORY")" "$BUILD_DIRECTORY" "$JNI_DIRECTORY"
if [[ ! -d "$SOURCE_DIRECTORY/.git" ]]; then
  git clone --filter=blob:none "$BERGAMOT_REPOSITORY" "$SOURCE_DIRECTORY"
fi
git -C "$SOURCE_DIRECTORY" fetch --depth 1 origin "$BERGAMOT_COMMIT"
git -C "$SOURCE_DIRECTORY" checkout --detach "$BERGAMOT_COMMIT"
git -C "$SOURCE_DIRECTORY" submodule sync --recursive
git -C "$SOURCE_DIRECTORY" submodule update --init --recursive --depth 1
test "$(git -C "$SOURCE_DIRECTORY" rev-parse HEAD)" = "$BERGAMOT_COMMIT"
test -z "$(git -C "$SOURCE_DIRECTORY" status --porcelain)"

cmake \
  -S "$SCRIPT_DIRECTORY" \
  -B "$BUILD_DIRECTORY" \
  -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_ARM_NEON=TRUE \
  -DANDROID_STL=c++_static \
  -DBERGAMOT_SOURCE="$SOURCE_DIRECTORY"
cmake \
  --build "$BUILD_DIRECTORY" \
  --target bergamot_runner \
  --parallel "${JOBS:-2}"

cp "$BUILD_DIRECTORY/bergamot_runner" "$OUTPUT"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  --strip-unneeded "$OUTPUT"

echo \
  "40a764d5fbcd8b18c6c0bef6bcc6ef38f25beb6f2e6defcfa5c00d7e54407f75  $OUTPUT" |
  sha256sum --check --strict
sha256sum "$OUTPUT"
stat --printf='%s bytes\n' "$OUTPUT"
