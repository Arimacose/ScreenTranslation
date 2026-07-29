# Bergamot Android proof of concept — 2026-07-29

## 结论

**Bergamot 的 Android ARM64 核心部署可行，但本轮结果不足以替换生产默认的
ML Kit Translate。**

本次已经完成真实 ARM64 二进制交叉编译、模型校验、ADB 下发、Android 16
真机推理、与 ML Kit 的同夹具评分，以及 2,000 次 raw/pipeline 组持续压力测试。Bergamot
在若干关键语义上优于 ML Kit，离线资产也完全由项目控制；代价是显著更高的
常驻内存、较大的自带资产和更慢的整句文学长句。生产主线继续使用 ML Kit，
Bergamot 保留为受特性开关控制的实验候选。

当前证明范围是“原生运行时核心”；APK 内 JNI 封装、模型交付、进程生命周期和
签名 Release/R8 长时验收仍是后续生产化门槛。

## 测试对象

| 项目 | 实测值 |
|---|---|
| 设备 | Xiaomi 15 Pro / `2410DPN6CC` |
| 系统 | Android 16 / API 36 |
| HyperOS | `OS3.0.304.0.WOBCNXM` |
| Android build | `BP2A.250605.031.A3` |
| ABI | `arm64-v8a` |
| Bergamot | `v0.4.5+9271618` |
| Bergamot commit | `9271618ebbdc5d21ac4dc4df9e72beb7ce644774` |
| NDK | r23b / `23.1.7779620` |
| 引擎配置 | CPU、Ruy、NEON、单 worker、beam 4 |
| 模型 | Firefox Translations `en` -> `zh` `base-memory` `int8Alpha` |
| ML Kit | Translate `17.0.3` |
| 测试语料 | 同一组 10 例英文输入，包括 611 字符 Dickens 长句 |
| 重复次数 | 质量轮每例 3 次；压力轮每例每路径 100 次 |

OCR 在本报告中使用 gold source 直通，避免把识别误差归因于翻译器。两种引擎
都对同一英文原文测试两条路径：

- **raw**：整段直接交给翻译器；
- **pipeline**：先通过应用现有 `ClauseSplitter`，再按顺序拼接译文。

BLEU 和 chrF++ 只有单条中文参考译文，只作为方向性指标；关键字段检查和人工
语义审阅仍是决策门槛。

## Android 可执行产物

| 产物 | 字节数 | SHA-256 |
|---|---:|---|
| stripped ARM64 benchmark | 8,438,552 | `67bf75d5889f47b3a1965eb6e5fa5b4fbd7045b60ad8d5992c80c5e3e52a5cc5` |
| 四个模型运行资产 | 49,913,927 | 见结果 JSON 内逐文件哈希 |
| 设备端 PoC 目录 | 57,071 KiB | `adb shell du -sk` |
| ML Kit `en_zh` 模型目录 | 44,217 KiB | `run-as ... du -ak` |

Bergamot 二进制与模型合计 58,352,479 B，即 55.65 MiB；设备文件系统统计为
55.73 MiB。ML Kit 的本应用英中模型目录为 43.18 MiB。上述数值不包含未来
JNI 封装、许可证文本或包格式开销。

模型包的核心文件：

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| `model.enzh.intgemm.alphas.bin` | 43,849,787 | `4e5accc141373565ddc8fa1565bceaa8d0c3482a82cab8131c719ebcc6c2157c` |
| `srcvocab.enzh.spm` | 806,952 | `bd9b65504acc6d9726dd281f7defc2adb7c2c22d0688fe2f84697de25197c8c5` |
| `trgvocab.enzh.spm` | 772,004 | `aded6993c36e440284d11cec3f6b8aef9c0e43188a772d80be342a713adf223d` |
| `lex.50.50.enzh.s2t.bin` | 4,485,184 | `8575d8daa10e2dbff316dcdf8e1ce475357bcc2c92bdc63b736a2d5add22f681` |

## 翻译质量与延迟

### 汇总

| 路径 | 引擎 | BLEU | chrF++ | 关键检查 | 中位延迟 | P95 |
|---|---|---:|---:|---:|---:|---:|
| raw | ML Kit | **36.382** | 28.186 | 7/8 | **49.348 ms** | **263.155 ms** |
| raw | Bergamot | 34.822 | **28.346** | **8/8** | 60.785 ms | 351.064 ms |
| pipeline | ML Kit | **36.709** | 27.843 | **8/8** | **47.074 ms** | 438.318 ms |
| pipeline | Bergamot | 34.135 | **28.210** | 7/8 | 51.838 ms | **296.434 ms** |

