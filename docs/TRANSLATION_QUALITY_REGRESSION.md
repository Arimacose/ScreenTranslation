# 可公开复现的翻译质量回归

本项目从 `2026.08-public-v1` 起将英→中、日→中测试集作为**带版本和
SHA-256 的公开回归语料**维护。自动分数、关键语义检查、保护片段检查、双盲
人工评分和 Online 失败契约共同构成候选翻译后端的质量门；BLEU 或 chrF++
中的任一单项都不代表发布结论。

## 固定输入与许可

| 项目 | 固定值 |
|---|---|
| 语料发布 | `2026.08-public-v1` |
| 语料文件 | `app/src/benchmark/assets/translation-fixtures.json` |
| SHA-256 固定文件 | `app/src/benchmark/assets/translation-fixtures.sha256` |
| 当前 SHA-256 | `d5995c3ba21aaf45ae65b1dc93d0c2df3ddb6d7cb6e9861ad7f8e6923e32bb28` |
| 权利/来源说明 | `app/src/benchmark/assets/translation-fixtures.LICENSE.md` |
| 自动阈值 | `tools/model-benchmark/fixtures/translation-regression-thresholds.json` |
| 人审量表 | `tools/model-benchmark/fixtures/human-rating-rubric.json` |
| Online 失败契约 | `tools/model-benchmark/fixtures/online-failure-contract.json` |

项目自建源文、全部中文参考译文、语义检查及元数据使用 Apache-2.0。三条引用
公版原作的 source excerpt 在 JSON 的 `provenance_id` 和权利说明中分别列出
作品、作者、来源记录及 `LicenseRef-Public-Domain`；它们的中文参考译文仍是
项目自写的 Apache-2.0 内容。

每次运行先同时检查：

1. corpus 字节摘要与 `.sha256` 固定值一致；
2. 每条 case 都能解析到来源登记项和参考译文 SPDX；
3. 每条关键正则可编译，且至少一个项目参考译文能命中该语义检查；
4. 阈值文件绑定同一 `corpus_release`；
5. failure fixture 不包含 API-key 形状的值。

修改语料时必须使用新的 `corpus_release` 并更新固定摘要。旧候选在旧语料上的
分数不能直接填入新发布的门槛报告。

## 覆盖矩阵

| 语言方向 | 用例 | 类别 | 中文参考 | 关键检查 | 公版源文 |
|---|---:|---:|---:|---:|---:|
| 英→中 | 48 | 15 | 62 | 64 | 2 |
| 日→中 | 48 | 15 | 68 | 62 | 1 |

两套语料均显式包含：

- URL、邮箱、版本号、模板占位符、host、订单号等 `protected_span`；
- 多从句长文本、否定、条件、例外、数量和时间；
- 紧凑 UI label、权限、破坏性操作和系统状态；
- 说话人、断行、打断、口语与上下文相关的字幕；
- 倒计时、价格、折扣、自动续订、退货窗口等电商文字；
- 文学、惯用语、敬语、技术文本及高风险剂量/退款条件。

Online 另有 9 个完全本地的协议/传输回放：401、403、HTTP 408、socket read
timeout、429 `Retry-After`、500、503、畸形 JSON 和空译文。它固定“可能已被
服务端处理的 socket/generation timeout 不重试；HTTP 408/429/503 至多重试一次；
普通 500 不重试；错误时保留上一条译文”的候选行为，不向任何真实服务发请求。

## 自动分数和可执行门槛

所有 edition 同时满足以下相对回归门：

- 相对同 edition 的已发布 incumbent，BLEU 下降不超过 `2.0`；
- chrF++ 下降不超过 `2.0`；
- 关键检查通过率下降不超过 `0.03`；
- 所有 protected-span 检查必须通过。

`translation_raw` 的绝对下限如下。数值基于 `2026-07-29` 至
`2026-08-01` 的同源 40-case 历史基准留出回归余量，现已绑定扩充后的
`2026.08-public-v1`；发布候选必须重新跑 48-case 结果，不复用旧报告数字。

| Edition | 方向 | BLEU | chrF++ | 关键检查率 | 充分性 | 流畅性 |
|---|---|---:|---:|---:|---:|---:|
| Lite | 英→中 | ≥34 | ≥24 | ≥0.60 | ≥3.5 | ≥3.5 |
| Lite | 日→中 | ≥28 | ≥20 | ≥0.70 | ≥3.5 | ≥3.5 |
| Full | 英→中 | ≥44 | ≥31 | ≥0.62 | ≥4.0 | ≥4.0 |
| Full | 日→中 | ≥38 | ≥28 | ≥0.68 | ≥4.0 | ≥4.0 |
| Online | 英→中 | ≥50 | ≥36 | ≥0.72 | ≥4.2 | ≥4.2 |
| Online | 日→中 | ≥45 | ≥34 | ≥0.72 | ≥4.2 | ≥4.2 |

人工门还要求：每个 blind output 至少 2 名独立评分者、case 覆盖率 `1.0`、
critical human error rate 不超过 `0.02`。Online 必须额外通过完整失败契约。

## 一分钟无密钥自检

安装项目已固定的 scorer 依赖后运行：

```powershell
python -m pip install -r .\tools\model-benchmark\requirements.txt
python .\tools\model-benchmark\translation_regression.py validate
python .\tools\model-benchmark\translation_regression.py smoke `
  --output .\app\build\model-benchmark\translation-regression\harness-smoke.json
