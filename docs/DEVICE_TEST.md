# 小米 15 Pro / HyperOS / Android 16 真机验收

本文是目标 ROM 的可重复验收清单。不要用模拟器结果替代 MediaProjection、HyperOS 悬浮窗、后台策略和温控测试。

## 2026-08-01 Online edition 候选（本地构建通过，真机待测）

- 已实现独立 `com.screentranslation.app.online` / `0.2.1-online` flavor，Online
  单元测试 83 项全部通过，Release Lint 为 0 error / 7 条既有基线 warning，
  R8 APK、AAB 与 debug-signed 可安装 APK 均构建成功。
- 静态 APK 审计确认只包含 ARM64 PP-OCRv6、ONNX Runtime 和 Online HTTP 路径；
  Bergamot runner、HY-MT2 JNI、ML Kit Translate 与翻译模型权重均未进入包。
- 本机工作树没有发行 keystore 或 `ANDROID_RELEASE_*` 环境变量；当前 Release APK
  是未签名的 R8 审计产物，可安装候选为标准 debug 证书签名包。正式发行由 GitHub
  Secrets 注入既有证书。
- 本轮尚未连接或安装到真机。开始前需明确通知设备持有人；真机/API 验收依次检查：
  自动补全 `GET /models`、含空格/连字符模型 ID 的下拉选择、自动补全
  `POST /chat/completions`、设置页不回显密钥、重启后密钥与模型状态、真实 HTTPS
  翻译、单稳定区域单请求、快速三段
  latest-wins、停止/重选/息屏取消、401/429/timeout UI、logcat 脱敏，以及抓包确认
  只有 OCR 文本 JSON 而无截图二进制。

## Issue #11 截图可见性回归（代码验收完成，真机待测）

- `OverlayController` 的窗口 flags 已移除 `FLAG_SECURE`，JVM 回归测试同时
  断言该标志缺失及三个既有交互/布局标志仍保留。
- MediaProjection 识别链路继续接收悬浮面板的规范化坐标，并由
  `BitmapExtractor` 在 OCR 前填白该区域。
- 本轮按任务范围只做代码、单元测试、Lint 和构建验收。后续真机验收需在
  小米 15 Pro / HyperOS 上分别保存一张系统截图和一段录屏，确认译文面板
  可见；再持续识别动态文本，确认上一帧译文没有回灌到后续 OCR 结果。

## 2026-07-31 v0.2.1 公开 Release 热修复验收

### 发布物与静态复核

