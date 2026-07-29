# ScreenTranslation

面向 **Android 16（API 36）/ 小米 15 Pro / HyperOS** 的实时识屏翻译原生应用。用户在前台主动启动一次任务后，应用通过 Android 的屏幕共享授权读取画面，只裁剪用户框选区域，执行本地 OCR 和本地翻译，并用悬浮窗显示结果。

> 项目状态：`0.x` 实验阶段。运行基线为 Android 16 / API 36，`minSdk` 与
> `targetSdk` 为 36，`compileSdk` 为 37。源代码采用
> [Apache License 2.0](LICENSE)，各第三方组件仍受自身条款约束。

## 功能

- 用系统 `MediaProjection` 授权捕获屏幕，不使用无障碍服务。
- 可拖拽框选识别区域，并可调节采样间隔。
- 内置 PP-OCRv6 small 多语言检测/识别模型，通过 ONNX Runtime 在设备端运行。
- 源语言提供简中、英语、日语、韩语、法语、德语和西班牙语；俄语当前作为目标语言提供。
- ML Kit 翻译模型按语言首次下载；下载完成后在设备端离线推理。
- 文本稳定后才触发翻译，避免同一画面反复识别与闪烁。
- 前台服务常驻通知明确显示捕获状态，可从应用或系统界面停止。
- 明暗主题、Android 16 edge-to-edge 及 HyperOS 悬浮窗权限流程。

## 技术基线

| 项目 | 版本/配置 |
|---|---|
| Android Gradle Plugin | 9.3.0 |
| Gradle Wrapper | 9.6.1 |
| JDK | 17 |
| Kotlin | AGP 9.3 内置 Kotlin（不应用 `org.jetbrains.kotlin.android`） |
| compile / min / target SDK | 37 / 36 / 36 |
| APK ABI | `arm64-v8a`（小米 15 Pro 目标构建） |
| Production OCR | PP-OCRv6 small ONNX，固定检测/识别模型提交 |
| OCR runtime | ONNX Runtime Android `1.26.0` |
| ML Kit Translate | `17.0.3` |

PP-OCRv6 检测模型、识别模型和字符表随 APK/AAB 打包，运行时无需下载 OCR
权重。首次构建会从 PaddlePaddle 官方仓库获取约 31 MB 的固定 ONNX 资产，
逐个校验 SHA-256 后才参与打包。翻译语言模型仍需要首次联网下载，单个模型
通常约 30 MB。

翻译结果由 Google Translate 提供支持；首次模型下载是中国大陆版 HyperOS 真机验收的独立门槛，详见 [`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md)。ML Kit 的动态模型存放在本应用专属存储中，卸载应用时随之删除。相关说明见 [ML Kit 模型安装路径](https://developers.google.com/ml-kit/tips/installation-paths) 和 [Google Translate](https://translate.google.com/)。

ML Kit 官方将端侧翻译定位为日常和简单翻译；文学长句、专业术语和非英语语言对需要独立评估。
本项目的英文原著长句真机测试显示 OCR 准确，但长句译文存在明显语义和流畅度退化，因此后续会区分
端侧快速模式与更高质量的翻译模式。

## 工程结构

```text
app/src/main/java/com/screentranslation/app/
├── MainActivity.kt                  # 权限、语言和采样设置
├── capture/
│   ├── BitmapExtractor.kt           # Image -> Bitmap 与区域裁剪
│   └── FrameProcessor.kt            # 单飞帧处理与调度
├── ml/
│   ├── OcrEngine.kt                 # OCR 生命周期与结果契约
│   ├── PpOcrv6Engine.kt             # PP-OCRv6 + ONNX Runtime 生产实现
│   └── TranslationEngine.kt         # ML Kit 模型下载、翻译与资源释放
├── overlay/
│   ├── OverlayController.kt         # 框选层和译文层
│   └── RegionSelectionView.kt       # 区域交互
├── prefs/AppPreferences.kt          # 用户设置
├── service/ScreenTranslationService.kt
└── util/StableTextGate.kt           # OCR 去抖/稳定门
```

设计细节见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，真机验收见
[`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md)，PP-OCRv6 与候选翻译模型的可复现
数据见 [`docs/MODEL_BENCHMARK_2026-07-28.md`](docs/MODEL_BENCHMARK_2026-07-28.md)。

## 构建

### 前置条件

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 37.0.0
- Git

Android Studio 安装 SDK 后，在未跟踪的 `local.properties` 中指向本机目录：

```properties
sdk.dir=C\:\\Android\\Sdk
```

检查环境：

```bash
java -version
./gradlew --version
```

Windows PowerShell 使用：

```powershell
java -version
.\gradlew.bat --version
```

首次执行构建任务时，`preparePpOcrv6Assets` 会下载并校验固定版本的
PP-OCRv6 small 资产。后续构建会复用校验通过的
`app/build/generated/ppocrv6Assets`。

### 编译、测试与 Lint

Linux/macOS：

```bash
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

Windows：

```powershell
.\gradlew.bat --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 安装

启用 Android 16 设备的开发者选项和 USB 调试后：

