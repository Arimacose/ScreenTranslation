# 发布流程

ScreenTranslation 使用语义化版本、签名 Android 包和 GitHub tag workflow。

## 1. 一次性准备

### 冻结应用身份

第一次公开发布前，仓库所有者应确认 `applicationId`、应用名称和签名证书身份。
Lite 保留 `com.screentranslation.app`，用于从 v0.1.0 覆盖升级；Full 使用
`com.screentranslation.app.full`，Online 使用 `com.screentranslation.app.online`，
三者可并存。Full 的应用标签、版本说明和发布说明均需明确标注
`HY-MT2 Q4 Experimental`；Online 需明确 OCR 文本会发送到用户选择的 API。

### 创建发布密钥

在受控环境中创建 keystore，并至少保存两份离线备份。密钥文件和密码不得进入 Git。

本地签名构建可复制：

```text
keystore.properties.example -> keystore.properties
```

然后填写绝对 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`。

### GitHub Actions secrets

仓库需要：

| Secret | 内容 |
|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | release keystore 的 Base64 文本 |
| `ANDROID_RELEASE_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_RELEASE_KEY_ALIAS` | key alias |
| `ANDROID_RELEASE_KEY_PASSWORD` | key 密码 |

Base64 只用于让 GitHub secret 保存二进制 keystore，不是安全边界；机密性来自 GitHub secrets
权限和离线原件保护。

## 2. 准备版本

1. 从最新 `main` 创建发布 PR。
2. 在 `app/build.gradle.kts` 提高：
   - `versionCode`：严格递增整数；
   - 基础 `versionName`：`MAJOR.MINOR.PATCH`；
   - Lite/Full/Online flavor 后缀分别为 `-lite`、`-full` 和 `-online`。
3. 将 `CHANGELOG.md` 的 `Unreleased` 内容移动到带日期的版本标题。
4. 更新 README、隐私说明、第三方条款、设备测试和迁移说明。
5. 冻结新的非必要功能，集中修复发布阻断问题。

查看 Gradle 解析的版本：

```bash
./gradlew -q :app:printVersionInfo
```

## 3. 本地验证

```bash
./gradlew --no-daemon clean \
  testLiteDebugUnitTest testFullDebugUnitTest testOnlineDebugUnitTest \
  lintLiteRelease lintFullRelease lintOnlineRelease \
  assembleLiteRelease assembleFullRelease assembleOnlineRelease \
  bundleLiteRelease bundleFullRelease bundleOnlineRelease
