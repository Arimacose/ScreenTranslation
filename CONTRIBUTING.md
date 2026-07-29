# 贡献指南

感谢参与 ScreenTranslation。项目当前处于 `0.x` 阶段，优先保证 Android 16、
MediaProjection 生命周期、隐私边界和小米 15 Pro / HyperOS 的可验证行为。

参与讨论、提交 issue 或代码即表示同意遵守 [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)。

## 开始前

1. 搜索现有 issue、PR 和 [`docs/ROADMAP.md`](docs/ROADMAP.md)。
2. 修复范围明确的小问题可直接提交 PR；跨模块功能、权限、网络传输或数据保存变更先开 issue。
3. 安全问题使用 [`SECURITY.md`](SECURITY.md) 中的私人报告流程。
4. 使用测试页面或脱敏样例复现，避免上传真实聊天、账号、支付或工作资料画面。

## 开发环境

- JDK 17
- Android SDK Platform 37（运行目标仍为 API 36）
- Android SDK Build Tools 37.0.0
- Git
- 可选：Android 16 设备；ROM 相关修改需要小米 15 Pro / HyperOS 复测

克隆后在仓库根目录执行：

```bash
./gradlew --version
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

本机 SDK 路径只写入被忽略的 `local.properties`：

```properties
sdk.dir=C\:\\Android\\Sdk
```

首次构建会运行 `preparePpOcrv6Assets`，从 PaddlePaddle 官方仓库下载固定版本
ONNX 权重并校验 SHA-256。模型缓存属于 `app/build` 生成物，不提交到 Git。

## 分支与提交

- 默认分支：`main`
- 建议分支：`fix/<topic>`、`feat/<topic>`、`docs/<topic>`
- 建议提交格式：`type(scope): summary`
- 常用类型：`feat`、`fix`、`refactor`、`test`、`docs`、`build`、`ci`、`chore`
- 一个提交尽量只表达一个可回滚的意图；生成物、密钥和本机路径不进入 Git

提交作者应使用可识别的 Git 名称和邮箱，以便正确保留贡献归属。

## 代码边界

- 屏幕捕获必须由用户主动发起，并使用新的系统 MediaProjection 授权。
- 不通过无障碍服务替代屏幕共享授权。
- 截图、OCR 原文和译文默认只存在于内存，不写日志、不落盘、不加入分析事件。
- 每个耗时异步组件必须有明确关闭路径；服务停止后释放投影、显示、图像读取器、ML 客户端和悬浮窗。
- 捕获循环保持单飞与背压，避免因积压帧增加功耗和延迟。
- 新翻译后端必须通过接口隔离，并在文档中说明质量、成本、网络和数据处理差异。
- 新依赖必须检查维护状态、许可证、二进制体积和数据披露。

架构说明见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 测试要求

所有 PR 至少执行：

```text
testDebugUnitTest
lintDebug
assembleDebug
```

按变更类型增加验证：

| 变更 | 必测项目 |
|---|---|
| 稳定门、调度、语言映射 | 对应 JVM 单元测试 |
| 权限或服务生命周期 | 授权、拒绝、停止、撤销、任务移除 |
| MediaProjection 或区域坐标 | 旋转、尺寸变化、重新框选、悬浮层遮蔽 |
| OCR 或翻译 | 固定文本夹具、语言对、空文本、模型缺失；OCR 还需报告 CER/WER、延迟和内存 |
| HyperOS 行为 | 小米 15 Pro 真机，记录 ROM 与 Android 构建号 |
| UI | 明暗主题、超长文本、字体缩放、edge-to-edge |

设备测试记录应追加到 [`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md)，不要覆盖历史证据。

## Pull Request

PR 应包含：

- 用户问题与行为变化；
- 最小实现说明；
- 执行过的命令和设备矩阵；
- 已脱敏截图或说明“不适用”；
- 隐私、权限、依赖和迁移影响；
- 需要时更新 `README.md`、`PRIVACY.md`、`SECURITY.md`、`CHANGELOG.md`。

CI、依赖审查和适用的 CodeQL 检查通过后再合并。维护者可要求拆分过大的 PR。

## 许可

提交贡献即表示你有权提交相关内容，并同意按照仓库的
[Apache License 2.0](LICENSE) 许可该贡献。第三方代码必须保留来源与许可信息。
