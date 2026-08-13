# 安全政策

## 支持版本

| 版本 | 安全更新 |
|---|---|
| `main` | 是 |
| 最新 GitHub release | 是 |
| 非最新的公开版本（包括 0.x） | 仅在维护者明确声明时 |

## 私人报告

请使用 GitHub 仓库 **Security → Advisories → Report a vulnerability** 私人报告漏洞。
仓库发布后，所有者应在设置中启用 Private vulnerability reporting。

若该入口尚未启用，可公开创建一个只写“请求安全联系渠道”的 issue；漏洞细节、利用样例、
真实截图、账号与密钥仍通过私人渠道发送。

报告建议包含：

- 受影响版本、commit 或 APK SHA-256；
- 设备、ROM 与 Android 构建；
- 攻击前提和最小复现；
- 对机密性、完整性、可用性或用户授权的影响；
- 已做脱敏的日志或示例；
- 建议修复或缓解措施。

维护目标为 7 天内确认、14 天内完成初步分级；复杂问题会在私人 advisory 中持续同步。

## 优先关注的安全边界

- 未经当前用户确认启动或复用 MediaProjection；
- 绕过 `FLAG_SECURE`、DRM、工作资料或系统策略；
- 截图、OCR 原文、译文或选择区域被持久化、记录或上传；
- 悬浮窗欺骗、触摸劫持或遮挡安全提示；
- 导出的 Activity、Service、Receiver 或 PendingIntent 被外部滥用；
- 路径穿越、任意文件读取、日志泄露或模型供应链问题；
- 发布签名、GitHub Actions secrets、构建产物或更新渠道被篡改。

## 披露流程

1. 维护者在私人 advisory 中复现并确定影响范围。
2. 在私有临时分支准备修复、测试和必要的密钥轮换。
3. 发布修复版本与安全公告；高风险问题视情况申请 CVE。
4. 公告后再公开修复细节与回归测试。

请给维护者合理修复窗口后再公开技术细节。

## 发布与供应链

- 仓库不保存 release keystore、密码或私钥。
- GitHub release 由标签工作流构建，APK 必须通过 `zipalign` 与 `apksigner` 验证。
- Release 同时发布 SHA-256 清单；维护者下载后再次核对。
- 依赖更新经过 Dependabot、依赖审查、CI 和适用的 CodeQL 检查。
- 引入新 SDK 时同步复核许可证、维护状态、权限、网络和数据披露。
