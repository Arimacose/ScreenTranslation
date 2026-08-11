# PP-OCRv6 持续识别性能基准（2026-08-11）

本报告记录 Issue #39 的可重复 A/B 预验收。目标不是用一次峰值推理代替日常负载，
而是在同一台小米 15 Pro、同一动态屏幕夹具和同一 5 分钟采集窗口内，比较全屏增量
识别在“每次签名扫描都构造整屏 Bitmap”和“先从 `Image.Plane` 计算签名、仅脏帧构造
Bitmap”两条实现路径的资源表现。最终 `v2.1.0` 签名 Release 仍按
[`DEVICE_TEST.md`](DEVICE_TEST.md) 对区域模式和全屏模式分别执行 15 分钟门禁；最终报告
摘要与哈希保存在 Issues #38/#39 的不可编辑验收评论和 Release notes 中。

## 1. 固定边界

| 项目 | 值 |
|---|---|
| 设备 | Xiaomi 15 Pro，`2410DPN6CC` / `haotian` |
| 系统 | Android 16 / API 36；HyperOS `OS3.0.304.0.WOBCNXM` |
| 安全补丁 | `2026-06-01` |
| OCR | PP-OCRv6-small / ONNX Runtime Android 1.28.0 |
| 翻译 | Lite Bergamot，英语→简体中文 |
| 识别模式 | 全屏增量覆盖，基础间隔 500 ms，自适应上限 2,000 ms |
| 动态夹具 | 独立 Online Benchmark 包；每 30 秒切换大块、小块与 `BATON/BATMAN` 字幕 |
| 采样 | 300 秒、每 15 秒一次，共 21 点；USB 供电状态如实保留 |
| 采集器 | `tools/device-endurance/collect.py`，脚本 SHA-256 `8792553781f808078baf40bb492b6a56a9a14f034b37c59d4bfcab5844d9b6fa` |

两个 APK 都是隔离的 `com.screentranslation.app.benchmark`，不替换正式 Lite 包。
夹具 APK 在两轮中保持逐字节一致，SHA-256 为
`ee5ed0b5b04f3d93920d37280e452fa92bf9e8b6e913d7dfb8a7ecff0e7044e2`。

## 2. A/B 实现

### A：Bitmap-first 基线

- source：`8b747c5`；
- APK SHA-256：`08bc172371580c90180fb167a210c9a5967b705b3687314bc59b0cd8041596f2`；
- 每个获准签名扫描先把整帧 `Image` 转成 Bitmap，再从 Bitmap 取样计算 tile 签名；
- 即使画面最终没有脏块，也已产生整屏分配与像素复制。

### B：Plane-first 候选

- source：`cba4ccc`；
- APK SHA-256：`65724560fa1356ed4055514c4230a498d1265ab68a0fbaa44e8268c09c0ac04a`；
- 直接按 RGBA plane 的 `rowStride` / `pixelStride` 批量复制取样行，生成 1/4 尺寸亮度
  缩略签名；悬浮层排除矩形在签名阶段预栅格化并写成白色；
- 只有出现自然脏块或计划强制复核块时才构造 Bitmap；静态帧直接关闭 `Image`；
- 同一提交还让译文标签避开状态栏、导航安全区、控制条和已经放置的标签；找不到
  合法空位时跳过该标签，而不是叠字。

初版逐像素 `DirectByteBuffer.get(index)` 候选的签名中位数约 201 ms，明显劣于 A，已在
正式 B 之前淘汰。最终 B 改为逐取样行批量复制，避免把失败原型混入发布结论。

## 3. 5 分钟结果

| 指标 | A | B | B 相对 A |
|---|---:|---:|---:|
| 单核等效 CPU | 21.187% | 18.007% | **-15.01%** |
| PSS 中位数 | 223,221 KiB | 213,519 KiB | **-4.35%** |
| PSS 峰值 | 306,803 KiB | 268,384 KiB | **-12.52%** |
| RSS 中位数 | 321,668 KiB | 271,868 KiB | **-15.48%** |
| 位图构造次数 | 134 | 22 | **-83.58%** |
| 位图构造字节 | 2,469,888,000 | 405,504,000 | **-83.58%** |
| 直接跳过位图次数 | 0 | 117 | +117 |
| 避免的位图字节 | 0 | 2,156,544,000 | **约 2.01 GiB** |
| OCR 调用 | 40 | 44 | +10.00% |
| OCR 输入像素 | 11,194,560 | 12,314,016 | +10.00% |
| 处理错误 / OCR 失败 | 0 / 0 | 0 / 0 | 无回归 |
| 崩溃 / ANR / OOM | 0 / 0 / 0 | 0 / 0 / 0 | 无回归 |
| Thermal status 最大值 | 0 | 0 | 相同 |
| 电池温度首末变化 | +0.8 °C | -0.1 °C | USB 供电条件下仅作观测 |