```

配置 `keystore.properties` 后，验证 APK：

```bash
zipalign -c -P 16 -v 4 app/build/outputs/apk/lite/release/app-lite-release.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/full/release/app-full-release.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/lite/release/app-lite-release.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/full/release/app-full-release.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/online/release/app-online-release.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/online/release/app-online-release.apk
```

三份 APK 的预期签名证书 SHA-256 均为
`b58712578045532158d45b847ab7ed1be041236b5a7a0bd1a1db5480fbe0439f`。
同时核验 PP-OCRv6-small 三个资产存在、每份 APK 只含对应翻译后端，且翻译模型权重由运行时下载。

在声明支持的 Android 16 / HyperOS 真机上执行 `docs/DEVICE_TEST.md` 核心矩阵，并记录：

- commit 和版本；
- APK SHA-256 与签名证书摘要；
- 设备、ROM、Android 构建；
- 模型下载、离线翻译、旋转、中断、停止与任务移除；
- 已知回归和残余风险。

## 4. 生成并验收冻结的签名 Artifact

发布 PR 合并、`main` 全绿后，在 Actions 手动运行 `Signed release and acceptance`，选择
`operation=build`。该入口要求 dispatch commit 等于当前 `origin/main`，并保存名称包含
source SHA 的 30 天签名 acceptance Artifact。下载并验证其中的 `SHA256SUMS`，随后使用
三份**同一 Artifact 内的 APK**完成 `docs/DEVICE_TEST.md` 真机矩阵。

验收通过后，按 `docs/DEVICE_TEST.md` 的固定 ASCII 字段格式在 Issue #47 添加
`DEVICE_ACCEPTANCE_PASS` 评论；评论必须绑定 build run ID、source SHA、目标设备/ROM 和
精确机型/ROM build token，以及三份 APK SHA-256。移除 `status:needs-verification` 并关闭
Issue #47。

## 5. 创建发布标签

accepted run 的 source commit、当前 `origin/main` 与准备标记的 commit 必须完全一致：

```bash
git switch main
git pull --ff-only
VERSION=2.0.0
git tag -a "v$VERSION" -m "ScreenTranslation v$VERSION"
git push origin "v$VERSION"
```

当前仓库历史采用 annotated tag；发布 workflow 会验证它确实是 tag object、其 commit
属于 `origin/main`，并要求 tag 名与 Gradle `versionName` 完全匹配。若维护者已配置 GPG/SSH
签名，可把 `-a` 换成 `-s`。`gh release create --verify-tag` 只核对远端 tag 已存在，
不等同于验证 tag 的密码学签名；Android APK/AAB 的发布签名由独立 keystore 门禁负责。

推送 tag 只建立版本引用，不会触发构建或自动发布，因而不会在真机验收后替换 APK 字节。

## 6. 原样提升已验收 Artifact

再次在 Actions 手动运行 `Signed release and acceptance`，选择 `operation=publish` 并填写：

- `acceptance_run_id`：完成真机验收的 `operation=build` run ID；
- `release_tag`：刚推送的 annotated/signed tag，例如 `v2.0.0`；
- `device_evidence_comment`：Issue #47 中 `DEVICE_ACCEPTANCE_PASS` 评论的完整 URL。

promotion job 采用 fail-closed 顺序：

1. 要求 dispatch ref、tag commit、accepted run `head_sha` 和当前 `origin/main` 完全相同；
2. 要求 tag 名匹配 Gradle `versionName`，tag 为 annotated/signed object；
3. 核验 accepted run 来自本仓库 release workflow 的成功 `workflow_dispatch`，并取得唯一、
   未过期、具有 SHA-256 digest 的 acceptance Artifact；
4. 要求 Issue #47 已关闭、`status:needs-verification` 已移除，证据评论来自仓库所有者或
   协作者，时间晚于 accepted run 且早于 Issue 关闭；评论须一次写定且所有必需字段唯一，
   promotion 会冻结正文 SHA-256 并在公开前再次核对；
5. 下载 Artifact ZIP 并核对 GitHub digest，要求 ZIP entry 精确等于预期九个平面文件，
   拒绝额外/缺失/嵌套路径和符号链接，随后执行 `sha256sum -c SHA256SUMS`；
6. 重新核验三 APK 的包名、版本、targetSdk 36、应用标签、非 debuggable、仅 ARM64、
   v2 signer、16 KiB 对齐、PP-OCRv6/ONNX Runtime、后端隔离与零内置翻译权重；核验
   三 AAB 的 JAR 签名证书同样匹配固定 release 证书和 edition 许可；
7. 核对第三方许可 42 项 SHA-256、根 LICENSE，以及证据评论里的三 APK SHA-256；
8. 先创建仅维护者可见的 Draft Release，从该目录原样上传以下九项并逐项核对 GitHub
   记录的名称、字节数与 SHA-256 digest；再次核验 `main`、tag、Issue 状态与评论正文后
   才公开，并把 run、Artifact digest、评论正文 SHA-256 和真机证据 URL 写入 Release notes：
   - `ScreenTranslation-$VERSION-lite-bergamot.apk`
   - `ScreenTranslation-$VERSION-lite-bergamot.aab`
   - `ScreenTranslation-$VERSION-full-hymt2-q4-experimental.apk`
   - `ScreenTranslation-$VERSION-full-hymt2-q4-experimental.aab`
   - `ScreenTranslation-$VERSION-online-llm.apk`
   - `ScreenTranslation-$VERSION-online-llm.aab`
   - `ScreenTranslation-$VERSION-LICENSE.txt`
   - `ScreenTranslation-$VERSION-THIRD-PARTY.zip`
   - `SHA256SUMS`

该过程不读取 release keystore，也不重新运行 Gradle 构建；公开资产与真机验收资产保持
逐字节一致。

## 7. 发布后核验

维护者从 GitHub release 重新下载产物：

```bash
VERSION=2.0.0
sha256sum -c SHA256SUMS
apksigner verify --verbose --print-certs "ScreenTranslation-$VERSION-lite-bergamot.apk"
apksigner verify --verbose --print-certs \
  "ScreenTranslation-$VERSION-full-hymt2-q4-experimental.apk"
apksigner verify --verbose --print-certs \
  "ScreenTranslation-$VERSION-online-llm.apk"
```

核对公开 Release 九个资产的名称、字节数与 SHA-256 均匹配 accepted Artifact；检查
Release notes 中的 source commit、build run、Artifact digest、Issue #47 证据 URL、许可、
隐私链接和已知问题。由于 promotion 上传的是已验收的同一组文件，无需用重构建产物重复
替代原验收结论。

## 8. 回滚与安全修复

- 尚未分发的错误 release 可标记为 pre-release，并发布修正版；不要移动已公开 tag。
- 已分发版本通过更高 `versionCode` 的新版本修复。
- 签名或高风险漏洞进入 GitHub Security Advisory；修复发布后再公开细节。
- Store 发布撤回不等于设备端回滚，应在公告中说明受影响版本和用户操作。
