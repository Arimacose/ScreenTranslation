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
| 取消 | `TranslationCancellationCapability` | 仅引擎生命周期，或活动请求 best-effort 取消 |
| 性能 | `TranslationPerformanceCapability` | 延迟/内存等级及同一基线设备上的观测区间 |
| 归属 | `TranslationAttributionCapability` | 随 APK 许可包、基准文档，或用户所选远端 provider 的动态归属 |

## 2. 当前 Provider 矩阵

下表性能数字来自既有小米 15 Pro / Android 16 基准，是能力分档依据，不是跨设备
SLA。内存观测口径沿用原报告中的进程 high-water 值。

| Profile | 状态与语言 | 输入 | 模型存储 | 取消 | 延迟/内存类 | Attribution |
|---|---|---|---|---|---|---|
| `BERGAMOT_LITE` | Shipping；`en→zh` 直译；`ja→en→zh` 显式 pivot | `CLAUSE_PLAN`；无应用层字符硬上限；离线推理 | app 私有 `no_backup`；固定 revision/SHA-256 下载；应用可删除；<128 MiB 档 | 单请求句柄为空，随引擎生命周期停止 | Interactive / Medium；raw median `35–64 ms`；HWM `457–768 MiB` | Bergamot runtime 与 Firefox models，MPL-2.0，许可随包 |
| `HY_MT2_Q4_FULL` | Experimental；非中文源→中文；已测英/日→中 | `CLAUSE_PLAN`；2,048 token context，保留 256 output；离线推理 | app 私有 `no_backup`；固定 revision/SHA-256；`1,133,080,448 B`；应用可删除 | 单请求句柄为空，随引擎生命周期停止 | Visible-delay / Very-large；raw median `653–753 ms`；HWM 约 `2,201 MiB` | HY-MT2 Apache-2.0 + pinned llama.cpp MIT，许可随包 |
| `ONLINE_BYOK` | Shipping；语言能力由用户所选 provider/model 决定 | `WHOLE_REGION`；最多 6,000 字符；推理需要网络 | 远端权重；本地模型为 0 B | 活动 HTTP/重试链 best-effort 取消 | Network-dependent / Remote-inference | provider/model 动态归属，用户配置决定 |
| `ML_KIT_BENCHMARK` | Benchmark-only；项目 UI 的 8 种语言集合；已测英/日→中 | `CLAUSE_PLAN`；离线推理（模型准备后） | ML Kit SDK 管理下载；不进入产品模型管理器 | 单请求句柄为空，随引擎生命周期停止 | Interactive / Small；raw median `28–40 ms`；HWM `272–324 MiB` | ML Kit 17.0.3 Terms，记录于 benchmark notices |
| `HY_MT2_STQ_CANDIDATE` | **Evaluation-blocked**；结构上为多源→中；仅已测英/日→中 | 计划为 `CLAUSE_PLAN`；2,048/256 token | **未进入应用存储/下载路径**；候选文件 `461,860,800 B` | 候选值，不构成生产承诺 | Visible-delay / Medium；raw median `616–622 ms`；HWM 约 `904 MiB` | HY-MT2 1.25-bit model Apache-2.0，仅基准记录 |

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
| llama.cpp STQ 支持 | PR [`#22836`](https://github.com/ggml-org/llama.cpp/pull/22836) 仍为 Open、未合并；观测 head `7e74b8296fbb2e48ad2fbe4663410279bbd2a5e7` | 否 |
| 应用 pinned runtime | gitlink `caa596ab3f0f8768ee326d6e3d5d39782194676c`，为当前 Q4 runtime | 不包含 STQ 已合并支持 |
| 模型 revision | `9df5c824a00a744fb0512a29c640466f4d97dfb0` | 是 |
| 模型 SHA-256 | `cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93` | 是 |

`TranslationEvaluationGate` 特意把 PR head、上游 merge commit 和应用 runtime pin
分成三个字段。PR head 只是证据，永远不会自动等价于“已合并且已固定”。当前
`upstreamSupportMergeCommit` 与 `pinnedRuntimeCommit` 均为 `null`，所以：

- `isSatisfied == false`；
- 缺失条件固定报告为 `UPSTREAM_SUPPORT_MERGED`、
  `SUPPORTED_RUNTIME_PINNED`；
- profile 为 `EVALUATION_BLOCKED`、`isSelectable == false`；
- 它不在 `editionProfiles`，factory 没有 STQ class 映射，也没有模型下载入口。

后续只有在上游格式支持合并、仓库把包含该支持的 40 位 commit 固定为 submodule、
官方 GGUF/哈希重新核对之后，才能填写两个空 gate 字段并重新开始候选验收。

## 4. 每日中间档准入阈值（先于实现发布）

`MiddleTierAdmissionPolicy.DAILY_MIDDLE_TIER` 是发布前必须全部命中的统一规则：

| 维度 | 准入阈值 | 原因 |
|---|---:|---|
| 质量保持 | 所有必测语言对的 raw BLEU 均达到 Q4 的 **≥95%** | 防止只靠某一语言平均值掩盖退化 |
| 关键语义 | 相对当前 Shipping Lite 的关键检查回归数 **=0** | BLEU 不足以覆盖否定、数值、术语与惯用语 |
| 延迟 | 各必测语言对最差 raw median **<350 ms** | 给 OCR、稳定门、分句、悬浮层留出交互预算 |
| 进程内存 | high-water **<1.2 GiB**（`1,288,490,189 B` 为排他上界） | 降低 LMK 与后台持续识别风险 |
| 热稳定 | 连续 **≥30 分钟**，最高 Android Thermal Status **≤1** | 短时冷启动数据不足以代表持续识屏 |
| 依赖 | 格式支持已合并，且应用 runtime/model revision/SHA-256 均已 pin | 禁止依赖漂移中的 PR head 进入产品 |

质量夹具、来源/许可和人工 adequacy/fluency 规范由
[#46](https://github.com/Arimacose/ScreenTranslation/issues/46) 的公开回归套件提供；
本策略先固定门槛，避免看完候选结果后再移动判定线。

### 当前 STQ 证据映射

| 门槛 | 当前证据 | 结论 |
|---|---:|---|
| Q4 BLEU 保持 ≥95% | 英中 88.37%，日中 89.68% | 未命中 |
| Lite 关键检查零回归 | 日中比 Bergamot 少 8 项 | 未命中 |
| median <350 ms | 英 `615.727 ms`，日 `622.125 ms` | 未命中 |
| HWM <1.2 GiB | `925,000 KiB` | 命中 |
| 30 分钟 / Thermal ≤1 | 约 14 分钟窗口，状态 0 | 时长未命中 |
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
5. 让 `TranslationCall.cancel()` 的真实行为与 cancellation capability 一致；
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
- STQ 不可选、未进入 factory，且只缺 merge/runtime pin 两个条件；
- 当前 STQ 证据确实未过质量/延迟/热时长门槛而通过内存门槛；
- 在模拟“已合并且 pin”后，恰好位于所有准入边界内的候选可以通过；
- 所有静态 provider 均有可审计 attribution。

```powershell
.\gradlew.bat --console=plain `
  testLiteDebugUnitTest `
  testFullDebugUnitTest `
  testOnlineDebugUnitTest
```
