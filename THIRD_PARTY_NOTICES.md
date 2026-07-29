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
| ML Kit Translate | 17.0.3 | ML Kit Terms of Service |

## 模型基准依赖

以下组件只进入 `benchmark` 变体，用于候选模型真机比较：

| 组件 | 当前版本/固定提交 | 上游条款 |
|---|---:|---|
| ML Kit Text Recognition（Latin/Chinese/Japanese/Korean） | 16.0.1 | ML Kit Terms of Service |
| Bergamot Translator Android PoC | `9271618ebbdc5d21ac4dc4df9e72beb7ce644774` | Mozilla Public License 2.0 |
| Firefox Translations en→zh `base-memory` model | pinned benchmark manifest | Mozilla Public License 2.0 |

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
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)
- [PP-OCRv6 small detection ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [PP-OCRv6 small recognition ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)
- [Bergamot Translator](https://github.com/browsermt/bergamot-translator)
- [Firefox Translations models](https://github.com/mozilla/firefox-translations-models)
- [Mozilla Public License 2.0](https://www.mozilla.org/MPL/2.0/)

ML Kit Maven POM 将翻译组件及 benchmark-only OCR 组件的许可证字段标记为
“ML Kit Terms of Service”，因此它们不因本仓库采用 Apache-2.0 而转为
Apache-2.0。应用界面保留 Google Translate 归属说明。

Bergamot 与 Firefox Translations 模型当前只用于被忽略构建目录中的概念验证，
不进入生产 APK。若后续分发原生库或模型，发布流程还需随包收录 MPL-2.0 文本、
对应源码修改说明，以及 Bergamot 各第三方子模块要求的 notice。

Gradle 还会解析传递依赖。发布维护者应在依赖更新和正式发布前检查：

```bash
./gradlew :app:dependencies
./gradlew :app:dependencyInsight --dependency <name> --configuration debugRuntimeClasspath
```

本文件用于维护者审计，不替代各上游组件随包提供的完整许可证文本。
