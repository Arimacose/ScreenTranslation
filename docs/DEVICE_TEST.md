# 小米 15 Pro / HyperOS / Android 16 真机验收

本文是目标 ROM 的可重复验收清单。不要用模拟器结果替代 MediaProjection、HyperOS 悬浮窗、后台策略和温控测试。

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

| OCR 文字体系 | 输入样例类型 | 目标语言 | 预期 |
|---|---|---|---|
| Latin | 英文网页/本地测试页 | 中文 | 稳定识别后显示中文译文 |
| Chinese | 简体与常见标点 | 英文 | 中文识别完整，标点不导致持续抖动 |
| Japanese | 平假名、片假名、汉字混排 | 英文/中文 | 使用日文识别器并得到对应译文 |
| Korean | 谚文与空格 | 英文/中文 | 保留合理分词并得到对应译文 |

每一行执行：

1. 在应用中选择对应源语言与目标语言；确认源语言自动路由到预期 OCR 体系；
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
- [ ] Latin/Chinese/Japanese/Korean 四套 OCR 通过
- [ ] 首次模型下载与同语言离线翻译通过
- [ ] 区域边界、旋转、小窗/分屏按目标范围通过
- [ ] `FLAG_SECURE`/DRM 场景不泄露内容且不崩溃
- [ ] 锁屏、撤销、划卡、强停后无资源残留
- [ ] 30 分钟稳定性与功耗结果已记录

测试报告应附完整 Gradle 任务结果、APK SHA-256、设备属性、关键 logcat 和所有未通过项；不要只写“已测试”。
