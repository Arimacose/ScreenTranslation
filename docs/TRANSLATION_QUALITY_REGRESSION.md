# 可公开复现的翻译质量回归

`2026.08-public-v2-original-references` 是英→中、日→中翻译回归语料的当前
发布。自动指标、关键语义检查、protected hard gate、绑定候选哈希的盲评，以及
Online 生产 Kotlin 策略证据共同决定发布结果；BLEU、chrF++ 或历史报告中的单个
数字都不能单独证明 release ready。

## 固定输入、许可与原创性

| 项目 | 固定值 |
|---|---|
| 语料文件 | `app/src/benchmark/assets/translation-fixtures.json` |
| 语料发布 | `2026.08-public-v2-original-references` |
| 语料 SHA-256 | `043bb49a27d647a24aba96c605f8d5eea0b5fd8d19eac490161b4e48b772bd72` |
| 权利与原创性审计 | `app/src/benchmark/assets/translation-fixtures.LICENSE.md` |
| 阈值 | `tools/model-benchmark/fixtures/translation-regression-thresholds.json` |
| 历史校准登记 | `tools/model-benchmark/fixtures/translation-regression-calibration.json` |
| 人审量表 | `tools/model-benchmark/fixtures/human-rating-rubric.json` |
| Online 失败契约 | `tools/model-benchmark/fixtures/online-failure-contract.json` |

每个方向包含 48 条，覆盖 protected spans、长句、UI、字幕、电商、否定/条件、
数量、文学与高风险剂量/退款条件。三个公版源文有作品、作者和来源登记；所有中文
参考译文均为项目原创 Apache-2.0 内容。v2 已重写 Austen、Dickens 和《吾辈是猫》
的六条参考，校验器保留退休参考的 SHA-256 与 12 字符窗口哈希，不重新提交退休
译文或可复原长片段。逐项搜索和近似重复审计记录在权利说明中。

`validate` 同时检查 corpus pin、唯一 ID/源文、来源/许可、关键正则、参考正例、
退休参考指纹、Online contract、完整阈值范围和历史校准文件哈希。

## 历史校准不是 formal evidence

Lite/Bergamot、Full/Hy-MT2 Q4 和 Online/DeepSeek V4-Flash 的既有 40-case 报告
及结果 SHA-256 已登记到 calibration manifest。它们解释当前阈值的量级，但都明确
标记为 `formal_gate_eligible: false`：当前语料是 48-case，仓库也不保存那些历史
候选的完整逐条输出。正式结果必须重新生成当前 corpus 的完整 candidate 和真实
incumbent，不能把历史 aggregate 或手填分数伪装成通过证据。

当前 raw translation 下限为：

| Edition | 方向 | BLEU | chrF++ | 关键检查率 | 充分性 | 流畅性 |
|---|---|---:|---:|---:|---:|---:|
| Lite | 英→中 | ≥34 | ≥24 | ≥0.60 | ≥3.5 | ≥3.5 |
| Lite | 日→中 | ≥28 | ≥20 | ≥0.70 | ≥3.5 | ≥3.5 |
| Full | 英→中 | ≥44 | ≥31 | ≥0.62 | ≥4.0 | ≥4.0 |
| Full | 日→中 | ≥38 | ≥28 | ≥0.68 | ≥4.0 | ≥4.0 |
| Online | 英→中 | ≥50 | ≥36 | ≥0.72 | ≥4.2 | ≥4.2 |
| Online | 日→中 | ≥45 | ≥34 | ≥0.72 | ≥4.2 | ≥4.2 |

候选还必须相对同 edition incumbent 满足 BLEU/chrF++ 下降均不超过 2.0、关键
检查率下降不超过 0.03。`category == protected_span` **或** tags 含 `protected` /
`protected_span` 的任何 case 都进入 protected hard gate，必须逐项通过。

## Formal candidate schema

候选文件不携带 source text、参考译文、category、tags、risk 或 critical checks；这些
字段只能从固定 corpus 按 `case_id + source_sha256` join。以下各层均为 exact-key
schema，任何未知字段都会失败：

```json
{
  "schema_version": 2,
  "evidence_kind": "real_model_inference",
  "corpus_release": "2026.08-public-v2-original-references",
  "fixture_sha256": "043bb49a27d647a24aba96c605f8d5eea0b5fd8d19eac490161b4e48b772bd72",
  "suite_id": "en-zh-diverse-v2",
  "source_language": "en",
  "target_language": "zh",
  "inference": {
    "producer": "DEVICE_BENCHMARK_RUNNER",
    "engine_id": "ENGINE_ID",
    "model_id": "MODEL_ID",
    "model_revision": "MODEL_REVISION",
    "runtime_id": "RUNTIME_ID",
    "runtime_revision": "RUNTIME_REVISION",
    "device_kind": "physical-android-device",
    "device_model": "DEVICE_MODEL",
    "os_version": "Android-16",
    "architecture": "arm64-v8a",
    "started_at_utc": "2026-08-09T01:00:00Z",
    "completed_at_utc": "2026-08-09T01:05:00Z",
    "repetitions": 1,
    "latency_clock": "elapsed-realtime-monotonic",
    "network_path": "offline"
  },
  "cases": [
    {
      "case_id": "CASE_ID",
      "source_sha256": "SOURCE_TEXT_SHA256",
      "candidate": {
        "output_text": "真实推理输出",
        "latencies_ms": [123.0],
        "median_latency_ms": 123.0
      }
    }
  ]
}
```

