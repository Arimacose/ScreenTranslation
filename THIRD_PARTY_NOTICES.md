# 第三方组件与条款

ScreenTranslation 自有源代码采用 Apache License 2.0。构建与运行还依赖以下直接组件；
各组件仍受其自身许可证或服务条款约束。

## 运行时依赖

| 组件 | 当前版本 | 上游条款 |
|---|---:|---|
| AndroidX Core KTX | 1.19.0 | Apache License 2.0 |
| AndroidX Activity KTX | 1.13.0 | Apache License 2.0 |
| AndroidX AppCompat | 1.7.1 | Apache License 2.0 |
| Material Components for Android | 1.14.0 | Apache License 2.0 |
| ONNX Runtime for Android | 1.26.0 | MIT License |
| PP-OCRv6 small detection ONNX | `28fe5895c24fd108c19eb3e8479f4ab385fbfc62` | Apache License 2.0 |
| PP-OCRv6 small recognition ONNX | `b8f84f0b80c529de40b4fbb3544b84fa7233a513` | Apache License 2.0 |

## Lite / Bergamot 依赖

以下组件进入 Lite edition。模型从固定上游地址按需下载，不写入 APK/AAB：

| 组件 | 当前版本/固定提交 | 上游条款 |
|---|---:|---|
| Bergamot Translator Android runtime | `9271618ebbdc5d21ac4dc4df9e72beb7ce644774` (`v0.4.5+9271618`) | Mozilla Public License 2.0 |
| Firefox Translations en→zh model | `en-zh/llmaat_finetune10M_qe8_f2_ByQcSxGXQRqGi-UTxYE43g` + per-file SHA-256 | Mozilla Public License 2.0 |
| Firefox Translations ja→en `base-memory` model | `ja-en/cjk_retrain_base-memory_NLRJLD_pQFyrvgKtbie2nA` + per-file SHA-256 | Mozilla Public License 2.0 |

## Full / HY-MT2 Q4 Experimental 依赖

以下组件进入 Full edition。模型从固定 revision 按需下载，不写入 APK/AAB：

| 组件 | 当前版本/固定提交 | 上游条款 |
|---|---:|---|
| Hy-MT2 1.8B Q4_K_M GGUF model | `1cd5208700acedef4ef93019b6cfc148b8522d45` | Apache License 2.0 |
| llama.cpp Android runtime | `caa596ab3` (`b10181`) | MIT License |

## Online / user-configured LLM 依赖

Online edition 不携带翻译模型或服务商 SDK；它增加以下 HTTP 运行时：

| 组件 | 当前版本 | 上游条款 |
|---|---:|---|
| OkHttp / okhttp-android | 5.4.0 | Apache License 2.0 |
| Okio / okio-jvm | 3.17.0 | Apache License 2.0 |

## 模型基准依赖

以下组件只进入 `benchmark` 变体，用于候选模型真机比较：

| 组件 | 当前版本/固定提交 | 上游条款 |
|---|---:|---|
| ML Kit Text Recognition（Latin/Chinese/Japanese/Korean） | 16.0.1 | ML Kit Terms of Service |
| ML Kit Translate | 17.0.3 | ML Kit Terms of Service |

## 测试依赖

| 组件 | 当前版本 | 上游条款 |
|---|---:|---|
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 |

