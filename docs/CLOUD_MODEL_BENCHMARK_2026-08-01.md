# TranslateGemma / Hy-MT2 Q4 云端服务预筛 — 2026-08-01

## 结论

本报告记录曾评估的项目托管候选。模型对比中 **Hy-MT2 1.8B Q4_K_M** 综合优于
TranslateGemma 4B Q4；但长期 GPU、公开网关与运营条件未落实，因此最终 Online
Release 未采用任何项目托管模型，只保留用户 API（BYOK）。同机、同运行时、同 80 条
英中/日中套件显示：

- Hy-MT2 的英中 BLEU 比 TranslateGemma 高 `5.831`，日中高 `6.233`；
- Hy-MT2 的关键检查分别多通过 `2` 和 `4` 项；
- Hy-MT2 raw 中位服务端耗时低约 `52.4–52.5%`，生成速度约为 `2.02x`；
- Hy-MT2 单服务 GPU 显存增量约 `1.33 GiB`，TranslateGemma 约 `2.69 GiB`；
- Hy-MT2 单请求应用层 JSON 约 `1.15 KiB`，TranslateGemma 约 `2.95 KiB`；
- TranslateGemma 日中稳定复现“清除缓存，而不是应用数据”的语义反转和车门陈述
  变命令，当前不进入项目托管链路。

TranslateGemma 的 chrF++ 略高：英中 `+0.768`、日中 `+0.085`。这没有抵消
Hy-MT2 在 BLEU、关键语义、人审、速度、显存和许可可操作性上的综合优势。

本结论只比较本地已有的两个 Q4 权重，不代表 TranslateGemma 12B/27B 或
Hy-MT2 7B/30B 的云端结果。TranslateGemma 官方覆盖 55 种语言；Hy-MT2
官方列出 33 种语言。若产品语言范围超出现有 UI 的英、日、韩、法、德、西班牙语，
需为新增语种另开质量门槛。

## 候选与固定版本

| 候选 | 权重 | 大小 | SHA-256 | 许可 |
|---|---|---:|---|---|
| Hy-MT2 | `tencent/Hy-MT2-1.8B-GGUF@1cd5208700acedef4ef93019b6cfc148b8522d45` / Q4_K_M | 1,133,080,448 B | `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699` | Apache-2.0 |
| TranslateGemma | `google/translategemma-4b-it@10042cb0e6e7fdce748996a71dc3dc432a4e0c89` / Q4_K_M | 2,489,909,376 B | `69a5b429f745810b89599d550915370410a475c7cfc73bd25f72266d6f34526e` | Gemma Terms of Use |

运行时固定为 `llama.cpp b10181` / commit `caa596ab3`。两个服务都使用：

- RTX 5060 Laptop GPU 8,151 MiB；
- `--ctx-size 2048 --parallel 1 --threads 8 --threads-batch 8`；
- `--gpu-layers 99`；
- temperature 0、top-k 1、top-p 1、seed 42、最多 256 个输出 token；
- 保留模型各自已验证的 repeat penalty：Hy-MT2 `1.05`、TranslateGemma `1.0`；
- localhost HTTP；网络耗时另行建模，不混入模型服务端耗时。

Hy-MT2 使用项目已验收的官方 multilingual-to-Chinese 提示；TranslateGemma
使用其专用语言字段模板和 raw completion。两者都使用同一批项目自编参考译文与
关键语义检查。

上游资料：

