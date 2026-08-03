# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的结构，并使用
[Semantic Versioning](https://semver.org/lang/zh-CN/) 管理公开版本。

## [Unreleased]

### Changed

- 将 ONNX Runtime Android 从 1.26.0 更新到 1.28.0；小米 15 Pro / Android 16
  固定 PP-OCRv6 夹具输出保持逐字一致，三轮 A/B 的 OCR 中位延迟降低 2.77%，
  进程峰值内存中位数增加约 4.6 MiB。

### Fixed

- PP-OCRv6 benchmark 改为从 ORT 运行时读取真实版本，并同步更新架构说明、
  第三方归属材料及 APK/AAB 许可证路径，避免依赖版本与发布材料漂移。

## [0.3.0] - 2026-08-02

### Added

- 实现独立 `com.screentranslation.app.online` edition：PP-OCRv6 在设备端识别，
  通过用户配置的 OpenAI-compatible HTTPS Chat Completions 服务翻译整段 OCR 文本。
- 增加 Online 设置页、服务/模型配置摘要、保存并测试入口，以及 600 ms 去抖、
  750 ms 最小间隔、单活跃/单 latest pending 的可取消翻译协调器。
- 将 Online 单元测试、Release Lint/R8、APK/AAB、后端隔离和许可证检查纳入
  CI 与签名发布工作流。
- 增加 TranslateGemma/Hy-MT2 的 80 条同 GPU 质量、性能与流量报告；受长期托管
  硬件条件限制，Online Release 最终只保留用户自带 API Key 的 BYOK 链路。
- 停止识屏后保留常驻快捷通知，可在目标应用内重新请求屏幕共享并直接框选。

### Security

- API Key 使用 Android Keystore AES-256-GCM 加密，输入框不回显；更换服务主机
  后必须重新确认 OCR 文本数据流。
- Online 仅接受无账号密码/query/fragment 的 HTTPS 地址，关闭重定向与 OkHttp
  隐式重试，不记录认证头、OCR 原文、请求正文或译文。

### Fixed

- 移除译文悬浮窗的 `FLAG_SECURE`，使用户发起的系统截图和录屏可以保留译文
  面板；MediaProjection 帧仍在 OCR 前按实际悬浮层坐标遮蔽，避免译文回灌。
- 框选最小边长从 64dp 降为 32dp，移除全屏黑色遮罩，并在框选期间接管返回、
  预测返回与系统边缘手势，避免目标应用被误返回；框选态只保留顶部单条提示，
  不再显示底部重复说明或控制面板。
- 读取 HyperOS 3 的 `MILLET_NO_RESTRICT_APP` 精确包名列表，区分已设/未设
  「无限制」，不再只依赖 AOSP 电源白名单猜测厂商策略。
- 针对在线翻译长尾，将 connect/write/read/call 超时调整为 `15/30/75/90 s`，
  `SocketTimeoutException` 不再自动重试；网络等待期间先显示稳定 OCR 原文与
  “正在请求在线翻译…”，避免把 OCR 等待误判为请求超时。

## [0.2.1] - 2026-07-31

### Changed

- 应用版本提高为 `versionCode 3` / `0.2.1-lite` / `0.2.1-full`。

### Fixed

- 修复 Lite 停止 Bergamot 服务时关闭 stderr 管道可能触发
  `InterruptedIOException` 未捕获异常并导致进程崩溃的问题；正常关闭期间的
  reader 中断现在按预期回收，非关闭期的读取错误仍记录告警。

## [0.2.0] - 2026-07-31

### Added

- 增加隔离的 `benchmark` 变体、固定识屏夹具和 OCR/翻译质量评分工具。
- 增加 PP-OCRv6 small + ONNX Runtime Android 候选实现及小米 15 Pro 真机基准。
- 增加固定版本、校验哈希的 Firefox Translations 英中模型获取与 Bergamot 基准流程。
- 增加 Lite edition：保留 `com.screentranslation.app`，使用 Bergamot
  英→中直译和日→英→中级联。
- 增加可并存安装的 Full edition：使用独立 `.full` 包名和
  Hy-MT2 1.8B Q4_K_M，所有界面及发布资产均标记为 Experimental。
- 增加 Hy-MT2 Q4 / 1.25-bit、ML Kit、Bergamot 多语言真机基准。
- 增加 Online edition 的 Base URL、API Key、固定翻译提示、可取消请求、
  Android Keystore 与隐私边界设计；通过同源 `GET /models` 自动获取可用模型，
  由用户从下拉列表选择，避免手工输入模型 ID。

### Changed

- 将 PP-OCRv6 small + ONNX Runtime Android 从隔离基准提升为 Debug/Release
  默认 OCR；官方检测/识别权重固定提交并在构建时校验 SHA-256。
- ML Kit Text Recognition 仅保留在 `benchmark` 变体中作为 v0.1.0 对照，
  Lite / Full 均使用 PP-OCRv6-small。
- ML Kit Translate 退到 `benchmark` build type；Lite / Full APK 只携带各自
  的 Bergamot 或 llama.cpp runtime。
- 应用版本提高为 `versionCode 2` / `0.2.0-lite` / `0.2.0-full`。
- GitHub Actions 改为分别测试、Lint、R8、签名和发布两个 edition，并验证
  v0.1.0 证书连续性、16 KiB 对齐、包内容和 SHA-256。

### Fixed

- 修复 HyperOS 已在系统设置选择「无限制」，应用却因 AOSP Doze 白名单状态
  显示“未放行”的误判。界面现在分别处理 Android 后台限制、AOSP 电源白名单
  与 HyperOS 厂商策略。
- 修复 Lite / Full 模型下载在完整 `.part` 文件处发送 EOF Range 并持续收到
  HTTP 416 的恢复阻断；完整文件改为先校验后接管，损坏文件从零重下。
- 修复 Full 中多个 Engine 竞争同一进程级 llama.cpp 状态的问题；运行时改为
  引用计数租约，并由 JNI owner token 阻止旧实例释放新实例。
- 为 Bergamot 子进程 READY 与翻译响应增加期限，停止时先终止进程再异步回收
  管道，避免阻塞前台服务的清理路径。

### Security

- Bergamot 模型同时校验压缩文件与解压文件的长度和 SHA-256；Full 固定
  Hy-MT2 revision、长度和 SHA-256。
- 两个 edition 都把翻译模型保存到应用专属 `no_backup` 目录，模型权重不进入
  APK/AAB。
- 完整的 common/Lite/Full 第三方许可证、版权 notices、固定模型坐标与
  Bergamot MPL 对应源码说明进入 edition-specific `assets/licenses/`，并随
  Release 提供统一 `THIRD-PARTY.zip`。
- 发布 workflow 对 Lite / Full 的签名证书、后端隔离和模型权重缺失做硬断言。

## [0.1.0] - 2026-07-26

### Added

- Android 16 / API 36 屏幕区域捕获、ML Kit OCR、端侧翻译与悬浮结果面板。
- 小米 15 Pro / HyperOS 真机验收记录。
- Apache-2.0 许可、贡献规范、行为准则、治理、安全、隐私和支持政策。
- GitHub Actions 构建、Lint、CodeQL、依赖审查和签名发布工作流。
- Dependabot 的 Gradle 与 GitHub Actions 每周更新策略。

### Changed

- 将构建说明从单台开发机路径调整为通用 JDK 17 / Android SDK 36 流程。
- 明确区分“屏幕内容在设备端处理”和 ML Kit SDK 的诊断/使用元数据网络行为。

### Security

- 发布签名从源码中分离，通过本地忽略文件或 GitHub Actions secrets 注入。
- Issue 与 PR 模板要求删除截图、OCR 文本和日志中的敏感内容。

## 发布规则

发布时将 `Unreleased` 中的内容移动到 `## [x.y.z] - YYYY-MM-DD`，同步提高
`versionCode` 与 `versionName`，完成真机验收后再创建 `vx.y.z` 标签。

[Unreleased]: https://github.com/Arimacose/ScreenTranslation/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/Arimacose/ScreenTranslation/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/Arimacose/ScreenTranslation/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/Arimacose/ScreenTranslation/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Arimacose/ScreenTranslation/releases/tag/v0.1.0