python -m unittest discover -s .\tools\model-benchmark -p "test_*.py" -v
```

`smoke` 使用项目参考译文生成 candidate/incumbent 回放，只证明语料、指标、阈值、
保护片段和 Online failure gate 的代码路径可执行。报告会明确写入
`not_model_evidence`、`release_ready: false`，不能冒充真实模型质量或人工验收。
仓库保留的确定性报告是
`docs/evidence/translation-regression-harness-smoke.json`。

## 真实候选结果契约

Android Benchmark 或 host adapter 结果必须携带以下字段：

```json
{
  "method": {
    "fixture_schema_version": 2,
    "fixture_suite": "en-zh-diverse-v2",
    "fixture_corpus_release": "2026.08-public-v1",
    "fixture_sha256": "d5995c3ba21aaf45ae65b1dc93d0c2df3ddb6d7cb6e9861ad7f8e6923e32bb28"
  }
}
```

结果 case 的 ID 和顺序必须与语料完全一致。项目的 ML Kit Benchmark activity、
OPUS-MT、Bergamot、Android Bergamot、Hy-MT2 和 TranslateGemma adapter 都会
把这些 fixture 元数据传到候选 JSON。模型权重、运行日志和真实结果仍放在被忽略的
`app/build/model-benchmark/`；PR 只提交 harness、固定语料和经过审核的摘要。

候选和 incumbent 各准备两份 JSON 后，可先运行不含人审的诊断门：

```powershell
python .\tools\model-benchmark\translation_regression.py gate `
  --edition full `
  --candidate en-zh=PATH\candidate-en.json `
  --candidate ja-zh=PATH\candidate-ja.json `
  --baseline en-zh=PATH\incumbent-en.json `
  --baseline ja-zh=PATH\incumbent-ja.json `
  --automated-only-smoke `
  --output .\app\build\model-benchmark\translation-regression\full-auto.json
```

该模式的 `release_ready` 固定为 `false`。Online 还要先生成或接入实际客户端测试
产生的 failure replay：

```powershell
python .\tools\model-benchmark\translation_regression.py replay-failures `
  --output .\app\build\model-benchmark\translation-regression\failure-replay.json
```

正式 Online `gate` 用 `--failure-replay FILE` 指向结果。仓库自带的确定性回放用于
验证 contract evaluator；客户端集成测试应按同一 schema 写入实际分类、重试次数、
退避和 prior-text 保留结果。

## 双盲充分性/流畅性流程

### 1. 建立盲评包

至少提供 incumbent 与 candidate 两个系统，每个系统都包含两个语言方向：

```powershell
python .\tools\model-benchmark\translation_regression.py blind `
  --system incumbent:en-zh=PATH\old-en.json `
  --system incumbent:ja-zh=PATH\old-ja.json `
  --system candidate:en-zh=PATH\new-en.json `
  --system candidate:ja-zh=PATH\new-ja.json `
  --seed release-2026.08-review-1 `
  --sheet PATH\blind-sheet.json `
  --key PATH\blind-key.json `
  --rating-template PATH\ratings-template.json
```

`blind-sheet.json` 只含 source、匿名 `output_id` 和随机输出顺序，不含 engine、
edition、model 名称；映射只存在 `blind-key.json`。key 不进入公开 PR，在评分锁定前
也不交给评分者。对同一个 corpus 和 seed，匿名化结果完全确定，便于审计。

### 2. 两名以上评分者独立填写

每名评分者复制 template，填写不同的 pseudonymous `rater_id`，并对每条 output
填写：

- `adequacy`: 1–5，判断源文含义、逻辑、实体和语气是否保留；
- `fluency`: 1–5，判断简体中文是否自然连贯；
- `critical_error`: `none` 或量表列出的意义反转、否定/条件、数量/时间、保护片段、
  危险指令、未翻译之一；
- `notes`: 可选的简短依据。

工具拒绝漏项、重复项、超出 1–5 的分数、未知 error 和重复 rater ID。

### 3. 聚合并运行正式门

```powershell
python .\tools\model-benchmark\translation_regression.py score-human `
  --sheet PATH\blind-sheet.json `
  --key PATH\blind-key.json `
  --ratings PATH\reviewer-a.json `
  --ratings PATH\reviewer-b.json `
  --output PATH\human-summary.json

python .\tools\model-benchmark\translation_regression.py gate `
  --edition full `
  --candidate en-zh=PATH\candidate-en.json `
  --candidate ja-zh=PATH\candidate-ja.json `
  --baseline en-zh=PATH\incumbent-en.json `
  --baseline ja-zh=PATH\incumbent-ja.json `
  --human-summary PATH\human-summary.json `
  --candidate-system candidate `
  --output PATH\full-release-gate.json
```

正式 gate 只有在自动门、保护片段、人审门，以及 Online 的失败门全部通过时才写入
`release_ready: true` 并以 0 退出。这个流程从 fixture validation 到盲评聚合均不需要
私有 API key；Online 模型的真实译文可以由用户在自己的环境中生成后离线评分。

## 维护原则

1. 语料和阈值是 release-scoped 输入，模型输出是独立、可替换的证据。
2. 新参考译文必须由项目编写或明确登记来源/许可，不从闭源服务输出反向充当 gold。
3. 新增语义检查时，至少一个参考译文必须通过，且单元测试要覆盖真实正例和反例。
4. 保护片段是硬门；高 BLEU 不抵消 URL、金额、日期、版本号或占位符损坏。
5. 失败回放只包含项目自写 fixture，不保存 API key、真实请求头或供应商响应正文。
6. 参考 replay 只做 harness smoke；模型替换决策使用真实输出和双盲结果。
