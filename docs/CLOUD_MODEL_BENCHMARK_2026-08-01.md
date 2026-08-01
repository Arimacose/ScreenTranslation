# TranslateGemma / Hy-MT2 Q4 云端服务预筛 — 2026-08-01

## 结论

Online edition 的项目托管云端固定采用 **Hy-MT2 1.8B Q4_K_M**，公开模型 ID
为 `hymt2-1.8b-q4`。本轮同机、同运行时、同 80 条英中/日中套件显示：

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

## 托管链路实现

Online APK 保留两个 provider：

1. **项目托管云端**：固定 `hymt2-1.8b-q4`，无需用户 API Key，只支持译为
   简体中文；公开 HTTPS Base URL 由 `managedCloudBaseUrl` Gradle 属性或
   `SCREEN_TRANSLATION_MANAGED_CLOUD_BASE_URL` 环境变量注入。
2. **用户 API**：用户填写 Base URL/API Key，应用通过 `GET /models` 拉取模型并
   选择，再调用 `/chat/completions`。切换到托管模式不会删除该配置或 Keystore 密钥。

项目网关位于 `services/managed-cloud-gateway/`。它固定公共模型/提示/解码参数，
从服务端环境读取私有上游 URL 与可选上游密钥，限制正文大小、每 IP 请求数与并发，
且不记录 OCR 原文或译文。真实部署还需 TLS、区域/留存说明、DDoS/账单限额、监控和
容量测试。

真实 Hy-MT2 GPU 服务 → 网关 → Android 等价 JSON 的桌面集成烟测已通过：英中
`190.656 ms`，日中 `155.696 ms`，网关指标为 2 成功、0 失败、0 拒绝。

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

## 尚待验收

- 未部署长期公网域名，因此公网 RTT、TLS、区域、云 GPU 账单与并发容量仍待最终
  主机确定后测试；
- 按用户要求，本轮暂停 Online 真机测试；未安装、启动或抓包 APK；
- 真机恢复前必须先通知用户，再执行托管模式/用户 API 模式切换、Keystore 保留、
  真实 HTTPS、latest-wins、日志脱敏和长时间运行验收。
