# 发布流程

ScreenTranslation 使用语义化版本、签名 Android 包和 GitHub tag workflow。

## 1. 一次性准备

### 冻结应用身份

第一次公开发布前，仓库所有者应确认 `applicationId`、应用名称和签名证书身份。
Lite 保留 `com.screentranslation.app`，用于从 v0.1.0 覆盖升级；Full 使用
`com.screentranslation.app.full`，Online 使用 `com.screentranslation.app.online`，
三者可并存。Full 的应用标签、版本说明和发布说明均需明确标注
`HY-MT2 Q4 Experimental`；Online 需明确 OCR 文本会发送到所选托管网关或用户 API。

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

### GitHub Actions repository variable

正式发布还必须配置公开变量：

| Variable | 内容 |
|---|---|
| `MANAGED_CLOUD_BASE_URL` | 已完成公网验收的项目托管 HTTPS Base URL，例如 `https://PUBLIC_GATEWAY/v1` |

它是 APK 可见的公开地址，不是上游密钥。Release workflow 会拒绝空值或非 HTTPS
值；私有模型地址和 `UPSTREAM_API_KEY` 只配置在
`services/managed-cloud-gateway` 的服务器环境中。

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
export SCREEN_TRANSLATION_MANAGED_CLOUD_BASE_URL=https://PUBLIC_GATEWAY/v1
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

## 4. 创建发布标签

发布 PR 合并且 `main` 全绿后：

```bash
git switch main
git pull --ff-only
git tag -s v0.2.1 -m "ScreenTranslation v0.2.1"
git push origin v0.2.1
```

优先使用已验证签名的 annotated tag。Tag 必须与 Gradle `versionName` 完全匹配，否则 workflow
会终止。

## 5. 自动发布内容

`.github/workflows/release.yml` 会：

1. 验证四个签名 secrets 与托管网关 repository variable；
2. 检查 tag 与 `versionName`；
3. 以 recursive submodules 检出源码，并准备 Android 16、NDK r23b/r29 与 CMake 3.31.6；
4. 对 Lite/Full/Online 分别运行单元测试、release Lint、APK 和 AAB 构建；
5. 执行 16 KiB 对齐、APK v2 签名和既有证书摘要验证；
6. 断言包名、versionCode/versionName、应用标签、PP-OCRv6-small 资产、后端隔离、
   权重分发策略和 edition-specific `assets/licenses/`；
7. 归档完整第三方许可证、notices 与 MPL 对应源码坐标，自验 SHA-256 清单并分别
   保存三个 edition 的 R8 mapping Actions artifact；
8. 生成 GitHub 自动变更记录，并在前置说明中标记 Full 为 `HY-MT2 Q4 Experimental`、
   Online 为托管 Hy-MT2 / 用户 API 双链路；
9. 发布以下九项：
   - `ScreenTranslation-0.2.1-lite-bergamot.apk`
   - `ScreenTranslation-0.2.1-lite-bergamot.aab`
   - `ScreenTranslation-0.2.1-full-hymt2-q4-experimental.apk`
   - `ScreenTranslation-0.2.1-full-hymt2-q4-experimental.aab`
   - `ScreenTranslation-0.2.1-online-llm.apk`
   - `ScreenTranslation-0.2.1-online-llm.aab`
   - `ScreenTranslation-0.2.1-LICENSE.txt`
   - `ScreenTranslation-0.2.1-THIRD-PARTY.zip`
   - `SHA256SUMS`

## 6. 发布后核验

维护者从 GitHub release 重新下载产物：

```bash
sha256sum -c SHA256SUMS
apksigner verify --verbose --print-certs ScreenTranslation-0.2.1-lite-bergamot.apk
apksigner verify --verbose --print-certs \
  ScreenTranslation-0.2.1-full-hymt2-q4-experimental.apk
```

随后安装到干净测试设备，确认版本、首次权限链、模型下载和基础翻译。检查 release notes、
许可证、隐私链接和已知问题。

## 7. 回滚与安全修复

- 尚未分发的错误 release 可标记为 pre-release，并发布修正版；不要移动已公开 tag。
- 已分发版本通过更高 `versionCode` 的新版本修复。
- 签名或高风险漏洞进入 GitHub Security Advisory；修复发布后再公开细节。
- Store 发布撤回不等于设备端回滚，应在公告中说明受影响版本和用户操作。