## 链接

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [AndroidX source and licenses](https://cs.android.com/androidx/platform/frameworks/support)
- [Material Components for Android](https://github.com/material-components/material-components-android)
- [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms)
- [ML Kit usage guidelines and attribution](https://developers.google.com/ml-kit/terms)
- [JUnit 4](https://github.com/junit-team/junit4)
- [ONNX Runtime 1.26.0](https://github.com/microsoft/onnxruntime/tree/v1.26.0)
- [ONNX Runtime 1.26.0 third-party notices](https://github.com/microsoft/onnxruntime/blob/v1.26.0/ThirdPartyNotices.txt)
- [PP-OCRv6 small detection ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [PP-OCRv6 small recognition ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)
- [Bergamot Translator exact source](https://github.com/browsermt/bergamot-translator/tree/9271618ebbdc5d21ac4dc4df9e72beb7ce644774)
- [Firefox Translations model license revision](https://github.com/mozilla/firefox-translations-models/tree/e7957fc407441a5e3e35bbcbf9d60d9b35764618)
- [Mozilla Public License 2.0](https://www.mozilla.org/MPL/2.0/)
- [Hy-MT2 1.8B GGUF exact source and license](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/tree/1cd5208700acedef4ef93019b6cfc148b8522d45)
- [llama.cpp exact source](https://github.com/ggml-org/llama.cpp/tree/caa596ab3f0f8768ee326d6e3d5d39782194676c)
- [OkHttp 5.4.0 source](https://github.com/square/okhttp/tree/parent-5.4.0)
- [Okio 3.17.0 source](https://github.com/square/okio/tree/parent-3.17.0)

ML Kit Maven POM 将 benchmark-only 翻译与 OCR 组件的许可证字段标记为
“ML Kit Terms of Service”，因此它们不因本仓库采用 Apache-2.0 而转为
Apache-2.0。

Lite APK 静态携带 Bergamot Android runtime。MPL-2.0 对应 Source Code Form
位于上面的固定 Bergamot revision；递归 gitlink、ScreenTranslation wrapper、
CMake、重建命令、NDK 版本、预构建文件大小与 SHA-256 记录在：

- `third_party/licenses/lite/licenses/bergamot-9271618/SOURCE-CODE.md`
- `third_party/licenses/lite/licenses/bergamot-9271618/STATIC_DEPENDENCIES.md`
- `app/src/lite/cpp/prebuilt-manifest.json`

固定上游源码在构建时保持 clean，项目 wrapper 是 Apache-2.0 larger-work 文件。
静态依赖的完整许可证、版权声明和 NDK notice 与 MPL 文本一并进入 Lite
`assets/licenses/`。Firefox 模型仅在用户准备语言对时从 Mozilla 固定地址下载；
逐文件压缩/解压大小与 SHA-256 位于
`third_party/licenses/lite/licenses/FIREFOX-TRANSLATIONS-MODELS.md`。

Full APK 携带 llama.cpp JNI runtime、启用的 Arm KleidiAI CPU kernels 和
Android NDK shared C++ runtime。对应 MIT 文本、Arm copyright 与 NDK notice
进入 Full `assets/licenses/`。Hy-MT2 Q4 模型只在用户准备模型时从 Tencent
固定 revision 下载，并由应用固定文件名、长度和 SHA-256；该 revision 的 Tencent
copyright 与 Apache-2.0 原文也在 Full license bundle 中。Full 的界面、包名与
发布说明均标记为 Experimental / HY-MT2 Q4。

三个 edition 共同把以下材料打入 `assets/licenses/`：

- 项目与 PP-OCRv6 使用的 Apache License 2.0 全文；
- ONNX Runtime 1.26.0 MIT 全文；
- ONNX Runtime 1.26.0 官方 `ThirdPartyNotices.txt`；
- PP-OCRv6-small 固定 revision、下载文件和 SHA-256 坐标。

GitHub Release 的统一 `THIRD-PARTY.zip` 是
`THIRD_PARTY_NOTICES.md` 与整个 `third_party/licenses/` 的归档；它和
APK/AAB 内嵌材料来自同一受版本控制的源。

Gradle 还会解析传递依赖。发布维护者应在依赖更新和正式发布前检查：

```bash
./gradlew :app:dependencies --configuration liteReleaseRuntimeClasspath
./gradlew :app:dependencies --configuration fullReleaseRuntimeClasspath
./gradlew :app:dependencyInsight --dependency <name> --configuration liteReleaseRuntimeClasspath
./gradlew :app:dependencyInsight --dependency <name> --configuration fullReleaseRuntimeClasspath
```

完整许可证文本、版权 notices 与对应源码坐标位于
[`third_party/licenses/`](third_party/licenses/)；本文件是其分发索引。
