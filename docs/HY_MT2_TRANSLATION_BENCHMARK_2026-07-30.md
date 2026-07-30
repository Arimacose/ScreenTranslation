# Hy-MT2 Q4 / 1.25-bit / ML Kit / Bergamot 综合翻译基准 — 2026-07-30

> 本文保留 Hy-MT2 量化、真机资源与格式兼容的完整证据。

## 结论

本轮在同一台小米 15 Pro、同一组项目自建英→中与日→中用例上，完成了
Hy-MT2 1.8B Q4_K_M 和 STQ1_0 1.25-bit 的 Android 16 真机推理，并与
已经验收的 ML Kit Translate `17.0.3` 和 Bergamot `v0.4.5` 结果对齐。

工程结论如下：

1. **Q4_K_M 仍是本轮质量上限。** 英→中 raw BLEU 为 `51.885`，日→中
   raw BLEU 为 `45.543`；分别比轻量方案的次高结果高 `12.916` 和
   `12.385`。日语直接输入中文提示词，没有日→英→中的显式级联。
2. **1.25-bit 仍保留明显的平均文本相似度优势，但优势不再全面。** 英→中
   raw BLEU 为 `45.850`，比 ML Kit / Bergamot 高 `7.388 / 6.881`；
   日→中为 `40.842`，高 `7.684 / 8.474`。但日中自动关键检查只有
   `35/52`，低于 Bergamot 的 `43/52`。
3. **1.25-bit 未达到预设的 Q4 质量保持门槛。** 英中、日中 raw BLEU
   分别只保留 Q4 的 `88.37%` 和 `89.68%`，低于 `95%` 目标；英中
   pipeline 保留 `93.35%`，日中仍为 `89.68%`。
4. **低比特显著降低部署资源，但没有达到持续识屏延迟目标。** 相对 Q4，
   模型文件减少 `59.24%`，实测 HWM 减少 `58.95%`；raw 中位延迟仍为
   `616–622 ms`，高于预设的 `350 ms`。
5. **Q4_K_M 不适合当前全屏持续翻译主链路。** 单句中位延迟为
   `653–753 ms`，约为 ML Kit 的 `16.5–27.3` 倍；模型文件为
   `1,133,080,448 B`，真机进程 HWM 为 `2,253,356 KiB`，模型加载约
   `5.73 s`。
6. **生产默认继续使用 ML Kit。** 它在当前设备上具有最低延迟、最低内存和
   最成熟的 Android 生命周期；代价是官方说明非英语语言对会经过英语中间层。
7. **Bergamot 继续保留为开放模型的中间方案。** 英中模型可部署且延迟接近
   ML Kit；日中仍依赖 `ja→en→zh` 级联，模型与内存成本明显上升。
8. **Hy-MT2 继续作为实验后端，不进入 v0.1.x 默认后端。** Q4 适合作为
   静止画面质量上限；1.25-bit 适合作为低存储实验档，但需要上游格式稳定、
   日中语义问题复核和 30 分钟热测试后再评估产品集成。

## 测试边界

| 项目 | 值 |
|---|---|
| 设备 | 小米 15 Pro，`2410DPN6CC`，SoC `SM8750` |
| 系统 | Android 16 / API 36 / HyperOS `OS3.0.304.0.WOBCNXM` |
| 测试集 | `en-zh-diverse-v2` 40 条；`ja-zh-diverse-v1` 40 条 |
| 重复 | 每条 raw 与 app `ClauseSplitter` pipeline 各 3 次 |
| OCR | gold source 直通；本轮只比较翻译，不代表 OCR + 翻译端到端性能 |
| Hy-MT2 Q4 | `tencent/Hy-MT2-1.8B-GGUF` Q4_K_M |
| Q4 revision / SHA-256 | `1cd5208700acedef4ef93019b6cfc148b8522d45` / `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699` |
| Q4 llama.cpp | tag `b10181`，commit `caa596ab3` |
| Hy-MT2 1.25-bit | `tencent/Hy-MT2-1.8B-1.25Bit-GGUF` STQ1_0 |
| 1.25-bit revision / 官方 SHA-256 | `9df5c824a00a744fb0512a29c640466f4d97dfb0` / `cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93` |
| STQ llama.cpp | PR `#22836` head `7e74b8296fbb2e48ad2fbe4663410279bbd2a5e7` |
| 真机配置 | ARM64 CPU-only，8 threads，2K context，单 slot |
| 解码 | temperature `0`、top-k `1`、top-p `1`、repeat penalty `1.05`、seed `42` |

