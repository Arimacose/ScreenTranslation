# 多元英/日→中翻译基准 — 2026-07-29

## 结论

本轮把原有 10 条英中样例扩展为两套各 40 条的设备端测试，并加入日语→中文。
综合质量、延迟和内存后，工程决策如下：

1. **生产默认翻译器继续使用 ML Kit Translate `17.0.3`。**
2. Firefox Translations/Bergamot 的 Android ARM64 部署路径成立；英→中单模型质量
   略优于 ML Kit，但优势不足以抵消 JNI、模型交付和内存集成成本。
3. Mozilla 当前生产模型注册表没有直接日→中模型，本轮只能使用
   `ja→en→zh` 两阶段级联。它在关键语义检查上优于 ML Kit，但 BLEU 略低、
   中位延迟高约 62%、模型资产约 99.83 MiB、进程峰值 RSS 约 767.63 MiB，
   **不适合作为当前移动端默认日中路径**。
4. 两个日中引擎都不擅长惯用语；高风险文本也各有真实错误。任何引擎都不应仅凭
   总 BLEU 直接切换。

## 测试对象

| 项目 | 值 |
|---|---|
| 设备 | 小米 15 Pro，`2410DPN6CC` |
| Android | 16 / API 36 |
| HyperOS | `OS3.0.304.0.WOBCNXM` |
| Display build | `BP2A.250605.031.A3` |
| ML Kit | Translate `17.0.3`，英→中与日→中均为直接语言对 |
| Bergamot | `v0.4.5+9271618`，commit `9271618ebbdc5d21ac4dc4df9e72beb7ce644774` |
| 原生配置 | Android ARM64、Ruy、NEON、`int8Alpha`、beam 4、单 worker `BlockingService` |
| 重复次数 | 每条 raw 与 pipeline 各 3 次 |
| 最终 Benchmark APK | 107,483,147 B，SHA-256 `17da38b892f9c3766bebd8895559ed5e5c954ed03849f47138f7341b16b7214f` |

测试使用 gold source 绕过 OCR，隔离翻译质量和翻译延迟。它不是整屏 OCR +
翻译的端到端结论。

## 模型路线

### 英→中

- ML Kit：直接 `en→zh`。
- Bergamot：Firefox Translations `en→zh base-memory`，43,849,787 参数文件；
  模型、双词表和 shortlist 共 49,913,927 B（47.60 MiB）。

### 日→中

- ML Kit：直接 `ja→zh`。
- Bergamot：`ja→en base-memory` 后接同一 `en→zh base-memory`。
- 日英阶段模型参数为 43,568,966，运行资产 54,769,181 B（52.23 MiB）。
- 两阶段运行资产合计 104,683,108 B（99.83 MiB），不含 8,444,920 B
  原生 runner。

级联比较有意保留这种不对称：ML Kit 是直接语言对，Bergamot 是两次解码。
这是当前 Mozilla 生产模型目录的真实约束，而不是将两者描述成相同结构。

## 覆盖范围

源文件：
[`app/src/benchmark/assets/translation-fixtures.json`](../app/src/benchmark/assets/translation-fixtures.json)

### `en-zh-diverse-v2`

- 40 条、53 份中文参考译文、54 个关键语义检查；
- 13 条有两份参考；
- 源文本长度 32–611 字符，中位数 63；
- 38 条一般文本、2 条高风险文本；
- 类别：UI 4、系统 6、数字 7、否定/逻辑 5、对话 4、惯用语 4、
  技术 3、安全领域 2、文学 2、运行时 2、OCR 鲁棒性 1。

### `ja-zh-diverse-v1`

- 40 条、59 份中文参考译文、52 个关键语义检查；
- 19 条有两份参考；
- 源文本长度 7–54 字符，中位数 27.5；
- 36 条一般文本、4 条高风险文本；
- 类别：UI 5、系统 5、数字 5、否定/逻辑 5、敬语/省略 4、对话 4、
  惯用语 4、技术 4、安全领域 2、文学 1、词义消歧 1。

新增内容覆盖：

- UI 权限、不可逆操作、存储警告和同步状态；
- 日期、时区、令和年号、单位、金额、订单号和下载进度；
- `unless`、部分否定、双重否定、only-if 和“不再支持”；
- 日语敬语、受益表达、省略主语、软拒绝和语用含义；
- 惯用语、谚语、同音异义和公版文学句；
- 药量、退款条件、密钥日志、线程和缓存/数据区分。