B 的 OCR 次数增加来自采集窗口内识别到 11 次自然变化，而 A 为 10 次；按变化事件看，
两者都约为每次 4 个 tile OCR，并不表示静态画面重复 OCR。B 的 139 次签名扫描中有
117 次在构造 Bitmap 之前退出，位图跳过率为 84.17%。

延迟方面，B 的 plane 签名中位数/P95 为 `47.971/59.594 ms`，高于 A 的
`34.985/48.878 ms`；代价换来大量整屏 Bitmap 分配消失，因此 5 分钟进程 CPU、PSS 与
RSS 仍整体下降。B 的 warm OCR 中位数为 `412.912 ms`，A 为 `404.900 ms`；两者均使用
相同 PP-OCRv6 模型，差异属于设备运行噪声，发布判断以固定语料输出和完整 15 分钟门禁
共同决定，而不是声称单次 OCR 推理更快。

## 4. 识别质量与 UI 正确性

同一轮固定 10 张 PP-OCRv6 夹具、每张 3 次推理的结果为：

- 10 个 case 中 9 个逐字完全一致；唯一差异仍是已知的 Unicode em dash→ASCII hyphen；
- corpus CER `0.0617%`，WER `0.2809%`；
- 30 次 OCR 中位数 `763.409 ms`、P95 `4,149.001 ms`；
- score JSON SHA-256：
  `4d4b875fd209da7fd336cd3e68c6379ea3ac9197725cc64f71640b3912b650a7`。

全屏动态夹具末态显示 `BATMAN` 原文时，只保留对应的新译文“巴特曼：脏图核实”，
没有残留 `BATON` 译文；三条应用译文标签互不重叠，顶部控制条仍可停止。屏幕顶部另有
HyperOS/其他应用窗口产生的彩色叠字，在停止 ScreenTranslation 服务并确认
MediaProjection 已结束后仍存在，因而不计入本应用递归反馈。

## 5. 稳定性门禁

A、B 的 21 个采样点均满足：

- 单一 PID，服务、MediaProjection 和 `ScreenTranslationCapture` 虚拟显示 21/21 连续；
- Thermal status 全程 0；
- PSS 首末增长不超过 64 MiB；
- 目标进程日志中没有崩溃、ANR、native fatal signal 或 OOM；
- telemetry session ID 全程一致，处理错误和 OCR 失败为 0。

B 预验收 `summary.json` SHA-256 为
`0c685f4bf5894976b0d4cb6e873e960a77c7b112cdee8b2f3bbc45561077d68`；A 对照为
`8024d3f34caa7709d3260cc6abcad390829c84e2304bb527ecf03f2a48a05c87`。
当前采集器默认不保存截图、OCR 原文或译文；本地验收目录保存采样 JSON，并将 Logcat
过滤为目标 PID 或明确包含目标包名的行，同时保存
文件 manifest，最终签名验收只在公开 Issue 评论中登记摘要哈希，避免把用户屏幕内容
提交到仓库。

## 6. 决定与回滚

采用 B：虽然签名扫描本身略慢，但它把 83.58% 的整屏 Bitmap 构造移出静态路径，并在
真实持续负载下降低 CPU 与内存；固定 OCR 输出没有变化。区域模式继续作为默认模式和
即时回滚路径，全屏增量覆盖在 v2.1.0 中仍标为 Experimental。若最终签名 Release 的
区域/全屏 15 分钟门禁、生命周期矩阵或屏上覆盖正确性任一失败，则不创建 v2.1.0 tag，
并在同一分支修复后重新生成新的 immutable acceptance Artifact。
