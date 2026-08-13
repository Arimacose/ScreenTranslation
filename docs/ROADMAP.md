# 路线图

路线图表达方向而非发布日期承诺。具体实现以对应 issue、设计讨论和真机证据为准。

当前从 v2.1.1 到 v2.4.0 的体验审计、依赖顺序、完成定义与 GitHub Issue 映射见
[`PRODUCT_EXPERIENCE_ROADMAP.md`](PRODUCT_EXPERIENCE_ROADMAP.md) 和跨版本总控
[#80](https://github.com/Arimacose/ScreenTranslation/issues/80)。

## v0.1 — Android 16 可验证基线

- [x] MediaProjection 用户授权与前台服务生命周期
- [x] 可框选识别区域
- [x] v0.1 Latin、中文、日文、韩文 ML Kit OCR 基线
- [x] ML Kit 端侧翻译与模型预下载
- [x] OCR 稳定门、单飞处理和悬浮层自反馈遮蔽
- [x] 小米 15 Pro / HyperOS 真机验收
- [x] 开源许可、治理、隐私、安全、CI 和发布基线

## v0.2 — 翻译质量与长文本体验

- [x] 建立 ML Kit、PP-OCRv6、OPUS-MT 与 Firefox Translations 的固定夹具基准
- [x] 将 PP-OCRv6 small + ONNX Runtime 提升为 arm64 Debug/Release 默认 OCR
- [x] 以 plane-first 签名降低 PP-OCRv6 持续路径的 Bitmap 分配、CPU 与内存，并把
  15 分钟温升验收绑定到签名 Release 证据（[#39](https://github.com/Arimacose/ScreenTranslation/issues/39)；
  [A/B 报告](PP_OCRV6_SUSTAINED_BENCHMARK_2026-08-11.md)）
- [x] 构建 Firefox Translations / Bergamot Android arm64 运行时并完成真机比较
- [x] 将固定 Bergamot ARM64 runner 接入 Lite 生产链路，并与 PP-OCRv6 完成真机内存/温升验收
- [x] 在翻译前遮蔽、翻译后恢复版本号、邮箱、金额、日期和 URL
- [x] 让原文与译文面板可复制
- [x] 在不保存历史的前提下增加长结果展开与限高滚动
- [x] 恢复 OCR 丢失的关键标点并按语义分句（[#40](https://github.com/Arimacose/ScreenTranslation/issues/40)；[质量基准](OCR_PUNCTUATION_QUALITY_2026-08-09.md)）
- [x] 以类型化 `TranslationProviderProfile` 分离 Lite、Full、Online 能力与中间档门禁（[#41](https://github.com/Arimacose/ScreenTranslation/issues/41)）
- [x] 为固定公开文本建立质量回归夹具与人工评分规范（[#46](https://github.com/Arimacose/ScreenTranslation/issues/46)）
- [x] 增加模型管理页面：大小、状态、下载与删除
- [ ] 增加“关于/开源许可/隐私/数据流”应用内页面（v2.2；[#45](https://github.com/Arimacose/ScreenTranslation/issues/45)）

验收重点：长句不因面板省略而丢失可访问性；翻译后端的数据流和归属清晰可选。

## v0.3 — 兼容性与自动化

- [ ] 扩大 Android 16 ROM 验证矩阵（当前里程碑明确延期）
- [ ] 增加 x86_64 调试构建或独立模拟器 flavor（v2.2；[#43](https://github.com/Arimacose/ScreenTranslation/issues/43)）
- [x] 增加 Android instrumentation 基线测试（[#42](https://github.com/Arimacose/ScreenTranslation/issues/42)）
- [ ] 以注入固定帧覆盖“捕获 → OCR → 翻译 → Overlay → 复制 → 停止”完整旅程，
  并增加 golden 与 macrobenchmark（v2.4；[#79](https://github.com/Arimacose/ScreenTranslation/issues/79)）
- [x] 覆盖旋转、锁屏、投影撤销和任务移除状态机（并入 [#42](https://github.com/Arimacose/ScreenTranslation/issues/42)）
- [ ] 增加内存压力与系统回收进程后的专项状态恢复回归
- [x] 建立可重复的性能、温升和持续运行基准（不保存 OCR 文本/屏幕内容）
- [x] 增加分块差分、块级稳定门和全屏持续识别模式（Experimental；15 分钟与生命周期
  真机门禁绑定于 [#38](https://github.com/Arimacose/ScreenTranslation/issues/38)）
- [x] 以 Apple 风格默认候选、MIUIX、Material 3 + 可选 Monet 替换旧 UI，并统一服务悬浮层视觉令牌
- 三套 UI 的签名 Release / HyperOS 真机视觉与交互结果由
  [#47](https://github.com/Arimacose/ScreenTranslation/issues/47) 与对应 Release notes
  保存，避免为回写验收状态移动已经固定的发布 commit。
- [ ] 生成可发布的依赖清单或 SBOM（v2.2；[#44](https://github.com/Arimacose/ScreenTranslation/issues/44)）

## v2.1.1–v2.4 — 产品体验计划

- **v2.1.1：** 低风险 UI/错误信息/触控修补与发布可发现性（[#68](https://github.com/Arimacose/ScreenTranslation/issues/68)、[#69](https://github.com/Arimacose/ScreenTranslation/issues/69)）；
- **v2.2.0：** 可恢复模型准备、任务优先首次使用、通知/Quick Settings 与应用内信任页（[#70](https://github.com/Arimacose/ScreenTranslation/issues/70)–[#72](https://github.com/Arimacose/ScreenTranslation/issues/72)、[#45](https://github.com/Arimacose/ScreenTranslation/issues/45)）；
- **v2.3.0：** 全屏阅读模式、区域预设和三套 UI 可访问性矩阵（[#73](https://github.com/Arimacose/ScreenTranslation/issues/73)–[#75](https://github.com/Arimacose/ScreenTranslation/issues/75)）；
- **v2.4.0：** 混合脚本、小字 OCR、Online 批处理与完整 E2E/性能门禁（[#76](https://github.com/Arimacose/ScreenTranslation/issues/76)–[#79](https://github.com/Arimacose/ScreenTranslation/issues/79)）。

## v2.x 跨设备稳定性边界

- 冻结全局唯一 `applicationId`、应用名称和长期发布签名；
- 稳定的数据迁移和版本策略；
- 在后续版本中扩展到至少两类 Android 16 ROM 的重复验收；v2.0.0 明确只承诺
  小米 15 Pro / 当前 HyperOS 基线；v2.1.0 仍不扩大 ROM 支持范围；
- 权限、隐私、签名和安全响应流程经过实际演练；
- 核心捕获状态机具备自动化回归；
- 翻译质量模式与用户预期有明确区分；
- 公开 API 和设置格式进入兼容性承诺。

## 当前非目标

- 静默或后台自动开始屏幕共享；
- 使用无障碍服务绕过 MediaProjection 用户授权；
- 绕过 `FLAG_SECURE`、DRM 或企业工作资料策略；
- 默认保存截图、OCR 历史或译文历史；
- 在缺少实测证据时声明支持 Android 15 及以下；
- 以单一厂商私有接口替代 Android 公共能力。
