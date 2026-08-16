# 路线图

路线图表达方向而非发布日期承诺。具体实现以对应 issue、设计讨论和真机证据为准。

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
- [ ] 在实验特性开关后实现 Bergamot JNI 适配器，并与 PP-OCRv6 联合做内存/温升验收
- [x] 在翻译前遮蔽、翻译后恢复版本号、邮箱、金额、日期和 URL
- [x] 让原文与译文面板可复制
- [x] 在不保存历史的前提下增加长结果展开与限高滚动
- [x] 恢复 OCR 丢失的关键标点并按语义分句（[#40](https://github.com/Arimacose/ScreenTranslation/issues/40)；[质量基准](OCR_PUNCTUATION_QUALITY_2026-08-09.md)）
- [x] 以类型化 `TranslationProviderProfile` 分离 Lite、Full、Online 能力与中间档门禁（[#41](https://github.com/Arimacose/ScreenTranslation/issues/41)）
- [x] 为固定公开文本建立质量回归夹具与人工评分规范（[#46](https://github.com/Arimacose/ScreenTranslation/issues/46)）
- [x] 增加模型管理页面：大小、状态、下载与删除
- [x] 增加“关于/开源许可/隐私”应用内页面（v2.2；[#45](https://github.com/Arimacose/ScreenTranslation/issues/45)）

验收重点：长句不因面板省略而丢失可访问性；翻译后端的数据流和归属清晰可选。

## v0.3 — 兼容性与自动化

- [ ] 扩大 Android 16 ROM 验证矩阵（当前里程碑明确延期）
- [x] 增加隔离的 Online x86_64 contributor 构建（v2.2；[#43](https://github.com/Arimacose/ScreenTranslation/issues/43)）
- [x] 增加 Android instrumentation 与 UI 自动化测试（[#42](https://github.com/Arimacose/ScreenTranslation/issues/42)）
- [x] 覆盖旋转、锁屏、投影撤销和任务移除状态机（并入 [#42](https://github.com/Arimacose/ScreenTranslation/issues/42)）
- [ ] 增加内存压力与系统回收进程后的专项状态恢复回归
- [x] 建立可重复的性能、温升和持续运行基准（不保存 OCR 文本/屏幕内容）
- [x] 增加分块差分、块级稳定门和全屏持续识别模式（Experimental；15 分钟与生命周期
  真机门禁绑定于 [#38](https://github.com/Arimacose/ScreenTranslation/issues/38)）
- [x] 以 Apple 风格默认候选、MIUIX、Material 3 + 可选 Monet 替换旧 UI，并统一服务悬浮层视觉令牌
- 三套 UI 的签名 Release / HyperOS 真机视觉与交互结果由
  [#47](https://github.com/Arimacose/ScreenTranslation/issues/47) 与对应 Release notes
  保存，避免为回写验收状态移动已经固定的发布 commit。
- [x] 为三个 edition 生成并发布 CycloneDX SBOM（v2.2；[#44](https://github.com/Arimacose/ScreenTranslation/issues/44)）

## v2.2 — 任务优先与可验证发布

- [x] 将模型准备迁移到 Activity 之外的可恢复 WorkManager 任务，并以模型坐标与文件
  identity 阻止陈旧“已就绪”状态（[#70](https://github.com/Arimacose/ScreenTranslation/issues/70)）
- [x] 首页按当前任务给出唯一下一步动作，并统一首次运行和启动前置检查
  （[#71](https://github.com/Arimacose/ScreenTranslation/issues/71)）
- [x] 增加可关闭的空闲快捷通知和四态 Quick Settings Tile
  （[#72](https://github.com/Arimacose/ScreenTranslation/issues/72)）
- [x] 应用内离线信任中心（[#45](https://github.com/Arimacose/ScreenTranslation/issues/45)）
- [x] Online x86_64 contributor 构建（[#43](https://github.com/Arimacose/ScreenTranslation/issues/43)）
- [x] Lite/Full/Online CycloneDX SBOM（[#44](https://github.com/Arimacose/ScreenTranslation/issues/44)）

代码与自动化门禁完成后，上述项目仍需按各 issue 的验收条目通过签名 Release、目标真机
和 GitHub Actions 证据，才进入发布关闭状态。

## v2.3 — 连续阅读、区域预设与可访问性验收

- [x] 全屏控制条支持暂停/继续、显示/隐藏、阅读模式、字号、透明度与停止，并让密集页面中
  所有 block 的完整原文/译文保持可发现（[#73](https://github.com/Arimacose/ScreenTranslation/issues/73)）
- [x] 框选结果面板可独立拖动和原子冻结；横竖屏分别保存仅含归一化坐标的区域预设
  （[#74](https://github.com/Arimacose/ScreenTranslation/issues/74)）
- [x] 三套视觉样式补齐语义、遍历顺序、状态 announcement、48 dp 目标、触觉反馈与
  日/夜/横竖屏/font scale 渲染矩阵（[#75](https://github.com/Arimacose/ScreenTranslation/issues/75)）

代码完成后仍以最终 `main` 的签名 acceptance Artifact、同一小米 15 Pro / HyperOS 真机
区域与全屏各 15 分钟报告、TalkBack 操作和三 edition smoke 作为关闭 milestone 与公开发布门禁。

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