所有关键检查同时允许不改变含义的常见中文等价形式，例如 `mg/毫克`、
`设备/终端`、`省电/节电`、阿拉伯数字/中文数字；明确的方向、时间、剂量和
不可逆含义错误仍判失败。正式测试会编译每一条正则，避免规则损坏。

## 总体质量与延迟

BLEU 和 chrF++ 使用每条样例的全部项目自写中文参考。它们是方向性指标，
不能代替逐类检查和人工审阅。

### Raw：完整源句直接翻译

| 语言对 / 引擎 | BLEU | chrF++ | 关键检查 | 中位延迟 | P95 |
|---|---:|---:|---:|---:|---:|
| 英→中 ML Kit | 38.462 | 27.869 | 32/54 | **27.643 ms** | 100.613 ms |
| 英→中 Bergamot | **38.969** | **28.733** | **35/54** | 35.547 ms | **82.022 ms** |
| 日→中 ML Kit | **33.158** | 23.316 | 34/52 | **39.627 ms** | **92.627 ms** |
| 日→英→中 Bergamot | 32.368 | **23.386** | **43/52** | 64.410 ms | 105.689 ms |

### App `ClauseSplitter` pipeline

| 语言对 / 引擎 | BLEU | chrF++ | 关键检查 | 中位延迟 | P95 |
|---|---:|---:|---:|---:|---:|
| 英→中 ML Kit | 38.342 | 27.704 | 32/54 | **25.760 ms** | 100.435 ms |
| 英→中 Bergamot | **39.132** | **29.038** | **36/54** | 34.576 ms | **77.700 ms** |
| 日→中 ML Kit | **33.158** | 23.316 | 34/52 | **41.694 ms** | **93.582 ms** |
| 日→英→中 Bergamot | 32.368 | **23.386** | **43/52** | 63.073 ms | 107.370 ms |

日语 40 条均短于当前 90 字符不拆分阈值，所以 raw 与 pipeline 质量相同。
代码另加入了长日语段落按 `。！？` 保留标点拆分的 JVM 回归测试。

## 英→中判断

Bergamot 在本套 40 条数据上小幅领先：

- raw BLEU `+0.507`、chrF++ `+0.864`、关键检查 `+3`；
- pipeline BLEU `+0.790`、chrF++ `+1.334`、关键检查 `+4`；
- 否定/逻辑、对话和惯用语类别分数较好；
- ML Kit 的中位延迟仍更低，且生产集成已存在。

这证明 Bergamot 是值得保留的候选，而不是证明应立即替换默认引擎。模型只有
单语言对，生产还缺 JNI 生命周期、模型交付、取消、内存压力和签名 Release/R8
验收。

## 日→中逐类判断

以下为 raw 层：

| 类别 | ML Kit BLEU / chrF++ / 检查 | Bergamot 级联 BLEU / chrF++ / 检查 | 判断 |
|---|---|---|---|
| UI | 28.611 / 18.838 / 5/7 | **34.590 / 26.994 / 6/7** | 级联较好 |
| 系统 | 27.918 / 22.656 / 5/7 | **29.990 / 26.067 / 7/7** | 级联较好 |
| 数字 | **28.346** / 27.284 / 2/5 | 22.353 / **30.420** / **3/5** | 互有胜负 |
| 否定/逻辑 | 45.687 / 39.929 / 6/8 | **54.261 / 44.817 / 8/8** | 级联明显较好 |
| 敬语/省略 | 20.822 / 16.232 / 2/4 | **24.010 / 18.251 / 3/4** | 级联较好 |
| 对话 | 23.515 / 14.762 / 1/3 | **37.192 / 18.740 / 2/3** | 级联较好 |
| 惯用语 | **11.544 / 9.472** / 1/4 | 5.703 / 7.069 / 1/4 | 两者都弱，ML Kit 略好 |
| 技术 | **41.727 / 26.602** / 6/7 | 31.000 / 22.014 / **7/7** | ML Kit 更贴近参考，级联语义检查完整 |
| 安全领域 | **35.300 / 24.737** / 4/5 | 31.328 / 23.232 / 4/5 | ML Kit 略好 |
| 文学 | **37.069 / 22.349** / 1/1 | 20.861 / 18.442 / 1/1 | ML Kit 较好 |
| 词义消歧 | 35.084 / 19.515 / 1/1 | **37.818 / 22.049** / 1/1 | 级联略好 |