```bash
adb devices -l
./gradlew :app:installDebug
adb shell am start -n com.screentranslation.app/.MainActivity
```

Windows 将 `./gradlew` 替换为 `.\gradlew.bat`。

## 首次使用顺序

1. 打开应用，选择源语言、目标语言和采样间隔；PP-OCRv6 使用同一套多语言
   权重识别画面，源语言用于配置后续翻译模型；俄语当前仅列入目标语言。
2. 点击悬浮窗授权；HyperOS 会打开本应用权限编辑页，进入“其他权限 → 显示悬浮窗 → 始终允许”。
3. Android 13+ 首次运行时允许通知；拒绝后，系统仍可能在任务管理界面显示前台服务，但用户体验不完整。
4. 点击开始，接受 Android 系统的“共享/录制屏幕”提示。每个捕获会话都必须使用新的授权结果。
5. 拖动框选需要识别的屏幕区域，确认后切到目标应用。
6. 首次使用某个翻译语言时保持联网，等待模型下载。后续可断网测试设备端翻译。
7. 回到应用点击停止，或使用前台服务通知提供的停止入口。

## HyperOS 注意事项

- `SYSTEM_ALERT_WINDOW` 是特殊权限，不能用普通运行时权限弹窗授权；必须由用户在系统设置页开启。
- Android 15 QPR1+ 会在锁屏时结束当前 `MediaProjection`。应用会立即释放采集资源并发布“屏幕共享会话已结束”通知；解锁后点按通知，再次通过系统授权即可继续。
- 若亮屏长时间运行时服务被 HyperOS 提前回收，可在应用详情的“省电策略”中选择“无限制”后复测；该设置不会保留锁屏后已失效的投影令牌。
- 不需要“自启动”、无障碍服务、读取相册、麦克风或联系人权限。
- Android 16 的每个新捕获会话都使用新的屏幕共享授权结果。
- 目标 HyperOS 版本会把应用悬浮层混入 MediaProjection 帧；本应用会在
  OCR 前遮蔽悬浮层的实际屏幕区域，避免原文和译文被递归识别。
- 银行、密码管理器、流媒体 DRM 等使用 `FLAG_SECURE` 的窗口会返回黑屏/空白，这是系统隐私边界。

## 隐私边界

- 屏幕帧只在进程内存中处理，不落盘。
- OCR 与翻译推理在设备端进行；翻译模型按需联网下载。
- 应用仅保存源/目标语言和采样间隔，不保存选择区域、截图或识别历史。
- 停止服务时释放 `VirtualDisplay`、`MediaProjection`、`ImageReader`、OCR/翻译客户端和悬浮窗。
- 项目代码不上传屏幕图像、OCR 原文或译文。
- PP-OCRv6 与 ONNX Runtime OCR 路径只处理本地帧；ML Kit Translate SDK
  仍可能传输设备/应用信息、每次安装标识、语言对及诊断指标；完整说明见
  [`PRIVACY.md`](PRIVACY.md) 和
  [ML Kit Android 数据披露](https://developers.google.com/ml-kit/android-data-disclosure)。

## 已知边界

- 当前只适配 Android 16，不保证低版本 Android 或其他厂商 ROM。
- 当前 APK 只打包 `arm64-v8a`，用于小米 15 Pro 真机；如需 x86_64 模拟器，应调整 `abiFilters` 后重新构建。
- PP-OCRv6 使用一套多语言识别模型；当前 UI 仅开放已列出的源语言，混合文字、
  小字号和艺术字体仍需按真机夹具验收。
- 动画、游戏或快速滚动画面会受采样间隔、设备温度和文字清晰度影响。
- 系统级安全窗口、工作资料策略和部分视频内容不可被捕获。
- 长结果默认折叠，可通过展开模式在限高面板内滚动查看。
- ML Kit 端侧翻译适合简短日常文本，文学长句质量不作为当前版本保证。

## 开源维护

- 贡献流程：[`CONTRIBUTING.md`](CONTRIBUTING.md)
- 行为准则：[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)
- 路线图：[`docs/ROADMAP.md`](docs/ROADMAP.md)
- 安全报告：[`SECURITY.md`](SECURITY.md)
- 隐私与数据流：[`PRIVACY.md`](PRIVACY.md)
- 支持范围：[`SUPPORT.md`](SUPPORT.md)
- 项目治理：[`GOVERNANCE.md`](GOVERNANCE.md)
- 维护者手册：[`docs/MAINTAINING.md`](docs/MAINTAINING.md)
- 发布流程：[`docs/RELEASING.md`](docs/RELEASING.md)
- 第三方组件：[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
- 版本记录：[`CHANGELOG.md`](CHANGELOG.md)

## 参考

- [Android Media projection](https://developer.android.com/media/grow/media-projection)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [PP-OCRv6 small detection ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [PP-OCRv6 small recognition ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)
- [ONNX Runtime Android](https://onnxruntime.ai/docs/build/android.html)
- [ML Kit Text Recognition v2（benchmark 基线）](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [ML Kit on-device translation](https://developers.google.com/ml-kit/language/translation/android)
- [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