raw 路径中，ML Kit 的 BLEU 高 1.560，Bergamot 的 chrF++ 高 0.160，
且 Bergamot 通过全部 8 个关键语义检查。Bergamot 中位延迟比 ML Kit 高
23.2%。三次短轮的长句延迟存在明显批次波动，因此性能决策采用下方 100 轮
分布，而不根据此表的 P95 单独下结论。

分句后，Bergamot 的一个服务存活关键检查在分句改写后失配，说明现有
`ClauseSplitter` 不能机械地视为质量提升。

### 代表性人工审阅

| 输入 | ML Kit | Bergamot | 判断 |
|---|---|---|---|
| Austen: `must be in want of a wife` | “必须忍受妻子” | “一定是想要一个妻子” | Bergamot 保住核心语义 |
| notification recovery | 把 `resumed translating` 译为“恢复平均” | “恢复了所选区域的翻译” | Bergamot 明显更准确 |
| dynamic beta | 保留为“样本β”，`remains alive` 为“保持活力” | 变成“测试样本”，`remains alive` 为“保持保留” | ML Kit 更准确 |
| Dickens contrast | 把 Darkness 错成“春天”，多处不通顺 | 保留光明/黑暗、希望/绝望对照，但 raw 把 `worst` 译成“最美好”，并把 `incredulity` 译成“无能” | 两者都未达到文学长句门槛 |

Bergamot 并非单向优于 ML Kit：10 例中技术句、低对比度说明、版本金额日期和
Dickens 整句的自动指标多由 ML Kit 胜出；Austen、通知恢复、动态 alpha/beta
中的部分表达及数字样例则由 Bergamot 胜出。现有 10 例足以否定“直接替换”，
但不足以对通用质量做统计显著性结论。

## 内存、稳定性与温度

### 三次质量轮

| 项目 | 实测值 |
|---|---:|
| 模型加载 | 88.839 ms |
| warm-up | 306.596 ms |
| warm-up 后 RSS | 367,384 KiB |
| 完成后 RSS | 372,876 KiB |
| HWM | 464,104 KiB |

100 轮测试中，ML Kit benchmark 应用的 PSS 从引擎前 70,830 KiB 增至
warm-up 后 152,313 KiB，完成后为 148,922 KiB；RSS 从 181,356 KiB 增至
268,268 KiB，完成后为 275,288 KiB，HWM 为 290,396 KiB。Bergamot 是独立
原生进程且本轮只记录 RSS/HWM，因此两者不是完全相同的内存口径；但
Bergamot 结束 RSS 为 376,368 KiB，比 ML Kit 完整 benchmark 进程仍高约
98.7 MiB，足以构成与 PP-OCRv6 同时常驻时的主要部署风险。

### 100 轮持续测试

两种引擎都把 20 组 raw/pipeline 输入各重复 100 次，即每个引擎各 2,000 次
组执行。ML Kit 耗时约 114.8 秒，Bergamot 原生 runner 耗时
169,127.744 ms。两条路径都逐字检查全部重复结果，未发现译文变化。

| 引擎/路径 | 样本数 | 中位延迟 | P95 | 前 10 轮中位 | 后 10 轮中位 | 前后变化 |
|---|---:|---:|---:|---:|---:|---:|
| ML Kit raw | 1,000 | **48.227 ms** | **108.322 ms** | 47.716 ms | 48.443 ms | +1.52% |
| Bergamot raw | 1,000 | 64.043 ms | 351.322 ms | 62.787 ms | 65.196 ms | +3.84% |
| ML Kit pipeline | 1,000 | **48.163 ms** | **187.312 ms** | 48.141 ms | 47.298 ms | -1.75% |
| Bergamot pipeline | 1,000 | 54.187 ms | 297.716 ms | 53.895 ms | 54.297 ms | +0.75% |

611 字符 Dickens 单例的 100 轮中位延迟：

| 路径 | ML Kit | Bergamot | Bergamot / ML Kit |
|---|---:|---:|---:|
| raw | 107.674 ms | 351.331 ms | 3.26x |
| pipeline | 187.107 ms | 297.720 ms | 1.59x |

进程 RSS 从 warm-up 后 370,884 KiB 增至结束时 376,368 KiB，HWM 始终为
467,684 KiB，没有持续抬升。系统 Thermal Status 前后均为 0；电池温度由
28.8°C 升至 29.7°C，当前 CPU 传感器约由 32–33°C 升至最高 44.8°C。设备处于
充电和受控桌面环境，该数据只能说明本次 169 秒高负载未触发系统热状态，
不能替代 15 分钟以上、亮屏 MediaProjection + PP-OCRv6 联合验收。

ML Kit 压力轮的系统 Thermal Status 同样前后为 0，`dumpsys battery` 温度由
31.7°C 变为 31.9°C。两轮的起始温度、执行顺序和环境并非热实验室控制变量，
因此这里只判定“本轮未触发系统热限制”，不据此比较两引擎能耗。

## `BlockingService` 与异步路径