测试集覆盖 UI、系统通知、数字与符号、否定逻辑、对话、惯用语、文学长句、
技术说明和高风险领域文本。详细用例设计见
[`TRANSLATION_BENCHMARK_EN_JA_ZH_2026-07-29.md`](TRANSLATION_BENCHMARK_EN_JA_ZH_2026-07-29.md)。

## 总体质量与延迟

BLEU 和 chrF++ 使用每条用例的全部项目自写参考译文。关键检查是较严格的
字面量/正则规则。三者共同用于发现方向性差异，不等同于通用翻译排行榜。

### Raw：整句直接翻译

| 语言对 / 引擎 | BLEU | chrF++ | 自动关键检查 | 中位延迟 | P95 |
|---|---:|---:|---:|---:|---:|
| 英→中 ML Kit | 38.462 | 27.869 | 32/54 | **27.643 ms** | 100.613 ms |
| 英→中 Bergamot | 38.969 | 28.733 | 35/54 | 35.547 ms | **82.022 ms** |
| 英→中 Hy-MT2 1.25-bit | 45.850 | 34.320 | **36/54** | 615.727 ms | 1,593.869 ms |
| 英→中 Hy-MT2 Q4 | **51.885** | **36.089** | **36/54** | 753.498 ms | 2,326.589 ms |
| 日→中 ML Kit | 33.158 | 23.316 | 34/52 | **39.627 ms** | **92.627 ms** |
| 日→英→中 Bergamot | 32.368 | 23.386 | **43/52** | 64.410 ms | 105.689 ms |
| 日→中 Hy-MT2 1.25-bit | 40.842 | **32.238** | 35/52 | 622.125 ms | 1,281.437 ms |
| 日→中 Hy-MT2 Q4 | **45.543** | **31.615** | 39/52 | 653.432 ms | 1,089.693 ms |

### App `ClauseSplitter` pipeline

| 语言对 / 引擎 | BLEU | chrF++ | 自动关键检查 | 中位延迟 | P95 |
|---|---:|---:|---:|---:|---:|
| 英→中 ML Kit | 38.342 | 27.704 | 32/54 | **25.760 ms** | 100.435 ms |
| 英→中 Bergamot | 39.132 | 29.038 | 36/54 | 34.576 ms | **77.700 ms** |
| 英→中 Hy-MT2 1.25-bit | 47.804 | 34.930 | **36/54** | 600.994 ms | 1,432.453 ms |
| 英→中 Hy-MT2 Q4 | **51.208** | **36.019** | **36/54** | 712.646 ms | 4,474.001 ms |
| 日→中 ML Kit | 33.158 | 23.316 | 34/52 | **41.694 ms** | **93.582 ms** |
| 日→英→中 Bergamot | 32.368 | 23.386 | **43/52** | 63.073 ms | 107.370 ms |
| 日→中 Hy-MT2 1.25-bit | 40.842 | **32.238** | 35/52 | 577.303 ms | 1,416.108 ms |
| 日→中 Hy-MT2 Q4 | **45.543** | **31.615** | 39/52 | 580.440 ms | 925.838 ms |

当前分句器对长英文会发起多次推理。Hy-MT2 英中 pipeline 的 P95 因此上升到
`4.47 s`，说明“把现有轻量翻译器直接替换成 1.8B 生成模型”并不成立；未来应
改为屏幕级批量输入、结构化行 ID 输出和增量缓存，而不是逐分句重复提示。
1.25-bit 的英中 pipeline P95 降至 `1.43 s`，但相对轻量引擎仍高一个数量级。

## 质量增益

### 英→中

相对 Bergamot，Hy-MT2 raw：

- BLEU `+12.916`；
- chrF++ `+7.356`；
- 自动关键检查 `+1`；
- 40 条逐句 BLEU 胜出 26 条，Bergamot 8 条，ML Kit 5 条，1 条并列。

长句、否定逻辑和技术文本总体提升最明显。狄更斯 611 字符长句能保持主要排比、
对立关系和完整句意；`only if`、`unless` 与多条件系统提示也大多保持方向。

