# 发布流程

ScreenTranslation 使用语义化版本、签名 Android 包和 GitHub tag workflow。

## 1. 一次性准备

### 冻结应用身份

第一次公开发布前，仓库所有者应确认 `applicationId`、应用名称和签名证书身份。
当前 `com.screentranslation.app` 是开发阶段标识；公开分发后再修改会被 Android 视为另一个应用，
也会中断原包名的覆盖升级。

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
   - `versionName`：`MAJOR.MINOR.PATCH`。
3. 将 `CHANGELOG.md` 的 `Unreleased` 内容移动到带日期的版本标题。
4. 更新 README、隐私说明、第三方条款、设备测试和迁移说明。
5. 冻结新的非必要功能，集中修复发布阻断问题。

查看 Gradle 解析的版本：

```bash
./gradlew -q :app:printVersionInfo
```

## 3. 本地验证

```bash
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
./gradlew --no-daemon lintRelease assembleRelease bundleRelease
```

配置 `keystore.properties` 后，验证 APK：

```bash
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

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
git tag -s v0.1.0 -m "ScreenTranslation v0.1.0"
git push origin v0.1.0
```

优先使用已验证签名的 annotated tag。Tag 必须与 Gradle `versionName` 完全匹配，否则 workflow
会终止。

## 5. 自动发布内容

`.github/workflows/release.yml` 会：

1. 验证四个签名 secrets；
2. 检查 tag 与 `versionName`；
3. 运行单元测试、release Lint、APK 和 AAB 构建；
4. 执行 16 KiB 对齐检查与 APK 签名验证；
5. 生成 APK/AAB SHA-256 清单；
6. 保存 R8 mapping Actions artifact；
7. 创建包含 APK、AAB、许可证、第三方条款和 `SHA256SUMS` 的 GitHub release。

## 6. 发布后核验

维护者从 GitHub release 重新下载产物：

```bash
sha256sum -c SHA256SUMS
apksigner verify --verbose --print-certs ScreenTranslation-0.1.0.apk
```

随后安装到干净测试设备，确认版本、首次权限链、模型下载和基础翻译。检查 release notes、
许可证、隐私链接和已知问题。

## 7. 回滚与安全修复

- 尚未分发的错误 release 可标记为 pre-release，并发布修正版；不要移动已公开 tag。
- 已分发版本通过更高 `versionCode` 的新版本修复。
- 签名或高风险漏洞进入 GitHub Security Advisory；修复发布后再公开细节。
- Store 发布撤回不等于设备端回滚，应在公告中说明受影响版本和用户操作。