保留结果使用一个长生命周期 `BlockingService`、一个 worker 和串行请求。
其 3 次质量轮与 100 次压力轮均逐字稳定。

诊断用 `AsyncService` 在同机并发提交长句分片时，Dickens pipeline 的第三次
结果与前两次不同；raw 单请求保持一致。候选架构因此固定为应用自有后台线程
上的串行阻塞服务。在厘清异步调度/共享状态前，不将并发 batch 用于生产译文。

## 部署可行性判定

| 维度 | 判定 | 依据 |
|---|---|---|
| Android ARM64 编译/链接 | 通过 | 固定提交和 NDK 生成的 stripped binary 在 Android 16 真机加载运行 |
| 模型格式 | 通过 | Firefox `int8Alpha` 模型、双 SPM 与 lexical shortlist 均由 Bergamot 直接加载 |
| 离线与可控交付 | 通过 | 全部运行资产本地校验，无云端翻译依赖 |
| 确定性 | 串行模式通过 | 2,000 次组执行逐字一致；异步并发存在已记录风险 |
| 短期稳定性 | 通过 | 169 秒满负载无崩溃、无 HWM 增长、Thermal Status 0 |
| 内存预算 | 未通过生产门槛 | Bergamot 稳态 RSS 约 362–368 MiB，需与 PP-OCRv6 联合测量和优化 |
| 质量 | 混合 | 自动总分接近，关键语义互有胜负，文学长句双方均有明显错误 |
| APK 集成 | 待实现 | 当前是 adb 下的原生核心；尚未加入 JNI、资产交付和生命周期管理 |

## 工程决策

1. 生产默认翻译器继续使用 ML Kit Translate `17.0.3`。
2. Bergamot PoC 保留在 `tools/bergamot-android-poc`，模型和结果不进入 Git。
3. 下一阶段先实现最小 JNI 适配器并接入计划中的 `TranslationProvider`
   抽象，用 runtime flag 切换并保留 ML Kit 默认实现。
4. Bergamot 只允许单 worker 串行调用；用 latest-text 队列、稳定文本 gate 和
   取消旧请求控制持续全屏模式的负载。
5. 优先验证模型卸载/重载、进程隔离、内存压力和 PP-OCRv6 联合运行。
6. 质量集扩展到至少 100 条 UI、字幕、文学、数字、专有名词和否定句，并增加
   多参考译文或人工盲评。
7. 切换默认引擎前必须通过签名 Release/R8、15 分钟持续捕获、温度、电量、
   后台/锁屏恢复和 HyperOS 生命周期验收。

## 可复现入口

- 构建和真机运行：
  [`tools/bergamot-android-poc/README.md`](../tools/bergamot-android-poc/README.md)
- 统一模型 benchmark：
  [`tools/model-benchmark/README.md`](../tools/model-benchmark/README.md)
- 上一轮候选筛选：
  [`MODEL_BENCHMARK_2026-07-28.md`](MODEL_BENCHMARK_2026-07-28.md)

生成结果位于忽略的目录：

```text
app/build/model-benchmark/bergamot-android-poc-2026-07-29/
├── translation-mlkit-android.json
├── translation-mlkit-android.scores.json
├── translation-mlkit-android-stress-100.json
├── translation-mlkit-android-stress-100.scores.json
├── translation-bergamot-android.json
├── translation-bergamot-android.scores.json
└── translation-bergamot-android-stress-100.json
```

本轮主要结果哈希：

| 结果 | SHA-256 |
|---|---|
| ML Kit 3-repeat JSON | `bec574d137207c12d4f4bd592140de6bf597ba69c0a16ead2bbb845764532e35` |
| Bergamot 3-repeat JSON | `0db5f4860e7a0a750c6b3f9a8130fd35c29b6f028df280bf181aa6bc360509ae` |
| ML Kit 100-repeat JSON | `c3c50265291af1dc7523dbd65e29cdbf6fd42653221f1d0ddfa839c818c11c11` |
| Bergamot 100-repeat JSON | `f4730ed6268b3cad75270285ea7005860ea872b1745cff2b4960f2a7b5fc1874` |

## 上游依据

- [Bergamot Translator](https://github.com/browsermt/bergamot-translator)
- [Bergamot pinned Android ARM64 workflow](https://github.com/browsermt/bergamot-translator/blob/9271618ebbdc5d21ac4dc4df9e72beb7ce644774/.github/workflows/arm.yml)
- [Firefox Translations model dashboard](https://mozilla.github.io/translations/firefox-models/)
- [Firefox Translations models repository](https://github.com/mozilla/firefox-translations-models)
- [Android NDK older releases](https://github.com/android/ndk/wiki/Unsupported-Downloads)
- [ML Kit on-device translation](https://developers.google.com/ml-kit/language/translation/android)