逐句 BLEU 为 Bergamot 胜 16、ML Kit 胜 22、平 2；逐句 chrF++ 为
19/19/2。总分接近，但错误类型不同。

### 级联的有效优势

- `ユーザーが再び許可した場合にのみ...`
  - ML Kit：`如果再次允许用户，则只会重新启动服务。`
  - Bergamot 枢轴：`Only if the user allows it again...`
  - Bergamot 中文：`只有当用户再次允许时，服务才会重新启动。`
  - 级联正确保留 only-if 方向。
- `田中さんが資料を送ってくれました。`
  - ML Kit 丢失“发给我”的受益对象；
  - 日英阶段得到 `Mr. Tanaka sent me the documents.`，最终中文保留“给了我”。
- `ソフトウェアを有効化していない場合に限り...`
  - 级联明确输出“只有……才……”，ML Kit 只输出普通 `如果` 条件。

### 级联的真实缺陷

- `バックアップを削除すると元に戻せません。`
  - 日英枢轴正确：`cannot be undone`；
  - 英中阶段却输出 `无法卸载`，说明第二阶段引入新错误。
- 药量句的日英枢轴正确为 `one tablet ... twice a day`；
  - 最终中文变成 `每天服用一片……两片`，频次/数量表达损坏。
- `石の上にも三年`、`雨降って地固まる` 等谚语两阶段仍趋向逐字翻译；
  级联不会自动解决惯用语知识不足。

### ML Kit 的有效优势与缺陷

- 文学短句和技术说明整体更贴近中文参考；
- 药量的每日两次和上限表达正确；
- 但 UI 权限句丢失“屏幕/本次”的对象与范围；
- `行けたら行く` 被译为 `如果你走了`，语用和主语均错误；
- `令和8年` 被直接省略；预订日期曾把 `2026` 错成 `2012`；
- `猫の手も借りたい`、`石の上にも三年` 等仍是字面翻译。

## 启动、内存与热状态

| 路径 | 准备/模型加载 | Warm-up | 套件结束/稳态 RSS | HWM |
|---|---:|---:|---:|---:|
| ML Kit 英→中 | 24.596 ms | 113.216 ms | 268,840 KiB RSS；152,520 KiB PSS | 278,148 KiB |
| ML Kit 日→中 | 39.797 ms | 189.954 ms | 319,712 KiB RSS；203,169 KiB PSS | 331,888 KiB |
| Bergamot 英→中 | 115.094 ms | 370.358 ms | 371,032 KiB | 467,548 KiB |
| Bergamot 日→英→中 | 246.577 ms | 745.591 ms | 693,168 KiB | **786,048 KiB** |

注意：ML Kit 数据来自应用进程 `dumpsys meminfo`，Bergamot 来自独立原生进程
`/proc/self/status`，可用于同轮量级比较，不应视为完全相同的内存口径。

两次最终 Bergamot 测试前后：

- 电池 100%；
- `dumpsys battery` 温度范围为 29.3–29.7°C；
- Android Thermal Status 均为 0；
- 80 组 × 3 次中每组输出均保持确定性；
- 原生 stderr 为空。

## 部署可行性

| 门槛 | 英→中单模型 | 日→英→中级联 |
|---|---|---|
| Android 16 / ARM64 链接与执行 | 通过 | 通过 |
| 模型、配置与远程 SHA-256 | 通过 | 两阶段均通过 |
| 输出确定性 | 通过 | 通过 |
| 质量 | 候选可继续 | 互有胜负，不足以替换直接模型 |
| 中位延迟 | 可接受但慢于 ML Kit | 明显慢于 ML Kit |
| 模型资产 | 47.60 MiB | 99.83 MiB |
| 峰值内存 | 高于 ML Kit | 约 767.63 MiB，不通过当前生产门槛 |

因此：