### 日→中

相对 ML Kit，Hy-MT2 raw BLEU `+12.385`、chrF++ `+8.299`；相对 Bergamot
级联则为 `+13.175`、`+8.229`。40 条逐句 BLEU 中 Hy-MT2 胜出 27 条，
ML Kit 8 条，Bergamot 3 条，2 条并列。

直接日→中避免了 Bergamot 第二阶段引入的新错误，并显著改善多数对话、
否定逻辑和普通系统文本。但本轮仍发现：

- `石の上にも三年` 与 `雨降って地固まる` 趋向字面翻译；
- `田中さんが資料を送ってくれました` 丢失受益对象“给我”；
- 个别语境把 `先生` 保留为“先生”，而不是“老师”；
- 日期、时间、温度和货币常被本地化改写，界面原样保真不足。

### 1.25-bit 是否仍有明显质量优势

相对当前两个轻量后端，答案是**平均 BLEU/chrF++ 仍有明显优势，但关键语义
正确性并非全面领先**：

| 计划 / 语言对 | 相对 ML Kit BLEU | 相对 Bergamot BLEU | 相对 Q4 BLEU | Q4 保持率 |
|---|---:|---:|---:|---:|
| raw 英→中 | +7.388 | +6.881 | -6.035 | 88.37% |
| pipeline 英→中 | +9.462 | +8.672 | -3.404 | 93.35% |
| raw 日→中 | +7.684 | +8.474 | -4.701 | 89.68% |
| pipeline 日→中 | +7.684 | +8.474 | -4.701 | 89.68% |

英中 1.25-bit 的关键检查仍为 `36/54`，与 Q4 相同，并高于 ML Kit 和 raw
Bergamot。日中则只有 `35/52`：比 ML Kit 多 1 项，但比 Q4 少 4 项、比
Bergamot 级联少 8 项。典型问题包括把 `猫の手も借りたい`、`石の上にも三年`
和 `口が滑る` 译成字面中文，以及个别系统术语保留为 `Bluetooth` 或
“机内模式”。因此，仅凭 corpus BLEU 把它判为日中全面更优会掩盖产品风险。

相对 Q4，1.25-bit 的英中表达仍普遍通顺，日中普通 UI/数字/否定句也大多可用；
主要损失集中在文学固定译法、日语惯用语、敬语受益关系和关键术语保持。预设的
“两种语言 raw BLEU 均保留 Q4 95%”门槛两项均未命中。

## 自动检查与人工审阅的差异

Hy-MT2 的中文表达更自由，现有正则对它产生了较多保守计分。例如：

- `这次` 未命中只覆盖“本次/仅限”的一次性权限规则；
- `节能模式` 未命中只覆盖“省电/节电”的规则；
- `并非所有`、`并不罕见`、`仍然可以正常使用` 均保持原意，但未命中固定词形；
- 日语结果中的 `再次授权`、`9时30分`、`持续上传` 同样属于词形覆盖不足。

因此报告保留可复现的自动分数，不把人工同义词判断回填成更高分。同时也保留
真正的产品问题：标识符被翻译、`09:45` 变为 `9:45`、`GiB` 变为“吉字节”、
负号字符变化、日语谚语字面化和受益对象遗漏。下一轮应把“语义正确”和
“屏幕字符串原样保真”拆成两个独立评分维度。

## 延迟、内存、启动与热状态

| 路径 | 模型资产 | 准备/加载 | 稳态 RSS | HWM |
|---|---:|---:|---:|---:|
| ML Kit 英→中 | 约 43.18 MiB | 24.596 ms | 268,840 KiB | 278,148 KiB |
| ML Kit 日→中 | 约 43.18 MiB | 39.797 ms | 319,712 KiB | 331,888 KiB |
| Bergamot 英→中 | 47.60 MiB | 115.094 ms | 371,032 KiB | 467,548 KiB |
| Bergamot 日→英→中 | 99.83 MiB | 246.577 ms | 693,168 KiB | 786,048 KiB |
| Hy-MT2 1.25-bit | **440.46 MiB** | **449.003 ms** | 924,860 KiB | **925,000 KiB** |
| Hy-MT2 Q4 | **1,080.59 MiB** | **5,734.492 ms** | 2,250,792 KiB | **2,253,356 KiB** |

