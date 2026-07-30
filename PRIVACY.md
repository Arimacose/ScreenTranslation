# 隐私说明

最近更新：2026-07-31

ScreenTranslation 是一个由用户主动启动的屏幕区域翻译工具。本文件描述当前开源代码的
数据流；发行者若修改代码、接入服务或增加分析 SDK，应发布相应版本的隐私说明。

## 处理的数据

运行一次识屏任务时，应用会处理：

- Android MediaProjection 提供的屏幕帧；
- 用户框选的区域坐标；
- OCR 识别出的文本；
- 翻译结果；
- 用户选择的源语言、目标语言和采样间隔；
- Lite 或 Full edition 所需的本地翻译模型。

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
首次启动 OCR 时复制到应用 `codeCacheDir` 供 ONNX Runtime 读取。Lite edition
按需下载 Firefox Translations 的 Bergamot 模型；Full edition 按需下载固定版本
的 Hy-MT2 Q4 GGUF。模型在校验固定大小与 SHA-256 后写入应用专属
`no_backup` 目录，卸载对应 edition 时随应用数据删除。选择区域、截图和识别
历史不持久化。

## 本地 OCR 与翻译模型网络请求

OCR 使用随 APK/AAB 打包的 PP-OCRv6 small ONNX 模型，通过 ONNX Runtime 在设备端
处理屏幕帧。运行中的 OCR 路径不上传画面，也不下载权重。构建系统从 PaddlePaddle
官方模型仓库获取固定版本资产并校验 SHA-256；这是构建时供应链步骤，并非应用运行时
网络请求。

Lite edition 的英语→中文模型，以及日语→英语→中文级联模型，来自 Mozilla
Firefox Translations 固定 HTTPS 地址。Full edition 的 Hy-MT2 Q4 模型来自固定
Hugging Face revision。应用逐文件校验发布清单中的长度和 SHA-256；校验通过后，
翻译推理全部在设备端执行。

v0.2.1 的 Lite 与 Full APK 不使用 ML Kit Translate。仓库中的 `benchmark`
build type 仍保留 ML Kit Translate 作为可复现实验对照，但该对照不属于两份
GitHub Release APK。

参考：

- [PP-OCRv6 small detection ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [PP-OCRv6 small recognition ONNX](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)
- [Firefox Translations models](https://mozilla.github.io/translations/firefox-models/)
- [Tencent Hy-MT2](https://github.com/Tencent-Hunyuan/Hy-MT2)

## Android 权限

| 权限/能力 | 用途 |
|---|---|
| `INTERNET` | 下载用户选择语言所需的固定翻译模型 |
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

计划中的 Online edition 会把 OCR 文本发送到用户自行配置的翻译服务；它的
API 契约、Android Keystore 密钥存储、数据流确认和验收门槛见
[`docs/ONLINE_TRANSLATION_DESIGN.md`](docs/ONLINE_TRANSLATION_DESIGN.md)。
该设计不属于 v0.2.1 Lite / Full APK 的运行路径。
