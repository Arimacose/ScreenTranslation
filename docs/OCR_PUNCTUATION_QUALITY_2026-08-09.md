# OCR 标点恢复质量基准（2026-08-09）

## 1. 目标与范围

Issue #40 要求在语义分句和翻译之前，以可复现的确定性规则恢复 OCR
中的高价值标点。返工后的优先级是“宁可少补，不把 UI 标签或视觉换行误当句界”。
实现覆盖：

- 英语、中文、日语高置信单块句尾；
- 明确缺少右侧符号的引号、括号、书名号等成对标点；
- 双换行以上的段落界，以及对单换行自动折行的保守抑制；
- 小数、URL、日期、金额、邮箱和版本字符串的原 UTF-8 字节保护；
- `ClauseSplitter` 的单次候选句界扫描、有界后端请求单元和超长输入降级。

整套基准为纯 Kotlin 本地执行，无模型和网络依赖。fixture 为项目维护者合成数据，
不含私有文本或 API Key。

## 2. 生产链路

区域模式与全屏增量模式使用同一顺序：

1. `ProtectedTextCodec.protect()` 先把翻译敏感值换成输入哈希派生的 token；
2. `OcrPunctuationRestorer` 仅在不透明 token 副本上判断成对符号、段落界和高置信句尾；
3. 单个 `region.text` 内只有一个换行时，不由换行触发句号；多个独立 OCR
   block/region 仍分别走高置信句尾规则；
4. 恢复 token 对应的原始值，再进入 `SourceTextFilter`；
5. 区域端侧模式进入 `ClauseSplitter`，Online 整段模式和全屏变化块进入对应协调器；
6. `ClauseSplitter` 再次保护值后搜索句界，所有输出单元以 `1,024` 字符为目标上限。

因此 Lite、Full、Online，以及区域和全屏增量两条捕获路径都经过同一恢复器。

## 3. 确定性规则与假阳性边界

| 规则 | 恢复条件 | 保守边界 |
|---|---|---|
| 英语块末 | 达到最少词/字母数，且存在显式助动词、有限动词词典或有明确宾语引导词的祈使结构 | 长度不再是兜底；任意 `-ed`/`-ing` 不再触发；`Open source ...` 固定为名词型负例 |
| 中文块末 | `请…`祈使句、句末语气/体标记，或“时态/助动信号 + 强谓语结尾” | `显示/安装/已/翻译` 等 UI 名词不再单独触发 |
| 日语块末 | `です/ます/ました/ません/ください` 等强终止形 | 裸 `する` 不触发，例如 `バックグラウンドで使用する` 保持原样 |
| 段落界 | 至少两个换行；左侧具备强终止形，右侧也通过高阈值句法检查 | 单换行视觉折行在英/中/日三种语言中全部保持原样 |
| 成对标点 | 开符号栈无交叉、无孤立右符号，只在块末补齐缺失右符号 | 交叉/孤立右符号保持 OCR 原样，不猜缺失左符号 |
| 保护值 | URL/domain/email/version/amount 采用显式 ASCII 大小写类，不依赖 JVM Unicode case folding；候选区间用 `TreeMap` 去重 | 每个 OCR 输入最多保护 2,048 个值；远高于屏幕文本预期规模 |
| 超长文本 | `ClauseSplitter` 在 `65,536` 字符以内做语义候选扫描；更大输入直接线性分块 | 不会把 65 KiB 以上原文作为单个翻译请求发送；无空白的异常长串必要时硬切 |

已知的刻意边界：规则不猜单行内部逗号，不为缺失左引号重写句首，
也不仅因“文本足够长”就添加句号。

## 4. Fixture 组成

固定数据位于 `app/src/test/resources/punctuation/punctuation_quality.tsv`。

共 36 条，每种语言 12 条：

- 1 条真实缺句号的高置信段落界；
- 2 条成对标点；
- 1 条混合 URL/日期/版本/小数保护值；
- 1 条基础 UI 标签负例；
- 1 条已有正确标点的幂等输入；
- 5 条 adversarial UI 标签（共 15 条）；
- 1 条单换行视觉折行负例（共 3 条）。

额外单测覆盖：2,000 条固定种子随机/边界输入、跨 JVM 中文保护值直接断言、
多 block 生产入口（完整句 + UI 标签）、交叉标点、英文缩写、43k 密集保护值、
约 60k 密集句界和约 238k 超长降级。

## 5. 阈值与实测

本地 JDK 25 定向回归与 Temurin 17.0.20（与 CI 同主版本）复验的结果一致：

| 指标 | 合格阈值 | 实测 | 结论 |
|---|---:|---:|---|
| 36 条输出 exact-match | `>= 0.95` | `1.000`（36/36） | 通过 |
| 保护值 UTF-8 字节保留率 | `= 1.00` | `1.000`（12/12） | 通过 |
| 假阳性 fixture 原样保持率 | `= 1.00` | `1.000`（21/21） | 通过 |
| 恢复后句界召回率 | `>= 0.90` | `1.000`（3/3） | 通过 |
| 相对未恢复文本的句界召回增益 | `>= 0.50` | `+1.000` | 通过 |

`ClauseSplitter` 对照为同一版分句器分别接收原始 OCR 文本和恢复后文本。
三条段落 fixture 的期望内部句界总数为 3；原文召回 `0/3`，恢复后召回 `3/3`。

Temurin 17 全矩阵为 Lite `175`、Full `174`、Online `189` 项单测，均为
`0 failures / 0 errors / 0 skipped`。`lintLiteDebug`、`lintFullDebug`、
`lintOnlineDebug` 均成功；每个 variant 仅保留 10 条与本 PR 无关的已有 Warning，无 Error/Fatal。

## 6. 性能与最坏输入

时间阈值设为 4,000 ms，用于阻止二次复杂度回归，而不是绑定某台 CI 主机的微基准。

| 输入 | 返工前 adversarial 实测 | 返工后 JDK 25 实测 | 生产边界 |
|---|---:|---:|---|
| 约 43k，1,000 小数 + 1,000 URL | 约 `9,464 ms` | `26–41 ms` | 输出与输入完全一致 |
| 约 60k，500 个完整句 | 约 `1,234 ms` | `30–37 ms` | 500 个有界单元 |
| 约 238k，2,000 个完整句 | 约 `22,673 ms` | `88 ms` | 跳过语义扫描，线性分成 `<=1,024` 字符的请求单元 |

关键改动是：受保护区间由全表 `none` 检查改为 `TreeMap` 相邻区间检查；
token 删除/恢复由每 token 整串扫描改为单次扫描；`ClauseSplitter` 由“切一段、重扫剩余全文”
改为“一次收集候选点、单调前进”。

## 7. 复现

Windows PowerShell：

```powershell
.\gradlew.bat testOnlineDebugUnitTest `
  --tests com.screentranslation.app.util.OcrPunctuationRestorerTest `
  --tests com.screentranslation.app.util.ClauseSplitterTest `
  --tests com.screentranslation.app.util.ProtectedTextCodecTest `
  --no-daemon --console=plain
```

成功日志包含：

```text
PUNCTUATION_QUALITY fixtures=36 exact=1.000 protected=1.000 false_positive=1.000 baseline_boundary_recall=0.000 restored_boundary_recall=1.000 boundary_gain=1.000
```

JUnit XML 位于 `app/build/test-results/testOnlineDebugUnitTest/`，HTML 报告位于
`app/build/reports/tests/testOnlineDebugUnitTest/`。它们是可重建产物，不提交仓库。