内存口径并非完全相同：ML Kit 来自 app `dumpsys meminfo`，Bergamot 和
Hy-MT2 来自独立原生进程。STQ 与 Q4 还使用不同 llama.cpp commit，因此加载
时间只用于当前设备的工程量级判断。1.25-bit 相对 Q4 的模型文件减少
`59.24%`，HWM 减少 `58.95%`，是本轮最明确的低比特收益。

连续执行英中与日中两套 Hy-MT2 真机测试约 `410.5 s`，观测到：

- 电池 `78% → 75%`；
- 电池温度 `28.0°C → 38.0°C`；
- Android Thermal Status 全程为 `0`；
- server HWM 约 `2.149 GiB`；
- 输出速度主要约为 `26–29 token/s`。

这是一次短时观测，没有空载对照，不直接推导续航百分比；它足以说明持续全屏
模式还需要长时功耗、后台调度和 LMK 压力测试。

1.25-bit 从首次成功加载、接口核对到两套正式基准后的约 14 分钟窗口内：

- 电池 `76% → 74%`；
- 电池温度 `30.9°C → 37.5°C`；
- Android Thermal Status 起止均为 `0`；
- 两套正式基准合计约 `354.1 s`；
- 结束时 server RSS / HWM 为 `924,860 / 925,000 KiB`。

这证明短时热状态正常并满足 `HWM < 1.2 GiB` 门槛；预设的 30 分钟持续运行
门槛本轮尚未执行。

## 输出稳定性

Q4 在 temperature `0`、top-k `1`、固定 seed 下：

- 英中 raw 有 3/40 条出现字面差异，pipeline 为 0/40；
- 日中 raw 有 2/40 条出现字面差异，pipeline 为 0/40。

差异多数是空格或近义词，但 `大致位置` 和预订动作存在轻微语义变化。因此生产
缓存键应基于输入和模型配置，并接受同一输入出现不同字面的事实；不要把
temperature `0` 视为逐字节稳定保证。

1.25-bit 的结果更稳定：

- 英中 raw 有 1/40 条出现“朝着天堂前进/朝着天堂的方向前进”的近义差异，
  pipeline 为 0/40；
- 日中 raw 与 pipeline 均为 0/40。

## 低比特量化门槛

### 2-bit：本轮实际加载未通过

已下载并校验官方文件：

| 项目 | 值 |
|---|---|
| repo | `tencent/Hy-MT2-1.8B-2Bit-GGUF` |
| revision | `b630487d19ab7f336664a15b07c638d0d1071471` |
| 大小 | `600,534,880 B`（572.72 MiB） |
| SHA-256 | `dcc33bbae9b28d923c8c76a64f6157840841d26f8774f3dfd770d5fabeeb1cd7` |

官方 `llama.cpp b10181` 启动时报告：

```text
tensor 'blk.0.attn_k_norm.weight' has offset 203248672, expected 203572256
```

官方模型卡说明该格式依赖尚处于 Draft 的 llama.cpp PR `#19357`。
PR 使用自定义 `Q2_0C` 布局，当前重点是 SME2；设备 `/proc/cpuinfo` 仅报告
`asimd/asimddp/i8mm/bf16` 等特性，没有 `sme/sme2`。因此 2-bit 当前既有
上游维护门槛，也没有针对本机 SoC 的已验收执行路径，本轮不生成质量排名。

### 1.25-bit STQ：真机质量已测，保留为实验档

| 项目 | 值 |
|---|---|
| repo / revision | `tencent/Hy-MT2-1.8B-1.25Bit-GGUF` / `9df5c824a00a744fb0512a29c640466f4d97dfb0` |
| 官方文件 | `461,860,800 B`（440.46 MiB） |
| 官方 SHA-256 | `cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93` |
| llama.cpp | PR `#22836` head `7e74b8296fbb2e48ad2fbe4663410279bbd2a5e7` |
| Android runtime | 9,748,144 B；SHA-256 `de58bceb861b9b13281d1611fbde5975939e2129a3ecc8a382b60bdb24f2c93c` |
| ABI / kernel | Android ARM64 CPU-only；NEON dot-product STQ1_0 |

PR 当前重排后的枚举与官方 GGUF 制作时所用的旧枚举发生冲突：