Gate 严格检查 48/48 coverage、唯一 ID、source hash、repetition/latency 数量、finite
范围、median 与样本一致、UTC 时间和真实推理元数据。Formal 只接受精确的
`real_model_inference`；reference、fixture、replay、synthetic、smoke evidence 均被
拒绝。即使把 evidence kind 伪写成 real，整套 canonical reference playback 或整套
source pass-through 仍会被内容级检查拒绝。

## Harness smoke 的边界

```powershell
python -m pip install -r .\tools\model-benchmark\requirements.txt
python .\tools\model-benchmark\translation_regression.py validate
python .\tools\model-benchmark\translation_regression.py smoke `
  --output .\app\build\model-benchmark\translation-regression\harness-smoke.json
python -m unittest discover -s .\tools\model-benchmark -p "test_*.py" -v
```

Smoke 用参考译文验证 corpus join、scorer、阈值和 hard gate 的管线是否可执行，
不调用模型，也不执行 Online Kotlin policy。其每个 edition 和总报告都固定
`release_ready: false`；Online smoke 只标记 contract schema 可解析。Formal `gate`
没有 automated-only 或 reference replay 入口。

## Online 失败证据

Online contract 包含 401、403、HTTP 408、socket read timeout、429
`Retry-After`、500、503、畸形 JSON 和空 assistant content。畸形/空输出都经过
`OpenAiChatProtocol.parseTranslation` 与 `OnlineHttpPolicy.sanitizeNetworkFailure`
归类为 `response`，而不是由 Python 把 `expected` 复制成 `actual`。

运行 Online JVM 测试会逐 case 执行生产 policy/parser，检查分类、retry、attempt、
delay，并通过生产 region overlay 状态策略证明失败时保留上一条可用译文：

```powershell
.\gradlew.bat --no-daemon :app:testOnlineDebugUnitTest
```

测试生成被 Gradle build 目录忽略的：

```text
app/build/model-benchmark/translation-regression/online-failure-evidence.json
```

Python formal gate 只接受 `evidence_kind: kotlin_policy_execution`、当前 contract hash、
固定 producer，以及与测试源码当前 SHA-256 一致的证据；synthetic/copied replay 会
被拒绝。Online 运行 gate 时必须传 `--failure-evidence`。

## 盲评工作流

### 1. 生成盲评 sheet 与私有 key

```powershell
python .\tools\model-benchmark\translation_regression.py blind `
  --system incumbent:en-zh=PATH\old-en.json `
  --system incumbent:ja-zh=PATH\old-ja.json `
  --system candidate:en-zh=PATH\new-en.json `
  --system candidate:ja-zh=PATH\new-ja.json `
  --sheet PATH\review.blind-sheet.json `
  --rating-template PATH\review.blind-rating-template.json
```

工具内部使用 `secrets.token_bytes(32)` 与 HMAC-SHA256 产生不可预测 ID 和顺序，CLI
不接受 seed。公开 sheet 不含 system ID、model ID、原 case ID 或可逆 seed；只含
评分必需的 source、context category、匿名 output 和 opaque IDs。私有 key 才含
system/case 映射及每个正式 candidate JSON 的 canonical SHA-256。

`--key` 省略时，key 默认写到用户目录：

```text
~/.screentranslation/blind-review-keys/<bundle-id>.blind-key.json
```

显式 `--key` 也必须位于仓库外；`.gitignore` 另行阻止常见 blind key/sheet/rating
文件名。公开仓库只保留 rubric 与格式说明。

### 2. 独立评分

至少两名评分者各自填写 pseudonymous `rater_id`，对每个 output 给出：

- `adequacy`: 1–5；
- `fluency`: 1–5；
- `critical_error`: rubric 中的固定枚举；
- `notes`: 简短依据。

评分端对 sheet、key、rating 文档逐层 exact-schema 校验，验证 bundle/sheet hash、
opaque output 一对一覆盖、唯一 rater、1–5 整数和 critical error 枚举。篡改 source、
漏项、重复 output、未知字段或换用另一 sheet 的 key 都会失败。

### 3. 聚合并运行 formal gate

```powershell
python .\tools\model-benchmark\translation_regression.py score-human `
  --sheet PATH\review.blind-sheet.json `
  --key $HOME\.screentranslation\blind-review-keys\BUNDLE.blind-key.json `
  --ratings PATH\reviewer-a.json `
  --ratings PATH\reviewer-b.json `
  --output PATH\human-summary.json

python .\tools\model-benchmark\translation_regression.py gate `
  --edition online `
  --candidate en-zh=PATH\candidate-en.json `
  --candidate ja-zh=PATH\candidate-ja.json `
  --baseline en-zh=PATH\incumbent-en.json `
  --baseline ja-zh=PATH\incumbent-ja.json `
  --human-summary PATH\human-summary.json `
  --candidate-system candidate `
  --failure-evidence .\app\build\model-benchmark\translation-regression\online-failure-evidence.json `
  --output PATH\online-release-gate.json
```

Human summary 中每个 system/pair 都携带 `system_evidence_sha256`。Gate 将候选 JSON
重新 canonical-hash 后逐语言对比；给另一候选打出的全 5 分不能复用。只有当前 corpus
真实 candidate/incumbent、自动门、protected hard gate、绑定的人审和（Online）Kotlin
failure evidence 同时通过时，报告才可能写入 `release_ready: true`。

## 维护原则

1. Canonical corpus 是 source/reference/check/category/tag 的唯一权威。
2. Model result 只保存真实输出与推理元数据；不保存私有 API key。
3. 历史 aggregate 与 harness smoke 均不是发布证据。
4. Protected span 损坏是硬失败，高 BLEU 不能抵消。
5. 新 corpus release 必须更新 byte pin、阈值绑定、原创性审计和校准说明。
6. Online 错误证据来自 Kotlin 生产 policy/parser；Python 不生成 expected-copy replay。
