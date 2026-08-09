# OCR 标点恢复质量基准（2026-08-09）

## 1. 目标与范围

Issue #40 要求在语义分句和翻译之前，以可复现的确定性规则恢复 OCR
高价值标点。本实现覆盖：

- 英语、中文、日语句尾；
- 明确缺少右侧符号的引号、圆括号、方括号、花括号、书名号等成对标点；
- OCR 硬换行形成的高置信句界；
- 小数、URL、日期、金额、邮箱和版本字符串的原字节保护；
- `ClauseSplitter` 对英语句号、问号、叹号的新句界支持。

这是一套纯 Kotlin、本地执行、无模型和无网络依赖的回归基准。fixture
由项目维护者为本功能合成，随仓库许可证发布，不含私有文本或 API Key。

## 2. 生产链路

区域模式与全屏增量模式使用同一预处理顺序：

1. `OcrPunctuationRestorer` 先调用 `ProtectedTextCodec.protect()`；
2. 在只含不透明 token 的副本上判断硬换行、成对标点和句尾；
3. 恢复 token 对应的原始值；
4. `SourceTextFilter` 按用户选择的源/目标语言过滤；
5. 区域端侧模式进入 `ClauseSplitter`，Online 整段模式和全屏变化块直接进入对应协调器；
6. `ClauseSplitter` 自身再次保护值后才搜索句界，防止 `3.14`、`v2.0.0`、
   `2026-08-09` 和 URL 内的点被识别为句号。

因此 Lite、Full、Online，以及区域和全屏增量两条捕获路径均经过同一恢复器。

## 3. 确定性规则与假阳性边界

| 规则 | 恢复条件 | 保守边界 |
|---|---|---|
| 块末句尾 | 达到按语言设置的最少词/字符数并包含动作/语法信号，且末尾没有现成句尾或显式分隔符 | 短菜单项、较长名词型菜单项、代码信号、冒号、分号、逗号、路径样式结尾保持原样 |
| OCR 硬换行 | 两侧都达到更高的句子长度阈值；拉丁语系下一行必须以大写源文字开头 | 普通自动换行和小段标签不会仅凭换行获得句号 |
| 成对标点 | 开符号栈无交叉、无孤立右符号，只在块末补齐缺失右符号 | 交叉/孤立右符号保持 OCR 原样；不猜测缺失的左符号 |
| 英语句界 | `. ! ?` 后有空白，两侧均达到 `ClauseSplitter` 最小子句长度 | 常见缩写和单字母首字母缩写跳过 |
| CJK 句界 | `。！？` 后两侧均达到最小子句长度 | 标点保留在左侧翻译单元，不丢字符 |
| 保护值 | 先替换为输入哈希派生的唯一 token，再执行全部规则 | URL 路径限定为 ASCII URL 字符集，紧邻 CJK 正文时不会把正文吞入 URL |

已知边界是刻意设置的：本规则不会在单行内部猜逗号，也不会为缺失左引号重写
句首。质量提升集中在高置信句尾、硬换行和缺失右侧成对符号。

## 4. Fixture 组成

固定数据位于：

`app/src/test/resources/punctuation/punctuation_quality.tsv`

共 18 条，每种语言 6 条：

- 1 条长文本硬换行边界；
- 2 条成对标点；
- 1 条混合 URL/日期/版本/小数保护值；
- 1 条短 UI 标签假阳性边界；
- 1 条已有正确标点的幂等输入。

测试还独立覆盖 100 次重复调用确定性、常见英语缩写、URL 紧邻 CJK 正文、
交叉标点、9 条长短 UI 标签，以及 `ClauseSplitter` 对保护值内部标点的隔离。

## 5. 阈值与实测

在 `dc4ed2e` 基线上加入本实现后，于 2026-08-09 执行固定 fixture：

| 指标 | 合格阈值 | 实测 | 结论 |
|---|---:|---:|---|
| 18 条输出 exact-match | `>= 0.95` | `1.000`（18/18） | 通过 |
| 保护值 UTF-8 字节保留率 | `= 1.00` | `1.000` | 通过 |
| 假阳性 fixture 原样保持率 | `= 1.00` | `1.000`（3/3） | 通过 |
| 恢复后句界召回率 | `>= 0.90` | `1.000`（3/3） | 通过 |
| 相对未恢复文本的句界召回增益 | `>= 0.50` | `+1.000` | 通过 |

对照定义为：同一版 `ClauseSplitter` 分别接收原始 OCR 文本与恢复后文本。
三条长文本 fixture 的期望句界总数为 3；原始文本召回 `0/3`，恢复后召回
`3/3`。这隔离了标点恢复的贡献，而不是把连接词分句计入增益。

## 6. 复现

Windows PowerShell：

```powershell
.\gradlew.bat testOnlineDebugUnitTest `
  --tests com.screentranslation.app.util.OcrPunctuationRestorerTest `
  --tests com.screentranslation.app.util.ClauseSplitterTest `
  --tests com.screentranslation.app.util.ProtectedTextCodecTest `
  --console=plain --info
```

成功日志包含：

```text
PUNCTUATION_QUALITY fixtures=18 exact=1.000 protected=1.000 false_positive=1.000 baseline_boundary_recall=0.000 restored_boundary_recall=1.000 boundary_gain=1.000
```

JUnit XML 位于
`app/build/test-results/testOnlineDebugUnitTest/`，HTML 报告位于
`app/build/reports/tests/testOnlineDebugUnitTest/`。两者是可重建产物，不提交仓库。