- **Bergamot 移动端部署在技术上可行。**
- **日中两阶段级联在当前项目中不宜产品化。**
- 若继续推进，优先寻找或训练直接 `ja→zh` 模型；其次才是 JNI 集成。

## 可复现命令

生成两个固定模型目录：

```powershell
python .\tools\model-benchmark\fetch_mozilla_model.py --pair en-zh
python .\tools\model-benchmark\fetch_mozilla_model.py --pair ja-en
```

启动 ML Kit 日中测试：

```powershell
adb shell am start -W -n `
  com.screentranslation.app.benchmark/com.screentranslation.app.benchmark.ModelBenchmarkActivity `
  --ez translation_only true `
  --es source_language ja `
  --es target_language zh `
  --es fixture_suite ja-zh-diverse-v1 `
  --ei translation_repetitions 3
```

运行日英中级联：

```powershell
python .\tools\bergamot-android-poc\run_device.py `
  .\app\build\model-benchmark\device-run\translation-mlkit-ja-zh-android.json `
  --binary .\app\build\bergamot-android-poc\bin\arm64-v8a\bergamot-android-benchmark `
  --model-dir .\app\build\model-benchmark\mozilla-ja-en-base-memory-2026-07-29 `
  --model-dir .\app\build\model-benchmark\mozilla-en-zh-base-memory-2026-07-28 `
  --repetitions 3 `
  --output .\app\build\model-benchmark\device-run\translation-bergamot-ja-en-zh-android.json
```

## 最终产物哈希

本地原始结果保存在被 Git 忽略的
`app/build/model-benchmark/multilingual-2026-07-29/`。

| 产物 | SHA-256 |
|---|---|
| ML Kit 英中 JSON | `eab94cfa7f79b3b32999f3dd01380dbaecdf0602d6dce633be6ccbb34a5834aa` |
| ML Kit 英中 Scores | `c0039d9d341f31d3746e3140dbcb40905e45a71cda1bb521252b3efaaaee6ceb` |
| Bergamot 英中 JSON | `fdb71f672dcd9e480e65c0ed0f774941e328ec7dfd3fe1cb8e9f982a3998b135` |
| Bergamot 英中 Scores | `43220de478cd93c890067111f418f9b74312df6b889a2d594d39e67eb3d9ffa7` |
| ML Kit 日中 JSON | `ab5bf4aea0d9f270439840edb60889076668cfdf4fbeef20a9c06f177f64be86` |
| ML Kit 日中 Scores | `3a4d9b16b0bc35fd1e48d8000d2f2c8b1cb8487091381f51b2242b940df97405` |
| Bergamot 日英中 JSON | `ec17bfdef08644c9bd6fa33f6e5fc5386450e50af1b6ddff63f259f9ab2c8dc6` |
| Bergamot 日英中 Scores | `2fc7f523ded81aa3359366ac92a2670164de37b129f4a8fe07df8e8ce8332892` |
| 原生 runner | `18fa11d8af9d04d34d49f7402a2859cce5e95377e89040204887afd219067e6e` |

## 局限与下一轮

- 每个语言对 40 条已显著优于原 10 条，但仍是项目定向回归集，不是通用语料统计。
- 参考译文由项目编写；下一轮应加入双盲人工充分性/流畅度评分和更多独立参考。
- 本轮绕过 OCR；真实屏幕还需加入日文 UI 截图、竖排/混排、低对比、小字号和
  OCR 错误传播。
- 本轮为短时质量轮；级联若继续，应做与 PP-OCRv6 同进程/隔离进程的长时内存、
  thermal、LMK、后台恢复和签名 Release/R8 测试。
- 全屏持续翻译应使用最新帧队列、稳定文本门和去重，不应把 64 ms 单句延迟简单
  乘以屏幕文本块数。

## 上游依据

- [Firefox Translations model registry](https://mozilla.github.io/translations/firefox-models/)
- [Mozilla Translations](https://mozilla.github.io/translations/)
- [Bergamot Translator](https://github.com/browsermt/bergamot-translator)
- [Pinned Bergamot Android ARM64 workflow](https://github.com/browsermt/bergamot-translator/blob/9271618ebbdc5d21ac4dc4df9e72beb7ce644774/.github/workflows/arm.yml)
- [ML Kit on-device translation](https://developers.google.com/ml-kit/language/translation/android)
