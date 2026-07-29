# 隐私说明

最近更新：2026-07-29

ScreenTranslation 是一个由用户主动启动的屏幕区域翻译工具。本文件描述当前开源代码的
数据流；发行者若修改代码、接入服务或增加分析 SDK，应发布相应版本的隐私说明。

## 处理的数据

运行一次识屏任务时，应用会处理：

- Android MediaProjection 提供的屏幕帧；
- 用户框选的区域坐标；
- OCR 识别出的文本；
- 翻译结果；
- 用户选择的源语言、目标语言和采样间隔；
- ML Kit 所需的翻译模型与运行诊断信息。

## 屏幕内容

- 屏幕帧、OCR 原文和译文在应用进程内用于当前任务。
- 当前生产代码不把截图、原文或译文写入文件、数据库、历史记录或应用日志。
- 当前项目代码不把屏幕图像、OCR 原文或译文发送到项目自建服务器。
- 停止任务时释放 MediaProjection、VirtualDisplay、ImageReader、OCR/翻译客户端和悬浮窗。
- 使用 `FLAG_SECURE`、DRM 或组织策略保护的窗口由 Android 决定是否返回黑屏或空白。

开发者在手动测试时可能主动保存脱敏截图和日志；这些测试产物位于构建目录，不属于生产功能，
不得包含真实私人内容。

## 本地保存

应用只保存源语言、目标语言和采样间隔等设置。PP-OCRv6 权重随安装包提供，
首次启动 OCR 时复制到应用 `codeCacheDir` 供 ONNX Runtime 读取；翻译语言模型
由 ML Kit 下载到应用专属存储。两类文件均位于应用专属空间，卸载应用时随应用
数据删除。选择区域、截图和识别历史不持久化。

## 本地 OCR、网络与 ML Kit

OCR 使用随 APK/AAB 打包的 PP-OCRv6 small ONNX 模型，通过 ONNX Runtime 在设备端
处理屏幕帧。运行中的 OCR 路径不上传画面，也不下载权重。构建系统从 PaddlePaddle
官方模型仓库获取固定版本资产并校验 SHA-256；这是构建时供应链步骤，并非应用运行时
网络请求。

翻译语言模型在首次使用相应语言对时由 ML Kit 按需下载，之后翻译推理在设备端执行。

Google 的 ML Kit Translate 数据披露说明指出，相关 Android SDK 还可能为诊断和使用
分析收集：

- 设备制造商、型号、OS 版本、构建和可用 ML 加速器；
- 应用包名与版本；
- 每次安装的标识符；
- 翻译功能配置的源语言和目标语言；
- 性能与错误诊断信息。

Google 表示这些数据通过 HTTPS 传输，且其披露页面列出的数据不转移给第三方。项目维护者仍需
根据实际发行包、地区和商店要求独立完成数据安全披露。

参考：

- [ML Kit Android 数据披露](https://developers.google.com/ml-kit/android-data-disclosure)
- [ML Kit 端侧翻译](https://developers.google.com/ml-kit/language/translation/android)
- [ML Kit 模型安装路径](https://developers.google.com/ml-kit/tips/installation-paths)
- [PP-OCRv6 small detection ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [PP-OCRv6 small recognition ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)

## Android 权限

| 权限/能力 | 用途 |
|---|---|
| `INTERNET` | 下载翻译模型及 ML Kit Translate SDK 网络行为 |
| `ACCESS_NETWORK_STATE` | 展示连接状态和模型准备反馈 |
| `SYSTEM_ALERT_WINDOW` | 框选区域并在其他应用上方显示结果 |
| `POST_NOTIFICATIONS` | 显示前台捕获任务通知 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 在用户授权后维持屏幕共享任务 |
| MediaProjection 系统授权 | 每次捕获会话由用户确认共享屏幕 |

应用不请求相册、联系人、麦克风、摄像头或无障碍权限。

## 用户控制

- 可从应用或前台服务通知停止任务。
- 可在 Android 设置中撤销悬浮窗、通知和应用权限。
- 清除应用数据可删除设置；卸载应用可删除应用及其专属数据。
- 重新开始屏幕共享时，Android 会再次显示系统授权界面。

## 变更要求

任何新增网络翻译、遥测、历史记录、崩溃上报、账号系统或云同步的 PR，必须同时：

1. 更新本文件、README 和数据流架构；
2. 明确用户开关、保留周期、传输目的与处理方；
3. 增加对应测试和迁移说明；
4. 在版本变更记录中标明。