- 官方文件：`general.file_type=41`、224 个 STQ 张量 `type=42`；
- 当前 PR：`Q2_0 file/type=41/42`，`STQ1_0 file/type=42/43`；
- 因而官方文件原样加载会把 STQ 张量当成 Q2_0，并报告
  `blk.0.attn_k_norm.weight` offset 不匹配。

项目新增
[`retag_legacy_stq_gguf.py`](../tools/model-benchmark/retag_legacy_stq_gguf.py)
生成独立兼容副本：只把一个 file type `41→42` 和 224 个 tensor type
`42→43`，不改原文件或张量数据。验证结果：

- 兼容副本大小仍为 `461,860,800 B`；
- 兼容副本 SHA-256 为
  `e482a38ceaaf8420573483c96ddc8449922b5f5de6a8023b70316e65d41e6de7`；
- 张量数据区起点 `5,090,208`，转换前后 payload SHA-256 均为
  `5ab383ce54adddcbcfbb400aacb5b457005c43f71304291889a61074f5686b2d`；
- PR 自带 `gguf_dump.py` 能完整解析 354 个张量，真机 server 在
  `449.003 ms` 后报告 model loaded。

这只是旧模型与当前 PR 的可审计兼容处理，不是重新量化。后续若官方仓库刷新
GGUF，应优先采用官方新文件并删除该兼容步骤。

#### 验收门槛

| 门槛 | 实测 | 结果 |
|---|---:|---|
| 英中 raw BLEU ≥ Q4 的 95% | 88.37% | 未命中 |
| 日中 raw BLEU ≥ Q4 的 95% | 89.68% | 未命中 |
| raw 中位延迟 < 350 ms | 英 615.727 / 日 622.125 ms | 未命中 |
| HWM < 1.2 GiB | 925,000 KiB | 通过 |
| 30 分钟 Thermal Status ≤ 1 | 短测起止均为 0 | 待长测 |
| runtime commit / ABI / 哈希固定 | 已固定 | 通过 |

因此它在存储和内存上具备很强的移动端价值，也在 BLEU/chrF++ 上继续领先
ML Kit/Bergamot；但 Q4 质量保持、日中关键语义和延迟三项尚未达到当前产品
门槛。上游 PR 合并、官方 GGUF 刷新或 kernel 继续优化后再复测更合适。

## 综合决策矩阵

| 维度 | ML Kit | Bergamot | Hy-MT2 1.25-bit | Hy-MT2 Q4 |
|---|---|---|---|---|
| 本轮英/日→中质量 | 中等 | 中等；日中检查较完整 | BLEU 明显较高；日中检查偏弱 | **最高** |
| 单句延迟 | **最低** | 较低 | 高 | 高 |
| 模型/内存 | **最低** | 英中可控；日中级联较高 | 440 MiB / HWM 约 0.88 GiB | 1.06 GiB / HWM 约 2.15 GiB |
| 大多数语言直译中文目标 | 非英语对官方说明经英语 | 受模型注册表限制 | **架构匹配；本轮实测英/日** | **架构匹配；本轮实测英/日** |
| Android 集成成熟度 | **生产已集成** | ARM64 PoC 已通过 | standalone PoC；依赖开放 PR | standalone PoC 已通过 |
| 开放模型与可审计性 | 模型未开放 | **开放且体积较小** | Apache-2.0；kernel/格式尚在变化 | Apache-2.0；上游 Q4 runtime |
| 当前适用模式 | 持续识屏默认 | 开放模型实验/特定语言对 | 低存储实验档 | 静止画面质量上限 |

### 当前优先级

1. 持续翻译主链继续围绕 ML Kit 做最新帧队列、稳定文本门、区域去重和缓存。
2. 将 Hy-MT2 抽象成非默认 `TranslationEngine` 实验接口，但暂不把 Q4 或
   1.25-bit 模型放入 APK、默认下载或生产持续识屏路径。
3. 1.25-bit 下一轮先等待官方 GGUF 与 PR 枚举稳定，并针对日语惯用语、敬语、
   术语保真做人工复核；如果继续作为产品候选，再执行 30 分钟热/LMK 测试。
4. 扩展韩、德、法、俄、阿拉伯、泰、越南语→中文测试，再判断“大多数语言
   直接译中”的真实覆盖，避免从英日两组外推全部语言。