- [Tencent Hy-MT2](https://github.com/Tencent-Hunyuan/Hy-MT2)
- [Hy-MT2 1.8B Q4 GGUF](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF)
- [TranslateGemma 4B](https://huggingface.co/google/translategemma-4b-it)
- [llama.cpp b10181](https://github.com/ggml-org/llama.cpp/releases/tag/b10181)

## 质量与时间

### Raw 整段翻译

| 语言 | 模型 | BLEU | chrF++ | 关键检查 | 中位 | P95 |
|---|---|---:|---:|---:|---:|---:|
| 英→中 | **Hy-MT2 Q4** | **52.862** | 36.702 | **37/54** | **119.695 ms** | **215.119 ms** |
| 英→中 | TranslateGemma Q4 | 47.031 | **37.470** | 35/54 | 251.713 ms | 515.337 ms |
| 日→中 | **Hy-MT2 Q4** | **47.790** | 34.048 | **38/52** | **101.306 ms** | **152.436 ms** |
| 日→中 | TranslateGemma Q4 | 41.557 | **34.133** | 34/52 | 213.342 ms | 351.767 ms |

### 语义人审

- TranslateGemma：`ja_technical_cache_not_data` 把“清除缓存而不是应用数据”反转；
  `ja_system_train_door` 把“右侧车门将打开”译成“请打开右侧的门”；
  `system_approximate_location` 把“精确位置关闭”弱化为“位置未确定”。
- Hy-MT2：上述缓存/应用数据关系保持正确；数字、版本、退款条件、剂量和否定整体
  更稳定。主要不足仍是把 `beta` 标识符泛化为“测试版”，以及部分日语谚语直译。
- 自动关键检查中存在保守正则假阴性。例如 Hy-MT2 的“请删除缓存数据，而不是应用
  数据”语义正确，但没有匹配仅接受“清除/擦除缓存”的正则；选型同时使用人审结果。

## 性能与资源

| 项目 | Hy-MT2 Q4 | TranslateGemma Q4 |
|---|---:|---:|
| 模型文件 | 1.06 GiB | 2.32 GiB |
| GPU 总显存：服务停止后 | 2,285 MiB | 2,283 MiB |
| GPU 总显存：套件结束时 | 3,642 MiB | 5,033 MiB |
| 推定服务显存增量 | **1,357 MiB** | 2,750 MiB |
| 生成速度中位 | **约 192 token/s** | 约 94.8 token/s |
| 套件结束功耗瞬时样本 | 74.81 W | 91.66 W |
| 二次加载至监听（系统页缓存存在） | 1.159 s | 17.282 s |

显存值是 Windows WDDM 下同一时刻 GPU 总占用的停止前后差值，不是 Linux
容器的逐进程 NVML 数值。单 slot 开发实例可把 Hy-MT2 的 `4 GiB VRAM` 视为
最低验证档；生产建议从 `8 GiB VRAM` 起步，为 KV cache、并发 slot、驱动和监控
留余量。TranslateGemma 即使单 slot 也更适合 `8 GiB VRAM` 或更高配置。
多并发容量必须在最终 Linux/容器/GPU 上重新压测，不能按本次单 slot 结果线性外推。

## 网络负载与响应时间

HTTP 载荷使用与客户端等价的 UTF-8 JSON 测得，不含 TCP/IP、HTTP/2 或 TLS
头部：

| 语言 | 模型 | 中位上行 | 中位下行 | 合计 |
|---|---|---:|---:|---:|
| 英→中 | Hy-MT2 Q4 | 376.5 B | 794.5 B | **1,171 B** |
| 日→中 | Hy-MT2 Q4 | 392.5 B | 782.0 B | **1,174.5 B** |
| 英→中 | TranslateGemma Q4 | 669.5 B | 2,344 B | 3,013.5 B |
| 日→中 | TranslateGemma Q4 | 688.5 B | 2,340 B | 3,028.5 B |

若画面持续变化并命中 Online 的 750 ms 最小间隔，理论上最多约 80 请求/分钟：

- Hy-MT2 应用层正文约 `12.5 kbit/s`；
- TranslateGemma 应用层正文约 `32.2 kbit/s`。

因此带宽不是主要约束，RTT、丢包、TLS 首连和服务排队才决定体验。保持 HTTP
连接复用时，可用近似式：

```text
用户看到译文的时间 ≈ 600 ms 去抖 + 等待最小间隔 + 1×RTT + 排队 + 模型耗时
```

建议托管节点面向目标用户保持稳定 `RTT < 100 ms`；带宽预留 `100 kbit/s/活跃设备`
已明显高于正文负载。最终公网验收需分别测 Wi-Fi、5G/4G、首次 TLS 连接、连接复用、
1% 丢包和并发排队。本轮 localhost 结果不替代这些测量。

## 用户 API · DeepSeek V4-Flash

在暂缓项目托管链路后，使用临时测试 Key 对用户 API 模式做了主机网络验证。Key
仅进入测试进程环境和 Bearer 请求头，结果文件与 Git 变更均不包含 Key。

- Base URL：`https://api.deepseek.com`；
- `GET /models`：HTTP 200，`304.9 ms`，返回 `deepseek-v4-flash` 与
  `deepseek-v4-pro`；
- Chat Completions：HTTP 200，响应模型与所选 `deepseek-v4-flash` 一致；
- 请求提示、`temperature: 0`、消息分层与 Online 客户端保持一致。

DeepSeek V4 默认启用思考。10 条英中/日中代表性样例的 A/B 结果说明，纯翻译应
显式关闭它：

| 模式 | 成功 | 中位延迟 | 平均延迟 | completion token | reasoning token |
|---|---:|---:|---:|---:|---:|
| 当前默认思考 | 10/10 | 2,300.8 ms | 2,436.0 ms | 1,794 | 1,646 |
| `thinking: disabled` | 10/10 | **870.8 ms** | **896.3 ms** | **138** | **0** |

关闭思考后中位延迟降低约 `62.1%`。逐句人审未发现由此引入的明显退化，文学长句、
数字、否定逻辑、技术文本和日语歧义样例均保留核心语义。因此客户端只在官方
`api.deepseek.com` 与 `deepseek-v4-*` 组合下增加该字段。

完整 40 条英中、40 条日中套件均为 40/40 HTTP 成功：

| 语言 | BLEU | chrF++ | 关键检查 | 中位 | P95 | 最大值 |
|---|---:|---:|---:|---:|---:|---:|
| 英→中 | **66.922** | **47.182** | **44/54** | 976.55 ms | 1,323.6 ms | 1,827.7 ms |
| 日→中 | **59.327** | **40.070** | 40/52 | 954.55 ms | 1,599.3 ms | 66,490.4 ms |

与同套件既有结果相比，DeepSeek V4-Flash 的英中 BLEU 比 Hy-MT2 Q4 高
`15.037`，日中高 `13.784`；关键检查也分别高 `8` 与 `1` 项。它的质量明显领先，
但网络中位延迟仍约一秒，而且日中出现一次 `66.49 s` 的无 HTTP 错误长尾。产品端
仍需依赖取消、latest-wins、超时与有限重试，不能把平均延迟当作稳定上界。

人工发现的主要质量弱项是日语谚语：`石の上にも三年` 被直译为“石上坐三年”，
`雨降って地固まる` 被译为“雨过地皮干”。自动关键检查还会把日期从 ISO 写法改成
中文日期视为失败，因此检查数需和人工语义审查一起使用。

### 2026-08-02 Android 16 真机补充验收

在 Xiaomi 15 Pro / Android 16 / HyperOS `OS3.0.304.0.WOBCNXM` 上，Online
debug 候选通过真实 DeepSeek 用户 API 验收：

- `GET /models` 返回 `deepseek-v4-flash` 与 `deepseek-v4-pro`，模型 ID 原样进入下拉
  列表；
- Base URL、所选模型和 Keystore 加密密钥状态在覆盖安装、强制停止与冷启动后保持；
- 设置页真实翻译成功；
- Chrome `example.com` 完成
  `MediaProjection -> PP-OCRv6 -> DeepSeek V4-Flash -> 悬浮译文`，4 秒证据窗口内
  已显示完整中文结果；
- logcat 中临时密钥、OCR 原文和译文均为 0 命中，应用私有目录中临时密钥明文也为
  0 命中；测试结束后已删除设备端密文和 Keystore 密钥。

首次请求发现 OkHttp 连接池在主线程清理会触发 Android 16
`NetworkOnMainThreadException`。清理切换到专用后台 executor 后，相同真机流程通过，
PID 保持且运行时 fatal 为 0。完整记录见
[`DEVICE_TEST.md`](DEVICE_TEST.md#2026-08-02-online-byok--api-真机验收)。

本机忽略目录：

```text
app/build/api-tests/deepseek-v4-flash-full-2026-08-01
```

| 文件 | SHA-256 |
|---|---|
| `en-zh-diverse-v2.json` | `232bc2ceae4a993c09dd03bbfeb6175cd99d1aebd9b85f9d2baf36750a53bf87` |
| `en-zh-diverse-v2.scores.json` | `fa66aecb71ca0dfbc7d352bd42cd7affdad727e2527d47a64e2617ffc90ceb95` |
| `ja-zh-diverse-v1.json` | `1e7950bf77a7b9e91c94744ab56a9a1f6bc89720759bd8a5af3c101c36ce6ec9` |
| `ja-zh-diverse-v1.scores.json` | `f0b4af51d90fb4c471427aeea26a208f4724776a6bf0c45c7632e2fb7d006029` |

## 发布决策

早期原型曾实现固定 `hymt2-1.8b-q4` 的本地网关，并通过真实 Hy-MT2 GPU → 网关 →
Android 等价 JSON 的桌面烟测：英中 `190.656 ms`，日中 `155.696 ms`。该结果只证明
协议原型可运行，不代表长期公网服务已具备发布条件。

发布前已删除应用中的托管 provider、网关地址 BuildConfig、CI/Release 网关门禁和
仓库内网关服务。Online APK 现在只有一条链路：用户填写 Base URL/API Key，应用经
`GET /models` 拉取并选择模型，再调用 `/chat/completions`。本报告与忽略目录中的
历史数据继续用于模型研究，不构成当前产品功能。

## 产物与复现证据

仓库内的 `tools/model-benchmark/run_hymt2.py` 与
`tools/model-benchmark/run_translategemma.py` 会复用同一 Android 导出的 fixture
JSON，并记录模型 revision、文件哈希、llama.cpp 版本、解码参数与运行范围。固定 GPU
服务启动参数和两种 runner 的命令见
[`tools/model-benchmark/README.md`](../tools/model-benchmark/README.md#cloud-q4-prescreen-hy-mt2-vs-translategemma)。

本机产物根目录：

```text
D:\DevCache\Benchmarks\cloud-model-prescreen-2026-08-01
```

| 文件 | SHA-256 |
|---|---|
| `hymt2/translation-hymt2-q4-en-zh-gpu.scores.json` | `3ab1bfca7da2b1390933723544a93e642d3e080c158f48c1f8845ece6f64850a` |
| `hymt2/translation-hymt2-q4-ja-zh-gpu.scores.json` | `ebdcaf51afce1587ae388ffb0a27af8832af5db9c1ccde079467dd7086bc7026` |
| `hymt2/traffic-raw-80.json` | `7553fe8aa2948a7af24895bc8e8623a338cefaa9af5af16f89797f7716e78032` |
| `translategemma/translation-translategemma-q4-en-zh-gpu.scores.json` | `9a1e7971ebac8025705f96e197fa6f3b12b4c4fdede3f931a247a93f85506782` |
| `translategemma/translation-translategemma-q4-ja-zh-gpu.scores.json` | `73581ddfe80fa6391d5ae108bad4f94847a8d79c64ee6891d8819eb46e0eb6ee` |
| `translategemma/traffic-raw-80.json` | `b65c439f682a5bd76609ea8fcaabeb9c2500e543c213b76dfb830e1765e4e6af` |
| `managed-gateway/integration-smoke.json` | `e4215c5a20c046a796a30b1d39388af1837d7d49d9054e6b89a187b5804ab2f8` |

## 剩余验收

- DeepSeek 用户 API 的真机 HTTPS、Keystore、日志脱敏与单次识屏闭环已经完成；
- 真机 latest-wins 压力、401/429/timeout UI、代理抓包、签名 Release 与长时间运行
  继续作为独立验收项。