v0.2.1 标签指向合并提交
`d28728fe50802b157f4894c961912218a2a8076e`。签名发布工作流
[`30570083086`](https://github.com/Arimacose/ScreenTranslation/actions/runs/30570083086)
成功，正式 Release 位于
[`v0.2.1`](https://github.com/Arimacose/ScreenTranslation/releases/tag/v0.2.1)。

| 项目 | Lite · Bergamot | Full · HY-MT2 Q4 Experimental |
|---|---|---|
| 包名 / 版本 | `com.screentranslation.app` / `0.2.1-lite` | `com.screentranslation.app.full` / `0.2.1-full` |
| APK 大小 | 46,226,563 B | 45,218,284 B |
| APK SHA-256 | `8C1527FD359D2E70F5C7F0E189A9D702F30C70A95DCCE8919E5BC251A28444CB` | `757A51329A9DC6CC81C5C9342AD9B3807C9B24F3BD392C4711CD00B4724CB5DB` |
| 版本码 | 3 | 3 |
| 发布定位 | 稳定 Lite | 明确标注 Experimental |

从 GitHub Release 重新下载全部七个公开资产后完成独立复核：

- GitHub 资产大小与服务器 SHA-256 元数据 7/7 匹配，`SHA256SUMS` 6/6
  通过；
- 两份 APK 均为非 debuggable Release，使用 APK Signature Scheme v2，
  通过 16 KiB page-aware zipalign，签名证书 SHA-256 为
  `B58712578045532158D45B847AB7ED1BE041236B5A7A0BD1A1DB5480FBE0439F`；
- 两份 AAB 均通过 JAR 签名验证，证书与 APK 相同；
- Lite/Full 均携带 PP-OCRv6-small；Lite 只包含 Bergamot runner，Full
  只包含 HY-MT2 JNI，发布包不内置翻译权重；
- 第三方 ZIP 含 43 个文件，内部 `SHA256SUMS` 的 41 项全部通过；
- Lite/Full 共 137 项 JVM 测试、双版本 Release Lint、R8 APK/AAB 构建
  均通过。两份 R8 mapping 由 Actions 保留至 2026-10-28。

### Lite：Issue #28 最终回归

公开 Lite APK 使用 `adb install -r` 覆盖本地候选后，设备端 `base.apk`
SHA-256 与上述 GitHub 下载物完全一致。目标机为 Xiaomi 15 Pro
`2410DPN6CC` / `haotian`，Android 16 / API 36，HyperOS
`OS3.0.304.0.WOBCNXM`。

清空 logcat 与 `ApplicationExitInfo` 后连续执行五轮：

1. 启动识屏并确认 Bergamot runner、前台服务、MediaProjection 和
   `ScreenTranslationCapture` VirtualDisplay 均已建立；
2. 点击真实悬浮层“停止”按钮；
3. 等待上述四项资源全部归零；
4. 比对停止前后的主进程 PID。

五轮均通过，主进程 PID 始终为 `18494`。新日志窗口中
`FATAL EXCEPTION: bergamot-lite-stderr`、对应
`InterruptedIOException`、unexpected stderr reader warning、应用 ANR、
OOM 和 native fatal 的命中均为 0；清空后的 `exit-info` 中 APP CRASH 与
ANR 也均为 0。由此确认 Issue
[#28](https://github.com/Arimacose/ScreenTranslation/issues/28) 的 stderr
关闭竞态在公开 Release 中已修复。

通过 ADB 覆盖安装时，HyperOS 再次把悬浮窗 app-op 重置为 `ignore`，通知
权限保持 `allow`；恢复测试基线后应用内两项状态均为已授予。该厂商侧现象与
v0.2.0 验收一致，不用于推断普通用户安装器升级时的权限保留行为。

### Full：公开包自检

公开 Full APK 的设备端 `base.apk` SHA-256 同样与 GitHub 下载物完全一致。
界面标题、副标题和 Banner 均显示
`Full · HY-MT2 Q4 Experimental`。应用内模型为
`Hy-MT2-1.8B-Q4_K_M.gguf`，实测 1,133,080,448 B，SHA-256 为
`dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`。

固定英文长句端侧自检耗时 `17,875 ms`，通过并得到：

```text
虽然委员会承认该提议能在短期内降低成本，但还是推迟了投票，
因为没有人能解释该系统如何保护那些被误标记的用户。
```

自检后的 logcat 与 `exit-info` 中崩溃、ANR、OOM 和 native fatal 均为
0。v0.2.0 Release 已保留原标签和七个资产，并增加 superseded 警告指向
v0.2.1。

## 2026-07-31 v0.2.0 Lite / Full 签名 Release 验收

### 构建与发布候选

| 项目 | Lite · Bergamot | Full · HY-MT2 Q4 Experimental |
|---|---|---|
| 包名 / 版本 | `com.screentranslation.app` / `0.2.0-lite` | `com.screentranslation.app.full` / `0.2.0-full` |
| APK 大小 | 46,232,307 B | 58,235,784 B |
| APK SHA-256 | `AA1DA5694D86239A79BE91563E4E30D84898EEA8DF8F4A45DEC0907DE893DB6E` | `5325347B5A2A4B637F4159930A0B57FE93EF20136BB62D36F0F994EE6A6E8DC8` |
| JVM 测试 | 67/67 | 68/68 |
| Release Lint | 0 error / 7 warning | 0 error / 7 warning |
| 运行中 PSS | 206,235 KiB（英中）；185,367 KiB（日中） | 2,314,830 KiB |

两份 Release APK 均由 v0.1.0 的发布证书签名，证书 SHA-256 为
`B58712578045532158D45B847AB7ED1BE041236B5A7A0BD1A1DB5480FBE0439F`，
并通过 APK Signature Scheme v2、16 KiB page-aware zipalign、ABI、OCR
资产和翻译后端隔离检查。两份 AAB 也通过 JAR 签名验证。干净构建共执行
135 项测试，failure、error、skip 均为 0。

目标机仍为 Xiaomi 15 Pro `2410DPN6CC` / `haotian`，Android 16 / API 36，
HyperOS `OS3.0.304.0.WOBCNXM`，显示为 `1440 × 3200 @ 600 dpi`。

### Lite：签名升级、HyperOS Issue #26 与 Bergamot

- 先安装已发布且证书匹配的 v0.1.0 Release，再用 `adb install -r` 原位升级；
  `versionCode` 从 1 变为 2，`firstInstallTime=2026-07-31 00:35:26`
  保持不变。
- 真正在 HyperOS 应用详情中把省电策略从“智能限制后台运行”切换到
  “无限制”，返回应用后的状态为：
  `系统后台限制：未检测到；HyperOS「无限制」状态请以设置页为准`。
  旧的“未放行”误报未再出现，Issue #26 的 ROM 路径验收通过。
- 英语→简体中文直模和日语→英语→简体中文级联均完成首次下载、长度/SHA-256
  校验，并显示“模型已就绪”。
- 两条路线都完成了真实
  `MediaProjection → PP-OCRv6-small → Bergamot → 悬浮窗` 闭环；
  停止后服务、投影和 VirtualDisplay 均归零。
- 日中使用禁止浏览器自动翻译的纯日文长句复测。OCR 原文保持日语，但级联
  译文仍出现明显语义与语序退化；因此该路线通过的是部署和链路验收，翻译
  质量作为 Lite 的已知限制保留，不与 Full 的质量结论混用。

HyperOS 在本次通过 ADB 注入的权限基线上进行包替换时重置了通知和悬浮窗
状态；重新按应用内入口授权后两项均显示“已授予”。这次现象不用于推断真实
用户手工授权的升级保留行为，后续发布回归应继续单独检查。

### Full：HY-MT2 Q4 Experimental

- 最终安装包为非 debuggable Release；应用标题、副标题、橙色 Banner、
  通知和结果 attribution 均明确标注 `Full · HY-MT2 Q4 Experimental`。
- 固定模型为 `Hy-MT2-1.8B-Q4_K_M.gguf`，大小
  1,133,080,448 B，SHA-256
  `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`。
  模型先写入同证书 debuggable 包的私有目录并复核，再由正式 Release 原位
  覆盖；Release 自检再次执行应用内校验和实际 llama.cpp 推理。
- 固定英文长句自检两次通过，耗时分别为 `22,579 ms` 和 `20,724 ms`；
  第二次在五轮快速启停之后执行，译文仍完整：

```text
虽然委员会承认该提议能在短期内降低成本，但还是推迟了投票，
因为没有人能解释该系统如何保护那些被误标记的用户。
```

- 服务运行时自检按钮为 disabled；五轮快速启停中每轮前台服务和
  MediaProjection 均建立，每轮停止后两者均归零。
- 在不含标题或浏览器翻译干扰的英文长句页上，完整识屏链路的展开译文为：

```text
尽管委员会承认该提案能在短期内降低成本，但还是推迟了投票。
因为没有人能解释，这个系统如何保护那些被误标记的账户的用户。
```

- 运行中 PSS 为 2,314,830 KiB，低于本轮 2.7 GiB 门槛；该内存成本与约
  20 秒长句耗时也是 Full 保持 Experimental 标识的原因。

本轮保存的全部 logcat 中，`FATAL EXCEPTION`、应用 ANR、
`OutOfMemoryError` 和 native fatal signal 命中均为 0。验收完成后本地
HTTP 夹具服务与 ADB reverse 已清理。

## 2026-07-29 PP-OCRv6 生产包验收

### 本机构建与包检查

| 项目 | 实测值 |
|---|---|
| 分支 | `codex/ppocrv6-production` |
| 版本 | `0.1.0` |
| 生产 OCR | PP-OCRv6 small det/rec + ONNX Runtime Android 1.26.0 |
| 保留配置 | CPU4、batch 1、检测最长边 640、memory pattern/CPU arena 关闭 |
| JVM 测试 | Debug 52/52、Benchmark 52/52，通过且无跳过 |
| Lint | `lintDebug` 0 error、7 warning |
| 构建 | Debug、Benchmark、Release/R8 APK 与 Release AAB 全部成功 |
| QA APK | `app/build/outputs/apk/qa/ScreenTranslation-0.1.0-ppocrv6-qa.apk` |
| QA APK 大小 | 77,615,403 B |
| QA APK SHA-256 | `DA207612678980A6DA9DA5D489EE11B6C45F06230BA4D0C11247E523D1DBB18C` |
| QA 签名 | Android Debug，证书 SHA-256 `D5EE8BD74DEDF58DFCE208E27A5FB2A38B08176F748C9BA669ACBB4FA971393D` |

Release APK 中的检测模型、识别模型和字符表均按固定字节数与 SHA-256
复核通过；包内 ABI 只有 `arm64-v8a`，生产 JNI 只有 ONNX Runtime 与
ML Kit Translate，未带入 benchmark 专用的 ML Kit OCR。R8 后还直接检查了
`OnnxTensor.createTensor`、`OrtSession.run` 和两个 Firebase registrar
构造器，APK 通过 16 KiB page-aware zipalign 与 APK Signature Scheme v3 验证。

### 小米 15 Pro 生产链路实测

2026-07-29 已把上述 QA APK 安装到目标机并完成合并前验收；受测代码提交为
`62d1234dce824d51de715b7f1589a3dd8747376d`。

| 项目 | 实测值 |
|---|---|
| 设备 | Xiaomi 15 Pro，型号 `2410DPN6CC`，设备代号 `haotian` |
| 系统 | Android 16 / API 36，安全补丁 `2026-06-01` |
| ROM | HyperOS `OS3.0.304.0.WOBCNXM` |
| 安装版本 | `versionCode=1`、`versionName=0.1.0`、`targetSdk=36` |
| 屏幕 | `1440 × 3200`、`600 dpi`、手势导航 |
| HyperOS 电池策略 | 保持系统当前“智能”策略，未为测试放宽 |
| 采帧节流 | 默认 `450 ms` |
| 结论 | PP-OCRv6 生产集成与 Android 16 / HyperOS 生命周期验收通过 |

实际链路为 MediaProjection → PP-OCRv6 small det/rec → ML Kit Translate →
悬浮窗。模型准备、系统录屏确认、全屏叠加层框选、持续 OCR、英译中和显式
停止均在真机上完成。固定样例中的标题、动态编号、两个英文动态句、订单号
`XT-2048`、金额 `$1,249.50` 和日期 `2026-07-31` 均被正确识别；页面从
alpha 句切到 beta 句时，无需重新框选即可更新结果。

展开态保存的代表性结果为：

```text
PP-OCRv6 PRODUCTION ACCEPTANCE Dynamic sample 12
The translation service is processing sample alpha while the target application remains visible.
Order XT-2048 totals $1,249.50 on 2026-07-31.
```

ML Kit 给出的对应译文语义可读，并保留订单号、金额和日期；同时仍可见
“订单……总数”和 beta →“测试版”等措辞问题。这与既有长句质量结论一致：
本 PR 验收的是 OCR 迁移和生产链路，翻译模型质量仍是后续独立工作。

### 15 分钟持续运行

- `904.832 s` 内共采样 16 次，PID 始终为 `23440`；
- 16/16 次均同时存在前台服务、MediaProjection 和
  `ScreenTranslationCapture` VirtualDisplay；
- PSS 为 `171,265–253,467 KiB`，第 15 分钟为 `171,670 KiB`；
  峰值后回落到基线附近，额外运行一分钟后为 `181,438 KiB`，未见持续增长；
- 电池温度从 `30.0°C` 升至 `33.1°C`，额外一分钟后为 `33.3°C`；
- 额外 `60.202 s` 全线程 CPU 时间为 `138,776,356,358 ns`，相当于
  单核 `230.5193%`（约 2.31 个核心）；这是当前 `450 ms` 连续 OCR
  配置的明确功耗成本，后续可通过变化检测或自适应节流优化；
- 日志扫描中崩溃、ANR、OOM 和 fatal signal 均为 0。

从悬浮层点击“停止”后，服务、MediaProjection、VirtualDisplay、应用悬浮
窗和活动前台通知均归零。测试期间仅临时启用了 USB 亮屏，结束后已恢复原值；
ADB reverse、本地 HTTP 服务和替换过的测试页也均已清理或恢复。

手势导航下还记录到一个非阻断交互点：从屏幕最左侧约 80 px 起手框选会先
触发系统返回手势；从选区内部起手可正常提交。后续可评估系统手势排除矩形或
在提示文字中明确避开边缘。

相同 PP-OCRv6 配置此前的独立模型基准为 CER `0.1218%`、WER `0.5263%`、
7 例精确匹配 6 例、中位 `403.429 ms`、p95 `810.762 ms`、HWM
`399,964 KiB`。本次生产链路实测补齐了该基准之外的 ROM、投屏、翻译、
叠加层和长会话证据。

完整证据保存在忽略构建目录：

```text
app/build/device-test/ppocrv6-production-2026-07-29/
```

## 2026-07-28 Issue #7 锁屏生命周期验收

### 构建与设备

| 项目 | 实测值 |
|---|---|
| 设备 | Xiaomi 15 Pro，型号 `2410DPN6CC`，设备代号 `haotian` |
| 系统 | Android 16 / API 36 |
| ROM | HyperOS `OS3.0.304.0.WOBCNXM` |
| 变体 | Release，R8 开启，QA debug 证书签名 |
| APK SHA-256 | `098E855E66ED941CBD7BE006BE458EF7C63283B467102284C17F1BBCE23694C1` |
| 签名证书 SHA-256 | `D5EE8BD74DEDF58DFCE208E27A5FB2A38B08176F748C9BA669ACBB4FA971393D` |
| 自动检查 | 48 项 JVM 测试、`lintDebug`、`assembleRelease` 与 R8 均成功 |

### 量化结果

- 英文 OCR 与中文翻译持续运行时，30.172 秒亮屏基线消耗
  `5,351,292,224 ns` CPU 时间，相当于单核 `17.7358%`。
- 发送休眠键后设备保持 `Dozing` 且 Keyguard 可见；15.150 秒窗口内应用
  CPU 时间增量为 `0 ns`，相当于单核 `0.0000%`。
- 锁屏触发 `MediaProjection.Callback.onStop()`；随后服务、
  MediaProjection、VirtualDisplay 与采集悬浮层全部清理。
- 非持续通知 `id=1104` 正常出现，包含标题、说明、操作按钮和指向
  `MainActivity` 的 PendingIntent。
- 解锁后点按通知，通知自动清除；主界面显示
  “屏幕共享会话已结束；Android 16 需要重新授权后继续”。
- 再次点击开始会出现新的系统屏幕共享确认页；确认、重新框选后，英文 OCR
  与中文翻译恢复。
- 最终从悬浮层显式停止后，服务、MediaProjection 和 `id=1104` 通知均为
  0；崩溃/ANR 扫描匹配为 0。

### 平台结论

Android 15 QPR1+ 锁屏会结束投影；Android 14+ 的新投影会话需要新的用户
授权结果。因此本项目的恢复语义是“锁屏立即归零并明确提示，解锁后由用户
点按通知重新授权”，而不是复用已失效的令牌。HyperOS 的“无限制”省电策略
只用于排查亮屏长会话被厂商回收的情况。

## 2026-07-26 本轮真机结果

### 设备与构建

| 项目 | 实测值 |
|---|---|
| 设备 | Xiaomi 15 Pro，型号 `2410DPN6CC`，设备代号 `haotian` |
| 系统 | Android 16 / API 36 |
| ROM | HyperOS `OS3.0.304.0.WOBCNXM` |
| 安全补丁 | `2026-06-01` |
| 显示 | `1440 × 3200`，`600 dpi`，手势导航 |
| ABI | `arm64-v8a` |
| APK | `app/build/outputs/apk/debug/app-debug.apk`，48,527,996 bytes |
| APK SHA-256 | `58C02EBDBF8C9E6736098FAA2FBC0570EB833D093225CBB345D08807B7BA9624` |
| 构建结果 | `testDebugUnitTest`、`assembleDebug`、`lintDebug` 全部成功 |

### 已通过项目

- 全新安装、覆盖安装和冷启动均成功；最终冷启动实测约 0.6 秒。
- 实际操作了 Android 通知权限弹窗，授权后
  `POST_NOTIFICATIONS` 为 granted。
- 实际操作了 HyperOS 权限路径：
  “识屏翻译 → 其他权限 → 显示悬浮窗 → 始终允许”。
  应用内按钮已验证会直接打开本应用权限编辑页。
- 英语 → 简体中文翻译模型准备成功，界面显示
  “模型已就绪：英语 → 简体中文”。
- Android 系统 MediaProjection 确认页正常出现；授权后
  `dumpsys media_projection` 显示本包的 `TYPE_SCREEN_CAPTURE`。
- 前台服务以 `mediaProjection` 类型运行，常驻通知标题和停止入口正常。
- 在受控本地网页上完成实际闭环：
  屏幕帧 → 框选区域 → Latin OCR → 英译中 → 悬浮窗。
- 动态文本从 `The door is open.` 变为 `The door is closed.` 后，
  悬浮窗无需重新框选即从“门打开”更新为“门关闭”。
- 运行期间切换横屏再恢复竖屏，前台服务和 MediaProjection token
  均保持，恢复后继续产生 OCR/翻译结果。
- 目标网页位于 Edge 前台时服务持续后台运行；一次较长会话持续约
  13 分钟，未出现崩溃、ANR 或 OOM。
- 最终运行采样：瞬时 CPU `0.0%`、PSS 约 `252 MiB`、
  RSS 约 `392 MiB`、电池温度 `29.3°C`。该数值包含 bundled OCR、
  翻译模型、全屏 ImageReader 缓冲和浏览期间已加载的原生库。
- 从悬浮层点击“停止”后，服务、MediaProjection、悬浮窗和活动前台
  通知均消失；通知转储中只保留历史归档记录。
- 冷启动及从其他应用返回后，主界面均显示“未运行”，开始按钮启用，
  停止按钮禁用。

关键截图和日志保存在忽略构建目录：

```text
app/build/device-test/final-live-state-a.png
app/build/device-test/final-live-state-b.png
app/build/device-test/final-landscape.png
app/build/device-test/final-portrait-restored.png
app/build/device-test/final-after-stop.png
app/build/device-test/final-app-logcat-running.txt
app/build/device-test/final-service-running.txt
app/build/device-test/final-media-projection-running.txt
app/build/device-test/final-window-running.txt
```

### 英文原著长句质量样例

使用 Charles Dickens *A Tale of Two Cities* 开篇公开文本作为 119 词、
611 字符的固定长句：

- OCR 单词序列 119/119 全部正确；
- 字符编辑距离为 1，字符准确率约 `99.836%`；
- 唯一差异是 `the other way—in short` 的破折号被识别为空格；
- ML Kit 完整译文共 174 个中文字符，但人工综合质量约 `3/10`；
- 主要错误包括 `incredulity` 词义、`season of Darkness` 反义关系、
  `so far like` 句法和 `superlative degree` 含义；
- 丢失的破折号还导致 `the other way—in short` 合并后严重误译；
- 当前悬浮面板只显示两行原文和三行译文，长文本会出现省略号。

结论：当前长句瓶颈在翻译质量、标点恢复和结果展示，而不是 Latin OCR。
该样例列入 [`ROADMAP.md`](ROADMAP.md) 的 v0.2 回归目标。原文来源：
[Project Gutenberg](https://gutenberg.org/files/98/98-h/98-h.htm)。

本地完整证据保存在忽略构建目录：

```text
app/build/device-test/long-sentence-full-result-utf8.txt
app/build/device-test/long-sentence-translation.png
app/build/device-test/long-sentence-quality-report.md
```

### 真机发现并已修复

1. HyperOS 忽略标准悬浮窗设置 Intent 的包 URI，曾跳到全局应用列表；
   当前优先打开 HyperOS 本应用权限编辑页，并保留标准 Android 回退链。
2. 该 ROM 的 MediaProjection 会把本应用的 secure 悬浮层混入捕获帧；
   当前将悬浮层实际屏幕边界传给采帧管线，在 OCR 前遮蔽该区域，阻止
   原文/译文递归进入下一次识别。
3. 旧稳定门会把高相似度但真实变化的文本视为重复；当前任何持续两帧
   的新文本都可独立通过，且新增了单词变化和单帧 OCR 抖动回归测试。
4. 从悬浮层停止后返回 Activity，旧状态文字曾残留；当前在 `onResume`
   依据服务真实状态刷新。

### 本轮未覆盖

- 中文、日文、韩文三套 OCR 的真机样例；
- 深色模式、三键导航、小窗/分屏；
- 锁屏、系统主动终止投屏、运行中撤销悬浮窗权限；
- DRM/`FLAG_SECURE` 第三方内容；
- 30 分钟持续功耗、20 轮快速启停；
- HyperOS 最近任务卡片手势移除；
- 断网后的模型复用和其他首次语言对下载。

这些项目仍保留在下方完整验收清单中，不因本轮英语闭环通过而自动勾选。

## 1. 记录测试基线

连接手机后，在项目根目录用 PowerShell 保存基础信息：

```powershell
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.incremental
adb shell getprop ro.build.display.id
adb shell getprop ro.mi.os.version.name
adb shell wm size
adb shell wm density
```

验收前提：

- 型号为小米 15 Pro；
- `ro.build.version.sdk` 为 `36`；
- 记录完整 HyperOS 版本和 Android 安全补丁日期；
- USB 调试授权稳定，`adb devices -l` 状态为 `device`。

HyperOS 悬浮窗授权路径应直接进入“识屏翻译”权限编辑页，再选择
“其他权限 → 显示悬浮窗 → 始终允许”；若跳到所有应用总列表，记为 ROM 跳转适配失败。

测试报告中还应记录：深色/浅色模式、刷新率、横竖屏、是否开启省电模式、Wi-Fi/移动网络和设备温度状态。

## 2. 干净构建与安装

```powershell
.\gradlew.bat --no-daemon clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb uninstall com.screentranslation.app 2>$null
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -W -n com.screentranslation.app/.MainActivity
```

检查：

- 单元测试、Lint 和 debug APK 构建全部成功；
- 冷启动无崩溃、ANR 或资源缺失；
- 图标在 HyperOS 桌面圆形/方圆遮罩下均不裁掉核心图形；
- 浅色、深色、三键导航和手势导航下内容不被系统栏遮挡；
- 旋转或窗口尺寸改变时界面状态明确，无重叠控件。

建议同步查看日志：

```powershell
adb logcat -c
adb logcat --pid=$(adb shell pidof -s com.screentranslation.app)
```

PowerShell 若不接受该内联形式，先取 PID：

```powershell
$appPid = (adb shell pidof -s com.screentranslation.app).Trim()
adb logcat --pid=$appPid
```

## 3. 权限链

### 3.1 悬浮窗

先确认未授权状态：

```powershell
adb shell appops set com.screentranslation.app SYSTEM_ALERT_WINDOW deny
adb shell am force-stop com.screentranslation.app
adb shell am start -n com.screentranslation.app/.MainActivity
```

验收步骤：

1. 点击“授予悬浮窗权限”；
2. 应进入本应用对应的 HyperOS 特殊权限页，而不是通用应用列表；
3. 开启“显示在其他应用上层”后返回；
4. 应用重新检查实际权限，开始按钮状态随之更新；
5. 运行中从系统设置撤销权限，应用不崩溃并停止/移除悬浮层。

查看 AppOps：

```powershell
adb shell appops get com.screentranslation.app SYSTEM_ALERT_WINDOW
```

HyperOS 文案和设置层级可能随系统小版本变化。若还有“后台弹出界面”一项，本应用正常的用户主动启动流程不应依赖它。

### 3.2 通知

```powershell
adb shell pm revoke com.screentranslation.app android.permission.POST_NOTIFICATIONS
adb shell am force-stop com.screentranslation.app
adb shell am start -n com.screentranslation.app/.MainActivity
```

检查：

- 首次需要时出现系统通知权限提示；
- 允许后，捕获期间存在不可误解的前台服务通知；
- 通知标题/内容说明正在识别屏幕，而不是伪装成普通消息；
- 停止后通知消失；
- 拒绝通知时应用给出明确状态，且不会因缺少通知权限崩溃。

查看授权：

```powershell
adb shell dumpsys package com.screentranslation.app |
    Select-String "POST_NOTIFICATIONS|granted="
```

### 3.3 MediaProjection

1. 从前台 Activity 点击开始；
2. 必须出现 Android 系统提供的共享/录屏确认页；
3. 取消时保持 Idle，不启动虚假捕获通知；
4. 同意后立即出现 mediaProjection 前台服务通知；
5. 停止后再次点击开始，必须再次取得新的系统授权；
6. 不允许跳过、缓存或静默复用上一次授权结果。

运行中检查：

```powershell
adb shell dumpsys media_projection
adb shell dumpsys activity services com.screentranslation.app |
    Select-String "foregroundServiceType|mediaProjection|isForeground"
```

## 4. 核心功能矩阵

准备四张静态、对比度足够的测试页，每张包含至少两行文字：

| PP-OCRv6 文字体系 | 输入样例类型 | 目标语言 | 预期 |
|---|---|---|---|
| Latin | 英文网页/本地测试页 | 中文 | 稳定识别后显示中文译文 |
| Chinese | 简体与常见标点 | 英文 | 中文识别完整，标点不导致持续抖动 |
| Japanese | 平假名、片假名、汉字混排 | 英文/中文 | 多语言模型保留日文文字并得到对应译文 |
| Korean | 谚文与空格 | 英文/中文 | 保留合理分词并得到对应译文 |

每一行执行：

1. 在应用中选择对应源语言与目标语言；确认 PP-OCRv6 使用同一多语言权重，
   源语言只配置后续翻译；
2. 开始投影，在测试页上框选固定区域；
3. 等待文本稳定，记录首次 OCR 与首次翻译耗时；
4. 保持画面不动 10 秒，译文不应高频闪烁或重复刷新；
5. 改变一行文字，最终结果应更新且旧结果不得反向覆盖；
6. 拖动/缩放选区到屏幕边缘，不得出现越界裁剪崩溃。

失败报告至少包含：原图（允许保存的测试页）、选择区域、OCR 体系、源/目标语言、采样间隔、期望文字、实际文字和 logcat 时间点。

## 5. 首次模型下载与离线

OCR 是随应用打包的；翻译模型按语言下载。按以下顺序区分两者：

1. 清除应用数据并重新安装；
2. 保持联网，开始一种从未使用过的语言对；
3. 界面应显示模型准备/下载状态，不把“模型未就绪”显示成空译文；
4. 下载完成并成功翻译后停止服务；
5. 关闭 Wi-Fi 和移动数据，再启动同一语言对；
6. 应可离线翻译；
7. 离线选择从未下载的另一种目标语言，应用应给出可恢复提示，不崩溃。

可辅助观察流量和网络状态：

```powershell
adb shell dumpsys connectivity
adb shell dumpsys package com.screentranslation.app |
    Select-String "INTERNET|ACCESS_NETWORK_STATE"
```

## 6. 区域、悬浮层与系统 UI

逐项测试：

- 小区域、半屏、接近全屏、四个屏幕边缘；
- 120 Hz 与 60 Hz；
- 浅色/深色模式；
- 手势导航与三键导航；
- 横屏应用、竖屏应用以及旋转过程中；
- 状态栏下拉、控制中心展开、音量面板弹出；
- 输入法弹出/收起；
- 分屏/小窗（若 HyperOS 当前版本允许）；
- 译文层本身不会拦截目标应用的正常点击；
- 区域选择层结束后不残留透明触摸遮罩。

Android 16 edge-to-edge 下，实际捕获坐标、WindowInsets 与物理帧可能有偏移。发现固定偏移时同时记录：

```powershell
adb shell wm size
adb shell wm density
adb shell dumpsys window displays
```

## 7. 安全窗口与中断

### 7.1 系统保护内容

打开明确使用 `FLAG_SECURE` 或 DRM 的合法测试页面：

- 捕获结果可能为黑色/空白；
- 应用不得尝试绕过系统保护；
- OCR 无结果时保持稳定，不无限创建翻译任务；
- UI 应把它视为不可捕获内容，而不是闪退。

### 7.2 会话中断

除“最近任务划掉”外，每项单独执行并确认资源释放：

- 锁屏 30 秒后解锁；
- 从系统隐私指示器/录屏控件停止共享；
- 强制停止应用；
- HyperOS 最近任务划掉应用：前台投屏服务应继续，随后从通知点“停止”并确认资源释放；
- 撤销悬浮窗权限；
- 切换深色模式；
- 旋转屏幕；
- 网络在模型下载中断开并恢复；
- 连续快速点击开始/停止 10 次。

检查没有遗留投影或悬浮窗：

```powershell
adb shell dumpsys media_projection
adb shell dumpsys activity services com.screentranslation.app
adb shell dumpsys window windows |
    Select-String "com.screentranslation.app"
```

## 8. HyperOS 后台与功耗

默认省电策略下运行 15 分钟：

1. 保持目标应用在前台，ScreenTranslation 在后台运行投影服务；
2. 每分钟改变一次测试文字；
3. 记录服务、译文更新、设备温度和耗电；
4. 锁屏再解锁，确认应用不会无提示地保留失效会话。

如服务被 HyperOS 异常提前回收，再把应用详情 → 省电策略设为“无限制”复测，并分别记录两组结果。不要把“自启动”当作投影恢复方案：MediaProjection 会话仍必须由用户在可见 Activity 中重新授权。

辅助命令：

```powershell
adb shell dumpsys deviceidle
adb shell dumpsys activity processes |
    Select-String "com.screentranslation.app"
adb shell dumpsys batterystats com.screentranslation.app
adb shell dumpsys thermalservice
```

## 9. 性能与稳定性门槛

建议 MVP 验收门槛：

- 静态清晰文字：连续 20 次中至少 19 次产生非空 OCR；
- 稳定画面：10 秒内不重复提交相同译文；
- 内容改变：在“采样间隔 + OCR/翻译耗时”的合理范围内更新；
- 连续运行 30 分钟：无崩溃、ANR、内存持续单调增长或无界任务队列；
- 开始/停止 20 轮：无残留前台服务、投影和悬浮窗；
- 设备进入明显热限制时：可以降速，但 UI 与停止操作保持响应。

采集：

```powershell
adb shell dumpsys meminfo com.screentranslation.app
adb shell top -b -n 1 -o PID,CPU,RES,NAME |
    Select-String "screentranslation"
adb shell dumpsys gfxinfo com.screentranslation.app
```

## 10. 最终通过清单

- [ ] API 36 / HyperOS 版本已记录
- [ ] clean build、单元测试、Lint、APK 成功
- [ ] Activity 冷启动、明暗主题、edge-to-edge 正常
- [ ] 悬浮窗拒绝/允许/撤销均正常
- [ ] 通知拒绝/允许均无崩溃
- [ ] 每次投影都经过系统授权
- [ ] FGS 类型确认为 `mediaProjection`
- [ ] PP-OCRv6 的 Latin/Chinese/Japanese/Korean 四类夹具通过
- [ ] 首次模型下载与同语言离线翻译通过
- [ ] 区域边界、旋转、小窗/分屏按目标范围通过
- [ ] `FLAG_SECURE`/DRM 场景不泄露内容且不崩溃
- [ ] 锁屏、撤销、划卡、强停后无资源残留
- [ ] 30 分钟稳定性与功耗结果已记录

测试报告应附完整 Gradle 任务结果、APK SHA-256、设备属性、关键 logcat 和所有未通过项；不要只写“已测试”。