## 可复现命令

宿主机：

```powershell
python .\tools\model-benchmark\run_hymt2.py `
  BASELINE\translation-mlkit-en-zh-android.json `
  --server-executable D:\DevTools\llama.cpp\b10181\cpu-x64\llama-server.exe `
  --model D:\DevCache\HuggingFace\manual\tencent\Hy-MT2-1.8B-GGUF\1cd5208700acedef4ef93019b6cfc148b8522d45\Hy-MT2-1.8B-Q4_K_M.gguf `
  --model-revision 1cd5208700acedef4ef93019b6cfc148b8522d45 `
  --expected-model-sha256 dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699 `
  --quantization Q4_K_M `
  --repetitions 3 `
  --log-directory .\app\build\model-benchmark\hymt2\en-host-logs `
  --output .\app\build\model-benchmark\hymt2\en-host.json
```

真机 server 启动后，通过 ADB loopback 转发运行同一 harness：

```powershell
adb forward tcp:18086 tcp:18086

python .\tools\model-benchmark\run_hymt2.py `
  BASELINE\translation-mlkit-en-zh-android.json `
  --external-server-url http://127.0.0.1:18086 `
  --model D:\DevCache\HuggingFace\manual\tencent\Hy-MT2-1.8B-GGUF\1cd5208700acedef4ef93019b6cfc148b8522d45\Hy-MT2-1.8B-Q4_K_M.gguf `
  --threads 8 `
  --context-size 2048 `
  --repetitions 3 `
  --log-directory .\app\build\model-benchmark\hymt2\en-device-logs `
  --output .\app\build\model-benchmark\hymt2\en-device.json

python .\tools\model-benchmark\score.py `
  .\app\build\model-benchmark\hymt2\en-device.json `
  --output .\app\build\model-benchmark\hymt2\en-device.scores.json
```

1.25-bit 兼容副本与同集真机测试：

```powershell
python .\tools\model-benchmark\retag_legacy_stq_gguf.py `
  --input D:\DevCache\HuggingFace\manual\tencent\Hy-MT2-1.8B-1.25Bit-GGUF\9df5c824a00a744fb0512a29c640466f4d97dfb0\Hy-MT2-1.8B-1.25Bit.gguf `
  --output D:\DevCache\HuggingFace\manual\tencent\Hy-MT2-1.8B-1.25Bit-GGUF\9df5c824a00a744fb0512a29c640466f4d97dfb0\Hy-MT2-1.8B-1.25Bit-STQ1_0-type43.gguf `
  --manifest .\app\build\model-benchmark\hymt2-stq\retag-manifest.json

adb push D:\DevTools\llama.cpp\pr-22836-7e74b829\android-arm64-stq-runtime\llama-server `
  /data/local/tmp/hymt2-stq/runtime/llama-server
adb push D:\DevCache\HuggingFace\manual\tencent\Hy-MT2-1.8B-1.25Bit-GGUF\9df5c824a00a744fb0512a29c640466f4d97dfb0\Hy-MT2-1.8B-1.25Bit-STQ1_0-type43.gguf `
  /data/local/tmp/hymt2-stq/model/model.gguf

adb shell
cd /data/local/tmp/hymt2-stq/runtime
chmod 755 llama-server
setsid -d ./llama-server -m ../model/model.gguf --host 127.0.0.1 --port 18087 \
  --threads 8 --threads-batch 8 --ctx-size 2048 --parallel 1 --fit off \
  --gpu-layers 0 --jinja --no-ui --metrics \
  </dev/null >../server.stdout.log 2>../server.stderr.log &
exit

adb forward tcp:18087 tcp:18087

python .\tools\model-benchmark\run_hymt2.py `
  BASELINE\translation-mlkit-en-zh-android.json `
  --external-server-url http://127.0.0.1:18087 `
  --model D:\DevCache\HuggingFace\manual\tencent\Hy-MT2-1.8B-1.25Bit-GGUF\9df5c824a00a744fb0512a29c640466f4d97dfb0\Hy-MT2-1.8B-1.25Bit-STQ1_0-type43.gguf `
  --model-repo tencent/Hy-MT2-1.8B-1.25Bit-GGUF `
  --model-revision 9df5c824a00a744fb0512a29c640466f4d97dfb0 `
  --expected-model-sha256 e482a38ceaaf8420573483c96ddc8449922b5f5de6a8023b70316e65d41e6de7 `
  --quantization STQ1_0-1.25bit-compat43 `
  --llama-tag PR-22836 `
  --llama-commit 7e74b8296fbb2e48ad2fbe4663410279bbd2a5e7 `
  --threads 8 --context-size 2048 --repetitions 3 `
  --log-directory .\app\build\model-benchmark\hymt2-stq\en-zh-logs `
  --output .\app\build\model-benchmark\hymt2-stq\en-zh.json
```

