# 维护者手册

本文面向拥有 triage、write 或 admin 权限的维护者。贡献者流程见
[`CONTRIBUTING.md`](../CONTRIBUTING.md)，发布流程见 [`RELEASING.md`](RELEASING.md)。

## 1. GitHub 仓库初始设置

本地代码推送为公开仓库后，完成以下设置：

1. 将默认分支设为 `main`。
2. 启用 Issues、Discussions、Actions 和 Dependency graph。
3. 启用 Dependabot alerts、security updates、version updates。
4. 启用 Secret scanning 与 Push protection。
5. 启用 CodeQL default/advanced setup；本仓库已提供 advanced workflow。
6. 启用 Private vulnerability reporting。
7. 允许 squash merge，自动删除已合并分支。
8. 将 Actions 默认 `GITHUB_TOKEN` 权限设为只读；需要写权限的 release workflow 已显式声明。
9. 确定 GitHub 维护者账号后添加 `.github/CODEOWNERS`，不要保留占位账号。

## 2. `main` 规则集

建议建立分支规则集：

- 只允许通过 Pull Request 合并；
- 至少一次批准（只有一位维护者时可暂时为零，但仍保留 PR）；
- 新提交后撤销旧批准；
- 要求所有讨论解决；
- 要求分支与 `main` 同步；
- 阻止 force push 与删除；
- 要求以下检查通过：
  - Android CI / Unit tests, lint, and debug APK
  - Dependency review / Vulnerabilities and licenses
  - CodeQL / Analyze Java and Kotlin（适用时）

发布标签使用 `vMAJOR.MINOR.PATCH`。可为 `v*` 建立不可删除、不可移动的 tag 规则。

## 3. 标签与分流

建议保留或创建：

- `bug`
- `enhancement`
- `documentation`
- `dependencies`
- `security`
- `privacy`
- `device:hyperos`
- `area:capture`
- `area:ocr`
- `area:translation`
- `area:overlay`
- `good first issue`
- `help wanted`
- `needs reproduction`

每周至少一次处理新 issue：

1. 检查是否含敏感截图、日志或凭据；有则先隐藏并通知提交者。
2. 确认版本、设备、ROM、Android 构建和最短复现。
3. 合并重复项并保留证据最完整的主 issue。
4. 标记范围、优先级和下一步。
5. 安全问题立即转入私人 advisory。

## 4. PR 审查

审查顺序：

1. 用户授权、隐私和权限是否改变；
2. 生命周期、异常与停止路径是否完整；
3. 是否引入帧积压、主线程阻塞或资源泄漏；
4. 新依赖的许可、体积、维护和网络行为；
5. 单元测试、Lint、构建与设备证据；
6. 文档、变更记录和迁移说明。

捕获、服务、悬浮窗或 ROM 适配变更至少需要一次 Android 16 真机复测。

## 5. 依赖维护

Dependabot 每周检查 Gradle 和 GitHub Actions。合并前：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew :app:dependencies
```

- 安全修复优先处理。
- AGP、Gradle、compileSdk 和 JDK 作为一组升级并验证兼容矩阵。
- ML Kit Translate 更新必须复测模型下载、离线行为、翻译归属、APK 大小和数据披露。
- ONNX Runtime 更新必须执行普通 JVM 后处理测试、Debug/Release/R8 构建和
  小米 15 Pro 真机 OCR；目标 ROM 上曾出现 XNNPACK 原生崩溃，execution provider
  变化需要单独记录。
- `app/proguard-rules.pro` 中的 `-keep class ai.onnxruntime.** { *; }` 是
  ONNX Runtime JNI 的 Release/R8 运行约束；修改前须同时通过 APK DEX 校验与
  签名 Release 真机启动、识别测试。
- PP-OCRv6 权重更新必须同时修改固定上游提交、期望字节数和 SHA-256，重新生成
  字符表，并在同一批夹具上报告 CER、WER、精确匹配、延迟、PSS/RSS 与温升。
- `preparePpOcrv6Assets` 只接受哈希完全匹配的官方资产；模型文件继续留在
  `app/build/generated/ppocrv6Assets`，不直接提交到 Git。
- GitHub Actions 大版本更新先阅读运行时与 runner 最低版本要求。
- 不自动合并未经构建与许可证审查的依赖 PR。

## 6. 设备与质量基线

维护者保持以下证据：

- `docs/DEVICE_TEST.md`：设备、ROM、构建、功能和功耗记录；
- `docs/MODEL_BENCHMARK_2026-07-28.md`：PP-OCRv6 生产配置的来源与迁移基线；
- `docs/ARCHITECTURE.md`：数据流与系统边界；
- `CHANGELOG.md`：用户可见变化；
- `PRIVACY.md`：实际数据处理；
- 固定 OCR/翻译夹具，不使用私人屏幕内容。

新的支持设备只有在可重复完成核心矩阵后才加入 README。单次成功不等于长期支持承诺。

## 7. 隐私与商店披露

每次新增或更新 SDK 时，复核其官方数据披露。发布前核对：

- Manifest 权限与 README/PRIVACY 表格一致；
- 数据安全表包含 ML Kit 的设备、应用、安装标识、语言对和诊断信息；
- 应用未记录屏幕原文或译文；
- Release 包与源码对应，没有调试探针或测试服务器；
- Google Translate 归属说明仍可见。

## 8. 发布密钥与 Secrets

- Release keystore 至少保留两份加密离线备份，并记录恢复演练日期。
- 只向实际发布维护者授予仓库 secrets 管理权限。
- 密钥材料不粘贴到 issue、PR、Actions 日志或聊天记录。
- 怀疑泄露时停止发布、轮换 GitHub secrets、评估 Android 签名密钥升级路径并发布公告。

所需 secrets 见 [`RELEASING.md`](RELEASING.md)。

## 9. 连续性

- 至少两位维护者理解构建、真机测试、签名和安全 advisory 流程后再提高发布频率。
- 关键决定写入公开 issue/PR；密钥位置只记录在受控的私有运维文档。
- 每季度复查路线图、过期依赖、未处理漏洞和支持矩阵。
- 长期暂停维护时，在 README 顶部和最新 release 中明确状态。
