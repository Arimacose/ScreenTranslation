# TranslationProvider 能力画像与中间质量档门禁

本文是 [Issue #41](https://github.com/Arimacose/ScreenTranslation/issues/41)
的实现契约。它把此前散落在 product flavor、运行时实现和基准报告中的翻译策略
收束为共享的不可变 `TranslationProviderProfile`，但不改变 Lite、Full、Online
三个 edition 的选择方式、包名、模型或请求链路。

## 1. 代码边界

- `TranslationBackend` 是一次运行期实例，负责准备、翻译、取消和关闭；
- `TranslationProviderProfile` 是不依赖 `Context` 的静态能力与政策声明；
- `TranslationBackendFactory.profile` 先根据唯一的 edition BuildConfig 标志选择
  profile，再反射创建该 edition 独有的 backend；
- factory 会校验实例声明的 `profile.id` 与所选 edition 一致，避免 flavor 依赖或
  类名漂移后静默接错后端；
- `TranslationBackend.inputMode` 默认直接来自 `profile.input.mode`，因此
  `CLAUSE_PLAN` / `WHOLE_REGION` 不再由实现中的第二份常量决定。

共享类型位于：

```text
app/src/main/java/com/screentranslation/app/ml/
├── TranslationEngine.kt             # backend 合约与 edition factory
└── TranslationProviderProfile.kt    # profile、能力类型、STQ 门禁、准入策略
```

profile 的类型化维度如下：

| 维度 | 类型 | 表达内容 |
|---|---|---|
| 稳定身份/状态 | `TranslationProviderId` / `TranslationProviderAvailability` | Shipping、Experimental、Benchmark-only 或 Evaluation-blocked |
| 语言 | `TranslationLanguageCapability` / `TranslationRoute` | 显式路由、pivot、多源到固定目标、固定集合任意语言对、远端 provider 自报能力 |
| 输入 | `TranslationInputCapability` | 整段/分句模式、字符上限、context/output token、换行、推理是否需要网络 |
| 模型存储 | `TranslationModelStorageCapability` | app `no_backup`、SDK 管理、远端、未供给；分发与大小档；是否可由应用删除 |
| 取消 | `TranslationCancellationCapability` | per-request 是否可取消，以及 `close()` 是 PREEMPT 还是 DRAIN；两者不再混为“引擎生命周期” |
| 性能 | `TranslationPerformanceCapability` | 按实际路由保存 `Double` 精度的 raw median、进程 HWM 与测量进程范围 |
| 归属 | `TranslationAttributionCapability` | 随 APK 许可包、基准文档，或用户所选远端 provider 的动态归属 |

## 2. 当前 Provider 矩阵

下表性能数字来自既有小米 15 Pro / Android 16 基准，是能力分档依据，不是跨设备
SLA。内存观测口径沿用原报告中的进程 high-water 值。

| Profile | 状态与语言 | 输入 | 模型存储 | 取消 | 延迟/内存类 | Attribution |
|---|---|---|---|---|---|---|
| `BERGAMOT_LITE` | Shipping；`en→zh` 直译；`ja→en→zh` 显式 pivot | `CLAUSE_PLAN`；无应用层字符硬上限；离线推理 | app 私有 `no_backup`；固定 revision/SHA-256 下载；应用可删除；<128 MiB 档 | per-request `NO_PER_REQUEST_CANCEL`；close `PREEMPT_ACTIVE_AND_DISCARD_QUEUED` | Interactive / Medium；英中 `35.547 ms / 456.590 MiB`，日中级联 `64.410 ms / 767.625 MiB` | Bergamot runtime 与 Firefox models，MPL-2.0，许可随包 |
| `HY_MT2_Q4_FULL` | Experimental；非中文源→中文；已测英/日→中 | `CLAUSE_PLAN`；2,048 token context，保留 256 output；离线推理 | app 私有 `no_backup/models/hymt2-q4`；固定 descriptor/revision/SHA-256；`1,133,080,448 B`；应用可删除 | per-request `NO_PER_REQUEST_CANCEL`；close `MARK_CLOSED_DRAIN_EXECUTOR_THEN_RELEASE_RUNTIME`：先标记 closed，不中断 executor；已过关闭检查的活动任务可结束，其余既有调用感知 closed，队列排空后释放 runtime | Visible-delay / Very-large；英中 `753.498 ms`，日中 `653.432 ms`；standalone HWM `2,200.543 MiB` | HY-MT2 Apache-2.0 + pinned llama.cpp MIT，许可随包 |
| `ONLINE_BYOK` | Shipping；语言能力由用户所选 provider/model 决定 | `WHOLE_REGION`；共享常量最多 6,000 字符；推理需要网络 | 远端权重；本地模型为 0 B | per-request `ACTIVE_REQUEST_BEST_EFFORT`；close `PREEMPT_ACTIVE_AND_DISCARD_QUEUED` | Network-dependent / Remote-inference | provider/model 动态归属，用户配置决定 |
| `ML_KIT_BENCHMARK` | Benchmark-only；项目 UI 的 8 种语言集合；已测英/日→中 | `CLAUSE_PLAN`；离线推理（模型准备后） | ML Kit SDK 管理下载；不进入产品模型管理器 | per-request `NO_PER_REQUEST_CANCEL`；close 为 preempt | Interactive / Small；英中 `27.643 ms / 271.629 MiB`，日中 `39.627 ms / 324.109 MiB` | ML Kit 17.0.3 Terms，记录于 benchmark notices |
| `HY_MT2_STQ_CANDIDATE` | **Evaluation-blocked**；结构上为多源→中；仅已测英/日→中 | 计划为 `CLAUSE_PLAN`；2,048/256 token | **未进入应用存储/下载路径**；候选文件 `461,860,800 B` | 无应用 backend；close 明确为 `NOT_IMPLEMENTED` | Visible-delay / Medium；英中 `615.727 ms`，日中 `622.125 ms`；约 `903.320 MiB` 仅为 standalone runner HWM | HY-MT2 1.25-bit model Apache-2.0，仅基准记录 |

### 为什么 Lite 的日语路由必须包含 pivot

`TranslationRoute("ja", "zh", listOf("en"))` 将现有的 `ja→en→zh` 级联作为
能力的一部分暴露。调用方和文档据此可以区分“界面上支持日中”与“模型直接日中”，
避免把级联的延迟、内存和语义损失误报为直接翻译能力。

### 为什么 Online 不硬编码语言列表

Online BYOK 在配置阶段从远端读取模型 ID，但 OpenAI-compatible `/models` 并没有
统一的语言能力 schema。profile 因而使用 `ProviderConfigured`：应用只保证非空且
不同的语言对能形成请求，最终覆盖范围和 attribution 由用户所选 provider/model
决定；这不是对任意模型多语能力的背书。

## 3. HY-MT2 STQ 的 fail-closed 门禁

截至 **2026-08-09** 的实时 GitHub/仓库核对：

| 项目 | 当前值 | 是否满足 |
|---|---|---|
| llama.cpp STQ 支持 | PR [`#22836`](https://github.com/ggml-org/llama.cpp/pull/22836) 状态显式为 `OPEN`；观测 head `7e74b8296fbb2e48ad2fbe4663410279bbd2a5e7` | 否 |
| 应用 declared/pinned runtime | gitlink `caa596ab3f0f8768ee326d6e3d5d39782194676c`，为当前 Q4 runtime | 未证明包含 STQ merge |
| 模型 revision | `9df5c824a00a744fb0512a29c640466f4d97dfb0` | 是 |
| 官方 source GGUF SHA-256 | `cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93` | 是 |
| retag runnable GGUF SHA-256 | `e482a38ceaaf8420573483c96ddc8449922b5f5de6a8023b70316e65d41e6de7` | 是；仅历史 PoC，不是生产资产 |
| 转换记录 | `retag-legacy-stq-gguf-v1`；manifest SHA-256 `b4713f...f284`；只改 file/tensor type，payload hash 前后相同 | 已记录 |
| 机器可读证据 | `docs/evidence/hymt2-stq-evidence-2026-07-30.json`；evidence ID `hymt2-stq-2026-07-30-xiaomi15pro-android16`；文件 SHA-256 `3ebf56...490cd` | 已 pin，但明确不是 Release 准入证据 |

`TranslationEvaluationGate` 不再把“长度为 40 的十六进制字符串”当成可信支持。
它把 PR 状态、merge commit、declared runtime、仓库实际 gitlink、merge ancestry、
可运行模型 hash、验证状态和 CI/版本化 evidence reference 分开。只有同时满足以下
条件才会 `isSatisfied`：PR 状态为 `MERGED`；merge/runtime 均为完整 commit；验证器
确认 `gitlink == declared runtime` 且 merge 是 runtime ancestor；验证器核对 runnable
GGUF hash；source/runnable/manifest/evidence 文件均已 pin。当前：

- `isSatisfied == false`；
- 缺失条件报告为 `UPSTREAM_PULL_REQUEST_MERGED`、
  `UPSTREAM_MERGE_COMMIT_RECORDED`、`PINNED_RUNTIME_CONTAINS_MERGE_VERIFIED`；
- profile 为 `EVALUATION_BLOCKED`、`isSelectable == false`；
- 它不在 `editionProfiles`，factory 没有 STQ class 映射，也没有模型下载入口。

后续只有在上游格式支持合并、仓库把包含该 merge 的 commit 固定为 submodule、CI
以 ancestry check 验证实际 gitlink、可运行 GGUF/哈希重新核对之后，才能生成绑定
上述四项的 `TranslationRuntimeSupportEvidence` 并重新开始候选验收。

## 4. 每日中间档准入阈值（先于实现发布）

`MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER` 是发布前必须全部命中的统一规则：

| 维度 | 准入阈值 | 原因 |
|---|---:|---|
| 路由覆盖 | `en→zh`、`ja→zh` 各恰好一条 measurement；缺失、重复和额外路由均失败 | 禁止调用方只上报一个聚合最差值或重复较优路由 |
| 质量保持 | 每条必测路由 raw BLEU 均达到 Q4 的 **≥95%** | 防止跨语言聚合掩盖退化 |
| 关键语义 | 每条必测路由相对 Shipping Lite 的关键检查回归数 **=0** | BLEU 不足以覆盖否定、数值、术语与惯用语 |
| raw 延迟 | 每条必测路由 raw median **<350 ms** | 保留模型本身的推理预算 |
| app pipeline | 每条路由 median **<750 ms**、P95 **<1,500 ms**、timeout **=0**；任一值 `null/NOT_MEASURED` 即失败 | 防止 raw microbenchmark 代替分句/保护/协调器后的真实路径 |
| 集成 Release 内存 | 整个 Release 应用进程 PSS **<1.0 GiB**、HWM **<1.2 GiB** | standalone server RSS/HWM 不再冒充应用进程证据 |
| LMK | 30 分钟窗口 LMK event **=0** | 验证后台持续识别的进程生存性 |
| 热稳定 | 持续热运行 **≥30 分钟**，至少 30 个 Thermal Status 样本，最大值 **≤1** | 只有起止两点或普通操作窗口均不足以形成最大值证据 |
| 依赖 | 格式支持已合并，且应用 runtime/model revision/SHA-256 均已 pin | 禁止依赖漂移中的 PR head 进入产品 |

质量夹具、来源/许可和人工 adequacy/fluency 规范由
[#46](https://github.com/Arimacose/ScreenTranslation/issues/46) 的公开回归套件提供；
本策略先固定门槛，避免看完候选结果后再移动判定线。

### 当前 STQ 证据映射

| 门槛 | 当前证据 | 结论 |
|---|---:|---|
| Q4 BLEU 保持 ≥95% | 英中 88.37%，日中 89.68% | 两路均未命中 |
| Lite 关键检查零回归 | 英中 0；日中比 Bergamot 少 8 项 | 日中未命中 |
| raw median <350 ms | 英 `615.727 ms`，日 `622.125 ms` | 两路均未命中 |
| app pipeline median/P95/timeout | `ClauseSplitter` + gold source（非 OCR E2E）：英 `600.994 / 1,432.453 / null`；日 `577.303 / 1,416.108 / null` ms | timeout 未测，整项失败 |
| 集成 Release PSS/HWM/LMK | `null / null / null`；`925,000 KiB` 是 standalone runner HWM | 未测 |
| 30 分钟持续热运行 | 约 14 分钟为操作窗口，不是 hot run | 未测 |
| Thermal 最大值 | 只有起止 `0/0`，无连续样本 | 未测，不填 `0` |
| merged + pinned runtime | PR Open，应用仍 pin Q4 commit | 未命中 |

因此 STQ 的资源收益是真实的，但它目前不是每日中间档；profile 只让其能力、证据和
阻塞条件可被代码与测试审计。

## 5. 行为与 flavor 隔离保持

| Edition | backend class | 运行路径是否改变 |
|---|---|---|
| Lite | `BergamotTranslationEngine` | 否；仍使用 pinned ARM64 runner、同一路由与 app-private model store |
| Full | `HyMt2Q4TranslationEngine` | 否；仍为 Q4_K_M + pinned llama.cpp，仍标记 Experimental |
| Online | `OnlineLlmTranslationEngine` | 否；仍为 BYOK、6,000 字符限制、整段请求与可取消 HTTP call |
| Benchmark | `TranslationEngine` | 否；ML Kit 仍只存在于 benchmark source set/dependency |

各实现现在必须覆盖 `profile`；主源码仍不直接引用 sibling flavor class，反射 factory
仍是隔离边界。构建一个 edition 不会把其他 edition 的 runtime、权重或 HTTP client
带入 APK。

## 6. 新 Provider 的最小审查清单

1. 新增稳定 `TranslationProviderId`，明确 Availability；
2. 声明直接路由和 pivot，不能只写界面显示的语言对；
3. 声明 input mode、上限、context 以及网络条件；
4. 声明权重位置、分发校验、大小级别和删除语义；
5. 分别声明 `TranslationCall.cancel()` 与 `close()` 的 PREEMPT/DRAIN 行为；
6. 使用同一公开夹具产生延迟/内存/质量证据；
7. 写明 model/runtime revision、license、source URL 与打包 notices；
8. 若为中间档候选，先满足 `DAILY_MIDDLE_TIER`，再进入 edition factory；
9. 为对应 flavor 增加编译与 profile 契约测试，确认 sibling 依赖没有泄漏。

## 7. 自动验证

共享测试覆盖：

- 三个 edition profile 完整且 ID 唯一；
- Lite direct/pivot 路由；
- Full 中文目标和 Q4 context/model size；
- Online 6,000 字符、整段输入、网络推理与活动请求取消；
- 每个 flavor 的 BuildConfig 只选择对应 profile；
- OPEN/CLOSED-unmerged、无 merge、gitlink 不符、merge 非 ancestor、runnable hash 不符均 fail-closed；
- 任意带未满足 gate 的 Shipping/Experimental profile 也不可选；
- 当前 STQ 逐路由证据确实未过质量/raw 延迟，pipeline timeout 与集成 Release/热证据按缺测失败；
- 缺路由、重复路由、额外路由及仅两个 Thermal 端点均失败；
- 只有真实 `MERGED` 状态、绑定 exact gitlink/ancestor/runnable hash 的 evidence 与完整两路/Release 数据位于全部边界内时才通过；
- Lite 路由、Online 6,000 字符、Q4 context/output/model path/hash/delete 和三种 close 行为由共享常量及 flavor tests 防漂移；
- 所有静态 provider 均有可审计 attribution。

```powershell
.\gradlew.bat --console=plain `
  testLiteDebugUnitTest `
  testFullDebugUnitTest `
  testOnlineDebugUnitTest
```