## 产物与哈希

原始结果保存在 Git 忽略目录
`app/build/model-benchmark/hymt2-2026-07-29/` 和
`app/build/model-benchmark/hymt2-stq-2026-07-30/`。

| 产物 | SHA-256 |
|---|---|
| Hy-MT2 英中真机 JSON | `cc32f3f0424b65b54cae87c800b01c31495ad424a8add61078a40c92afd11a95` |
| Hy-MT2 英中 Scores | `a218a1464f1c21f0cca216e7fd74294268a185f34128e42efb13eb4531e3b786` |
| Hy-MT2 日中真机 JSON | `3a0a80d85d74ca17e437c0b6ba6d16d9500badb884dc8df25155c5039d883861` |
| Hy-MT2 日中 Scores | `278d957712a1af44c106bd2b941fa34ec7b2feea2ab1e9d400672b4b447e738e` |
| 三模型 Android 汇总 | `fcf7237ac77006e73fa0ff0acdb4891df4308df76beb8f5613e51b298bc2c6d4` |
| Android llama-server stderr | `3feae89117dda16f5f8311ad4f226954b3e9be913c4eb3e2d490385189455236` |
| 2-bit 加载错误日志 | `3db27e320103cd8c986d96e8377ccb2065dfe9744cf1adcfbd12a9705d68e1fe` |
| 1.25-bit retag manifest | `b4713fdb0e95c74446688597d7c0bab6cc217379379115dc535c2e89f599f284` |
| 1.25-bit 英中真机 JSON | `43d8d80ba17153ce9926fc5246533bdc50829ade33d4a7b55fd6b771921d1e1a` |
| 1.25-bit 英中 Scores | `aff52d86a6daca3fece4b35ca9dd2dbbb85b97c814669c68fd606d5fcef94ec3` |
| 1.25-bit 日中真机 JSON | `2123f90db5e616ffaa54724262cfeec90307ebb5dcacd339972583ecb6675c8a` |
| 1.25-bit 日中 Scores | `efd40fe23582913b09fb6bae66b32b55df385efd90a695b32af54546874ce4c3` |
| 四模型 Android 汇总 | `529c68c08f62906b99f2edb04ae965675826ee7a1519aa7c76911b32a88f365a` |
| 1.25-bit Android server stderr | `653fd16c15b44b961a14eb3ac1b3ab8a9eb3ec33a03863223d5587195c6a8a14` |
| 官方 1.25-bit GGUF 原样加载错误日志 | `ef60398e6258c5d624a7dc048b417d93870956194603bc1ed81d7c39acad7ae7` |
| STQ runtime version | `f295cf608f34871f770ebe31719d991bf96bdb20da5e08c29e948958a8fad6c6` |
| STQ 设备/runtime 属性 | `0adbc66af8ed5e5d621e6b61fb57fea4cd332bae7b4d316e888269b474c752b1` |

## 上游依据

- [Tencent Hy-MT2](https://github.com/Tencent-Hunyuan/Hy-MT2)
- [Hy-MT2 1.8B Q4 GGUF](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF)
- [Hy-MT2 1.8B 2-bit GGUF](https://huggingface.co/tencent/Hy-MT2-1.8B-2Bit-GGUF)
- [Hy-MT2 1.8B 1.25-bit GGUF](https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF)
- [llama.cpp INT2 PR #19357](https://github.com/ggml-org/llama.cpp/pull/19357)
- [llama.cpp STQ1_0 PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836)
- [llama.cpp Android 文档](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
- [ML Kit on-device translation](https://developers.google.com/ml-kit/language/translation)
- [Bergamot Translator](https://github.com/browsermt/bergamot-translator)
- [Firefox Translations model registry](https://mozilla.github.io/translations/firefox-models/)
