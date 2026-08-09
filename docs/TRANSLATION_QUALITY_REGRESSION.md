# 可公开复现的翻译质量回归

`2026.08-public-v2-original-references` 是英→中、日→中翻译回归语料的当前
发布。自动指标、关键语义检查、protected hard gate、由原始评分重算且逐输出绑定
候选/基线的盲评、Online 新鲜 Kotlin 策略执行，以及可信 runner provenance 共同决定
发布结果；BLEU、chrF++、自报元数据或历史报告中的单个数字都不能单独证明
release ready。

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
候选的完整逐条输出。质量评估必须重新生成当前 corpus 的完整 candidate 和
incumbent，不能把历史 aggregate 或手填分数伪装成通过证据；发布判定还要求新鲜
gate-owned runner response 或可验签的外部 attestation。

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

当前仓库尚未登记各 edition 的 canonical incumbent manifest/pin。调用方传入的 baseline
可用于生成 `metric_checks_passed` 诊断，但不能自称发布基线；报告固定标记
`baseline_admission.status: unadmitted_caller_supplied_incumbent_records`，并使
`automated_passed`/`release_ready` 保持 false。后续 manifest 必须绑定 edition、corpus
release 和英/日两路完整 evidence SHA，不能由同一次 gate 调用临时自报。

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
    "producer": "screen-translation-benchmark-runner/v1",
    "engine_id": "ENGINE_ID",
    "model_id": "MODEL_ID",
    "model_revision": "LOWERCASE_IMMUTABLE_REVISION_HASH",
    "runtime_id": "RUNTIME_ID",
    "runtime_revision": "LOWERCASE_IMMUTABLE_REVISION_HASH",
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
  ],
  "provenance": {
    "schema_version": 1,
    "producer_id": "screen-translation-benchmark-runner/v1",
    "producer_source_sha256": "CURRENT_TRACKED_RUNNER_SOURCE_SHA256",
    "raw_inference_record_sha256": "CANONICAL_RUNNER_RECORD_SHA256"
  }
}
```

校验器严格检查 48/48 coverage、唯一 ID、source hash、repetition/latency 数量、
finite 范围、median 与样本一致、UTC 时间、固定 runner ID、当前 runner 源码哈希及
raw-record 结构完整性。`model_revision` 与 `runtime_revision` 必须为不可变的小写哈希。
reference、fixture、replay、synthetic、smoke evidence 均被拒绝。

内容级 replay 指纹先做 NFKC，再移除保守的 control/format 超集、空白与标点。
Default-Ignorable 表固定为 Unicode 15.1，显式包含 category 为 `Cn` 的保留范围，
包括 U+2060–U+206F 与 U+FFF0–U+FFF8，不依赖 Python 运行时的 general category
近似。因此给 reference/source 加 U+200B、U+2065、U+FFF0、variation selector、
全半角、空白或仅标点变体不会改变指纹；达到 suite 的 90%（48 条中至少
44 条）即按 near-complete replay/pass-through 拒绝。该规则检测明显的 fixture 复用，
不是通用抄袭检测器。

### Runner provenance 的信任边界

`producer_source_sha256` 与 `raw_inference_record_sha256` 只证明 JSON 对公开源码和自身
record **结构自洽**。任意调用方也能读取公开源码、伪造输出并重算这些哈希，所以它们
不是签名、设备证明或真实模型推理证明。当前本地/CI 流程未持有设备 attestation 私钥，
也不在本次非真机验收中启动设备 runner；`gate` 因而报告
`runner_provenance.status: unattested_structural_runner_records` 并保持
`release_ready: false`。后续只有 gate-owned 一次性 runner challenge response，或对
仓库外受信签名/CI attestation 完成验签，才能把这一门置为通过。

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

普通 Online JVM 测试会逐 case 执行生产 policy/parser，检查分类、retry、attempt、
delay，并通过 `RegionOverlayContentPolicy` 的生产 reducer 依次执行
success→pending→failure，证明 reducer 始终保留上一组完整原文+译文，而不是显示新原文
配旧译文。该证据覆盖 reducer 合同；服务层 wiring 由常规编译/单测约束，不把这条测试
表述为完整 UI/service instrumentation：

```powershell
.\gradlew.bat --no-daemon :app:testOnlineDebugUnitTest
```

正式 Online gate 不读取普通测试留下的固定 JSON。它在进程内生成 256-bit 随机 nonce
和限时 challenge，绑定 contract、Kotlin producer 源码哈希，以及 producer/protocol、
生产 policy/parser、region reducer、Gradle build/wrapper 和 Python gate helper 构成的
确定性 execution-chain manifest，然后启动专用任务：

```powershell
python .\tools\model-benchmark\online_failure_evidence.py `
  --output PATH\online-failure-evidence.audit.json
```

