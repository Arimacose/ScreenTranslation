# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的结构，并使用
[Semantic Versioning](https://semver.org/lang/zh-CN/) 管理公开版本。

## [Unreleased]

### Added

- 增加隔离的 `benchmark` 变体、固定识屏夹具和 OCR/翻译质量评分工具。
- 增加 PP-OCRv6 small + ONNX Runtime Android 候选实现及小米 15 Pro 真机基准。
- 增加固定版本、校验哈希的 Firefox Translations 英中模型获取与 Bergamot 基准流程。

### Changed

- 将 OCR 抽象为 `OcrEngine` 接口，Release 继续使用 `MlKitOcrEngine` 默认实现。

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

[Unreleased]: https://github.com/Arimacose/ScreenTranslation/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Arimacose/ScreenTranslation/releases/tag/v0.1.0
