# Bergamot Android ARM64 static dependency manifest

The shipped executable is pinned by the SHA-256 recorded in `SOURCE-CODE.md`.
This manifest records the source revisions used by its reproducible build and
maps them to license texts included in this directory.

## Top-level source

| Component | Revision/version | License material |
|---|---|---|
| browsermt/bergamot-translator | `9271618ebbdc5d21ac4dc4df9e72beb7ce644774` | `MPL-2.0.txt` |
| browsermt/marian-dev | `2781d735d4a10dca876d61be587afdab2726293c` | `marian-dev-2781d735/LICENSE.txt` and `vendored/` |
| browsermt/ssplit-cpp | `a311f9865ade34db1e8e080e6cc146f55dafb067` | `static-dependencies/ssplit-cpp-a311f986-LICENSE.txt` |
| PCRE2 | `10.39` | `static-dependencies/PCRE2-10.39-LICENCE.txt` |
| Android NDK | `23.1.7779620` | `ANDROID-NDK-r23b-NOTICE.txt` |

`SSPLIT_USE_INTERNAL_PCRE2=ON` statically builds PCRE2 10.39. The LGPL-2.1
`ssplit-cpp/nonbreaking_prefixes` data files are not compiled or packaged by
ScreenTranslation.

## Marian CPU dependency revisions

The following gitlinks are fixed by Marian revision `2781d735...` and are used
by the CPU translation executable:

| Component | Revision | License material |
|---|---|---|
| kpu/intgemm | `f7401513da71758dacce52fed1c7855549abee59` | `static-dependencies/intgemm-f7401513-LICENSE.txt` |
| google/ruy | `2d950b3bfa7ebfbe7a97ecb44b1cc4da5ac1d6f0` | `static-dependencies/ruy-2d950b3b-LICENSE.txt` |
| browsermt/sentencepiece | `ae41b7740d7006596bb9257e83340b2620db9d00` | `static-dependencies/sentencepiece-*-LICENSE.txt` |
| browsermt/simd_utils | `d0793d86aea9036a5bc77b9ca7791dff024168ca` | `static-dependencies/simd_utils-d0793d86-LICENSE.txt` |
| pytorch/cpuinfo via ruy | `5916273f79a21551890fd3d56fc5375a78d1598d` | `static-dependencies/cpuinfo-*-LICENSE.txt` |

The license bundle also retains Marian's vendored-source licenses for CLI11,
cnpy, faiss, mio, pathie-cpp, phf, spdlog, yaml-cpp and zstr. Retaining these
notices is deliberately conservative when link-time garbage collection removes
unused source sections.

The revisions for examples, regression tests, CUDA/NCCL, browser ONNX.js,
WebSocket and pybind test/build support are not production runtime inputs and
are not represented as shipped code.