该 helper 使用 gate 独占临时 challenge/response 路径，拒绝 Gradle 用户目录中的隐式
`init.gradle[.kts]`、`init.d/*.gradle[.kts]` 与 `gradle.properties`，清除
`GRADLE_OPTS`、`JAVA_OPTS`、`JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、
`_JAVA_OPTIONS` 与 `ORG_GRADLE_PROJECT_*` 注入，再调用
`:app:generateFreshOnlineFailureEvidence`。Kotlin 独立重算 execution-chain hash，现场执行
生产 policy/parser，为每条 actual 计算 nonce-bound digest，并以 `CREATE_NEW` 写 schema v3
response；Python 再验证 nonce/challenge/chain/time、9/9 coverage、严格类型与逐字段策略
结果。旧 schema、旧 nonce、预植输出或调用方 evidence JSON 均不是这条 gate 的输入。

这条链证明的是“当前 hash-pinned checkout 在本次进程中产生了匹配结果”，不是密钥签名、
隔离 worker 证明或 CI attestation。同一主机/用户仍能修改 checkout 或构建环境并重算所有
公开哈希；因此不得把 nonce/digest 描述为真实执行的密码学防伪。正式 release 仍由独立的
candidate runner attestation 与 canonical incumbent admission fail-closed。输出文件只是
可留存的审计副本，例如：

```text
app/build/model-benchmark/translation-regression/online-failure-evidence.json
```

Online `gate` 内部直接运行这条 fresh challenge 链，API/CLI 均没有
`failure_evidence`/`--failure-evidence` 注入口。旧 schema-v2 校验命令仅以
`audit-legacy-online-evidence` 名义保留，固定报告 `formal_gate_eligible: false`。

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

结构门要求至少两份完整评分文档，每份使用不同的 pseudonymous `rater_id`，
对每个 output 给出：

- `adequacy`: 1–5；
- `fluency`: 1–5；
- `critical_error`: rubric 中的固定枚举；
- `notes`: 简短依据。

评分端对 sheet、key、rating 文档逐层 exact-schema 校验，验证 bundle/sheet hash、
opaque output 一对一覆盖、唯一 rater、1–5 整数和 critical error 枚举。篡改 source、
漏项、重复 output、未知字段或换用另一 sheet 的 key 都会失败。

### 3. 审计聚合并运行 formal gate

```powershell
python .\tools\model-benchmark\translation_regression.py score-human `
  --sheet PATH\review.blind-sheet.json `
  --key $HOME\.screentranslation\blind-review-keys\BUNDLE.blind-key.json `
  --ratings PATH\reviewer-a.blind-ratings.json `
  --ratings PATH\reviewer-b.blind-ratings.json `
  --rubric .\tools\model-benchmark\fixtures\human-rating-rubric.json `
  --output PATH\human-summary.audit.json

python .\tools\model-benchmark\translation_regression.py gate `
  --edition online `
  --candidate en-zh=PATH\candidate-en.json `
  --candidate ja-zh=PATH\candidate-ja.json `
  --baseline en-zh=PATH\incumbent-en.json `
  --baseline ja-zh=PATH\incumbent-ja.json `
  --blind-sheet PATH\review.blind-sheet.json `
  --blind-key $HOME\.screentranslation\blind-review-keys\BUNDLE.blind-key.json `
  --ratings PATH\reviewer-a.blind-ratings.json `
  --ratings PATH\reviewer-b.blind-ratings.json `
  --rubric .\tools\model-benchmark\fixtures\human-rating-rubric.json `
  --candidate-system candidate `
  --baseline-system incumbent `
  --output PATH\online-release-gate.json
```

`score-human` 输出只供人工审计，**不能**作为 gate 输入。Gate 必须从 blind sheet、
仓库外 identity key、至少阈值数量的原始 rating documents 和 canonical rubric 重新调用
同一评分器；伪造 aggregate summary 即使字段、候选哈希和全 5 分都正确也没有入口。

Gate 还要求 blind key 的 system 集合严格等于 candidate+baseline，并逐 pair/case 将
sheet 的每个 output text、key 中的 `system_evidence_sha256` 和实际 candidate/baseline
JSON 双向对回。联合修改 sheet+key hash、替换基线文字、复用另一候选评分、缺 rating、
重复 rater 或篡改 rubric 都会失败。Online edition 随后自动执行 fresh Kotlin challenge。

`rater_id` 只是 JSON 内的唯一字符串；当前没有 reviewer allowlist、签名或受保护审批，
因此它不证明文档真的来自两名独立自然人。报告只把分数标为
`recomputed_from_structurally_valid_unauthenticated_raw_ratings`，并在
`reviewer_authenticity` 门保持 false。当前还缺 canonical incumbent admission 与可信
candidate runner attestation，因此即使 metric/score/Online 全部通过，
`automated_passed` 和 `release_ready` 仍保持 false。

## 维护原则

1. Canonical corpus 是 source/reference/check/category/tag 的唯一权威。
2. Model result 只保存输出、严格推理元数据与结构完整性哈希；不保存私有 API key，也
   不把公开自哈希描述成真实性证明。
3. 历史 aggregate 与 harness smoke 均不是发布证据。
4. Protected span 损坏是硬失败，高 BLEU 不能抵消。
5. 新 corpus release 必须更新 byte pin、阈值绑定、原创性审计和校准说明。
6. Online 错误证据由 formal gate 现场 challenge Kotlin 生产 policy/parser；Python 不接收
   caller evidence，也不生成 expected-copy replay。
7. 聚合 human summary 不是发布输入；正式门每次从 raw sheet/key/ratings/rubric 重算。
8. 不同 pseudonymous `rater_id` 不等于已验证的独立评审者；未接入签名或
   受保护审批前，reviewer authenticity 必须 fail-closed。
