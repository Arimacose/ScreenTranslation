# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的结构，并使用
[Semantic Versioning](https://semver.org/lang/zh-CN/) 管理公开版本。

## [Unreleased]

### Added

- 新增由 WorkManager 承载、按 edition/语言/模型 revision/SHA-256 稳定去重的模型准备任务；
  支持应用退出后继续、网络约束、空间预检、暂停/继续/取消、`.part` 续传，以及下载速度和
  ETA 展示。完成状态仅在当前模型文件 identity 再次匹配时成立。
- 首页改为任务优先的信息架构：先展示当前动作与唯一主按钮，再按翻译、模型、权限、外观
  排列设置；启动前统一检查语言、Online 配置、模型、通知、悬浮窗和 MediaProjection。
- 增加可关闭的空闲快捷通知与 Quick Settings Tile；Tile 区分未就绪、就绪、运行和暂停，
  并继续遵守每次启动都由用户确认的 MediaProjection 流程。
- 增加应用内“关于与信任中心”，离线展示 edition/OCR/翻译后端 identity、模型版本、
  Lite/Full/Online 数据流、Apache-2.0、第三方 notices、隐私与安全文档，且不显示 API Key。
- 增加仅 Online 可用的 `onlineContributor` x86_64 Debug 构建，供 Android 16 模拟器验证
  PP-OCRv6/ONNX Runtime；所有 Release edition 继续仅打包 `arm64-v8a`。
- 为 Lite、Full、Online 生成独立 CycloneDX 1.5 SBOM，并把三份 SBOM 纳入签名
  acceptance Artifact、`SHA256SUMS` 和 GitHub Release 的逐字节 promotion 门禁。

### Verification

- Lite、Full、Online Debug 单元测试共同覆盖任务门控、模型任务 identity、速度/ETA、空间
  预检、快捷通知开关、Tile 四态、edition identity 与信任文档一致性。
- CI 同时构建 `onlineContributor`，断言其包含 x86_64 ONNX Runtime，并拒绝任何 Release
  APK 混入 x86_64 库；SBOM 校验器拒绝缺少固定组件、非法 SHA 或本机路径泄漏的清单。

## [2.1.0] - 2026-08-12

### Added

- 新增可重复的 Android 16 持续识别采集器，按固定间隔记录 PID、PSS/RSS/VmHWM、
  CPU ticks、电池/thermal 状态、服务/MediaProjection/虚拟显示连续性，以及帧、脏块、
  Bitmap、OCR、翻译和自适应间隔计数；采集器默认不保存 OCR 文本或屏幕内容，落盘
  Logcat 只保留目标 PID 或明确包含目标包名的行。
- 新增独立动态屏幕夹具，按固定周期切换大块、小块和 `BATON/BATMAN` 短字幕，用于验证
  小范围变化检出、旧译文移除、稳定门、缓存和标签位置。
- 新增全屏译文标签碰撞规避：依次避开状态栏/导航安全区、顶部控制条和已经放置的标签，
  优先显示在原文上方；找不到合法空位时跳过该标签，避免叠字遮挡。

### Changed

- 全屏增量签名从 Bitmap-first 改为直接读取 RGBA `Image.Plane`：按 stride 批量抽取
  1/4 亮度缩略图，先完成脏块判断，仅在出现脏块或计划复核块时才构造整屏 Bitmap。
- 5 分钟同机 A/B 中，新的静态路径把位图构造次数/字节减少 83.58%，单核等效 CPU
  降低 15.01%，PSS 峰值降低 12.52%，RSS 中位数降低 15.48%；117 个静态帧合计避免
  约 2.01 GiB 位图分配。完整边界见
  [`docs/PP_OCRV6_SUSTAINED_BENCHMARK_2026-08-11.md`](docs/PP_OCRV6_SUSTAINED_BENCHMARK_2026-08-11.md)。
- 签名 Artifact promotion 的设备证据从固定 Issue #47 扩展为版本无关的验收 schema：
  证据评论列出全部 accepted issues，工作流逐个验证已关闭、里程碑等于发布版本、
  验收时长/温控/失败计数与报告 SHA-256 字段，并继续冻结评论正文哈希。

### Verification

- 固定 10 张 PP-OCRv6 夹具保持 9/10 exact、CER `0.0617%`、WER `0.2809%`；已知唯一
  差异仍为 Unicode em dash 归一化成 ASCII hyphen。
- Benchmark 预验收在小米 15 Pro / Android 16 / HyperOS
  `OS3.0.304.0.WOBCNXM` 上完成 5 分钟 A/B；两轮均为单一 PID、21/21 服务/投影/虚拟
  显示连续、Thermal status 0、0 OCR/处理错误、0 crash/ANR/OOM。
- 最终三 edition 签名 Release 的区域模式与全屏模式各 15 分钟耐久、生命周期恢复和
  精确 APK 哈希由 Issues #38/#39 的最终证据评论与 Release notes 绑定；为保持
  immutable promotion，最终真机数据不会在验收后反向移动已固定的 source commit。

## [2.0.0] - 2026-08-11

### Added

- 以 Apple 风格（默认）、MIUIX 和 Material 3 三套可切换视觉语言替换原有平铺式
  UI，并统一主页面、模型管理、Online 设置及两类悬浮翻译层。
- 为 Material 3 增加可关闭的 Monet 动态取色与固定色板回退路径。
- 当前语对模型准备成功后，“准备模型”变为灰色“已就绪”且不可点击；切换 UI 导致
  Activity 重建时仍保留该就绪状态。

- 新增保留框选区域为默认值的“全屏增量覆盖”Experimental 模式：`3×6` 亮度分块
  差分、自然变化后的强制复核、PP-OCRv6 文字框坐标、跨帧 block 稳定门、单活跃
  翻译队列与静态画面最长 2 秒自适应采样；译文标签优先显示在对应原文框上方。
- 新增模型管理页，按 edition 展示下载状态、实际/预期大小和固定 revision，可直接
  发起当前语言模型准备，并在服务停止后删除完整或部分下载权重。
- 新增 URL、邮箱、日期、金额与版本号的翻译前占位保护/翻译后恢复，以及区域结果面板
  的独立“复制原文”“复制译文”按钮。
- 新增真实 localhost HTTPS MockWebServer 契约测试，覆盖 Online 401 不重试、429
  读取 `Retry-After` 后一次有界重试，以及 90 秒生成超时不重试。
- 新增英文 README、可复现的 30 秒仓库 UI 预览 GIF、v2.0.0 milestone 和公开路线图
  issues。
- 新增 API 36 instrumentation 回归，覆盖屏幕共享授权、旋转/锁屏恢复、
  授权撤销、停止与通知栏重启的状态机。
- 新增 Lite/Full/Online/Benchmark 的类型化 `TranslationProviderProfile`，统一声明语言
  路由、输入限制、模型存储、取消/关闭语义、逐路由性能与 attribution；HY-MT2 STQ
  中间档候选改由 CI 重算的 canonical PR/gitlink/ancestry/artifact admission 记录约束，
  当前缺测与 Open PR 均保持 fail-closed，未进入 factory 或模型下载路径。
- 新增 `2026.08-public-v2-original-references` 英→中/日→中公开质量回归发布：
  每方向 48 条、逐条来源/许可、原创参考审计、语料 SHA-256、category/tag
  protected 硬门、Lite/Full/Online 全字段阈值、候选哈希绑定盲评，以及由生产
  Kotlin policy/parser 执行生成的 Online 失败证据。

### Changed

- 签名发布改为两阶段 immutable Artifact promotion：先从当前 `main` 构建并真机验收带
  source SHA 的 30 天 Artifact，再由独立 publish operation 核验 tag、run、Artifact digest、
  Issue #47 设备证据、APK/AAB 与许可证后原样上传，避免 tag 后重构建替换已验收字节。
- 把 HyperOS 私有省电设置键与设置页 Intent 隔离到 `VendorAdapter` 边界；当前仍只提供
  `HyperOsVendorAdapter`，本阶段不扩展其他 ROM。
- Online 的 401/403、429 与超时提示分别指向密钥/权限/余额、限流退避和服务/模型
  响应时长，避免统一显示泛化网络错误。
- `OcrEngine.Recognition` 增加归一化文字行几何与置信度，同时保持既有 text/blocks
  调用兼容。
- 中间档 HY-MT2 1.25-bit STQ 仅保留为评估候选；上游 runtime 支持、
  可运行模型哈希、逐语言路由质量、应用内延迟、Release 整进程内存与
  30 分钟热稳定证据未同时满足时，不进入 edition 选择、工厂或下载路径。

### Fixed

- Online 设置页的 Base URL 与 API Key 输入提示改为不会在 1.30 倍大字体下截断的短标签；
  地址示例、密钥留空保留规则及自动补充 `/models`、`/chat/completions` 的说明保留在
  可换行帮助文字中。
- 全屏块翻译队列的暂停状态现在只能显式恢复；新任务提交或稳定状态重置不会在屏幕
  隐藏期间意外重启请求。
- 当前语言模型准备成功后，主页面操作变为灰色不可点击的“已就绪”；语言对、Online
  配置或模型文件状态变化后才恢复准备入口。
- OCR 标点恢复改为保守的高置信句尾/成对闭合策略，去除英文长度、
  任意 `-ed/-ing`、中文名词和日语裸 `する` 等高误报信号；密集受保护文本
  线性化处理并增加 65,536 字符有界回退。
- UI 风格重建、进程冷启和模型管理返回主页时，“已就绪”不再依赖
  `savedInstanceState` 猜测；Lite/Full 重算完整 SHA-256，Online 绑定规范化
  URL、模型、同意版本与 Keystore 密钥身份，删除、替换或轮换后立即失效。
- Lite 与 Full 的主页、模型管理共用各自的完整 SHA-256 验证器；Lite 已验证完整模型
  优先于遗留 `.part` 状态，Full 1.13 GB 权重校验只在 Activity `STARTED` 且识屏服务
  未运行时执行，避免后台或推理期并发全量读取。
- 模型就绪后台任务改由同步 generation/resource controller 统一管理安装、取消、释放
  与一次性完成，消除生命周期切换时的双重关闭、资源泄漏和旧结果发布窗口。

### Verification

- JVM、Lint、R8 Release、APK/AAB、API 36 instrumentation 与 GitHub CI 构成
  v2.0.0 的非真机门禁。三 edition 最终签名 Release 与三套 UI 短时真机矩阵
  通过 acceptance run、Issue #47 证据正文和 Release notes 绑定；全屏覆盖的长时功耗、
  温升与持续运行保留到 v2.1.0 的 Issue #38/#39，不纳入本版本声明。
- 翻译回归工具对 96 条公开语料、126 个关键检查和 9 个 Online 失败场景完成确定性
  harness smoke；参考回放、调用方自报 provenance 和未经认证的人工评分均保持
  fail-closed，正式候选仍需 gate-owned 或可外部验签的模型输出证据与可信评分身份。

## [0.3.1] - 2026-08-03

### Changed

- 将 ONNX Runtime Android 从 1.26.0 更新到 1.28.0；小米 15 Pro / Android 16
  固定 PP-OCRv6 夹具输出保持逐字一致，三轮 A/B 的 OCR 中位延迟降低 2.77%，
  进程峰值内存中位数增加约 4.6 MiB。

### Fixed

- PP-OCRv6 benchmark 改为从 ORT 运行时读取真实版本，并同步更新架构说明、
  第三方归属材料及 APK/AAB 许可证路径，避免依赖版本与发布材料漂移。

## [0.3.0] - 2026-08-02

### Added

- 实现独立 `com.screentranslation.app.online` edition：PP-OCRv6 在设备端识别，
  通过用户配置的 OpenAI-compatible HTTPS Chat Completions 服务翻译整段 OCR 文本。
- 增加 Online 设置页、服务/模型配置摘要、保存并测试入口，以及 600 ms 去抖、
  750 ms 最小间隔、单活跃/单 latest pending 的可取消翻译协调器。
- 将 Online 单元测试、Release Lint/R8、APK/AAB、后端隔离和许可证检查纳入
  CI 与签名发布工作流。
- 增加 TranslateGemma/Hy-MT2 的 80 条同 GPU 质量、性能与流量报告；受长期托管
  硬件条件限制，Online Release 最终只保留用户自带 API Key 的 BYOK 链路。
- 停止识屏后保留常驻快捷通知，可在目标应用内重新请求屏幕共享并直接框选。

### Security

- API Key 使用 Android Keystore AES-256-GCM 加密，输入框不回显；更换服务主机
  后必须重新确认 OCR 文本数据流。
- Online 仅接受无账号密码/query/fragment 的 HTTPS 地址，关闭重定向与 OkHttp
  隐式重试，不记录认证头、OCR 原文、请求正文或译文。

### Fixed

- 移除译文悬浮窗的 `FLAG_SECURE`，使用户发起的系统截图和录屏可以保留译文
  面板；MediaProjection 帧仍在 OCR 前按实际悬浮层坐标遮蔽，避免译文回灌。
- 框选最小边长从 64dp 降为 32dp，移除全屏黑色遮罩，并在框选期间接管返回、
  预测返回与系统边缘手势，避免目标应用被误返回；框选态只保留顶部单条提示，
  不再显示底部重复说明或控制面板。
- 读取 HyperOS 3 的 `MILLET_NO_RESTRICT_APP` 精确包名列表，区分已设/未设
  「无限制」，不再只依赖 AOSP 电源白名单猜测厂商策略。
- 针对在线翻译长尾，将 connect/write/read/call 超时调整为 `15/30/75/90 s`，
  `SocketTimeoutException` 不再自动重试；网络等待期间先显示稳定 OCR 原文与
  “正在请求在线翻译…”，避免把 OCR 等待误判为请求超时。

## [0.2.1] - 2026-07-31

### Changed

- 应用版本提高为 `versionCode 3` / `0.2.1-lite` / `0.2.1-full`。

### Fixed

- 修复 Lite 停止 Bergamot 服务时关闭 stderr 管道可能触发
  `InterruptedIOException` 未捕获异常并导致进程崩溃的问题；正常关闭期间的
  reader 中断现在按预期回收，非关闭期的读取错误仍记录告警。

## [0.2.0] - 2026-07-31

### Added

- 增加隔离的 `benchmark` 变体、固定识屏夹具和 OCR/翻译质量评分工具。
- 增加 PP-OCRv6 small + ONNX Runtime Android 候选实现及小米 15 Pro 真机基准。
- 增加固定版本、校验哈希的 Firefox Translations 英中模型获取与 Bergamot 基准流程。
- 增加 Lite edition：保留 `com.screentranslation.app`，使用 Bergamot
  英→中直译和日→英→中级联。
- 增加可并存安装的 Full edition：使用独立 `.full` 包名和
  Hy-MT2 1.8B Q4_K_M，所有界面及发布资产均标记为 Experimental。
- 增加 Hy-MT2 Q4 / 1.25-bit、ML Kit、Bergamot 多语言真机基准。
- 增加 Online edition 的 Base URL、API Key、固定翻译提示、可取消请求、
  Android Keystore 与隐私边界设计；通过同源 `GET /models` 自动获取可用模型，
  由用户从下拉列表选择，避免手工输入模型 ID。

### Changed

- 将 PP-OCRv6 small + ONNX Runtime Android 从隔离基准提升为 Debug/Release
  默认 OCR；官方检测/识别权重固定提交并在构建时校验 SHA-256。
- ML Kit Text Recognition 仅保留在 `benchmark` 变体中作为 v0.1.0 对照，
  Lite / Full 均使用 PP-OCRv6-small。
- ML Kit Translate 退到 `benchmark` build type；Lite / Full APK 只携带各自
  的 Bergamot 或 llama.cpp runtime。
- 应用版本提高为 `versionCode 2` / `0.2.0-lite` / `0.2.0-full`。
- GitHub Actions 改为分别测试、Lint、R8、签名和发布两个 edition，并验证
  v0.1.0 证书连续性、16 KiB 对齐、包内容和 SHA-256。

### Fixed

- 修复 HyperOS 已在系统设置选择「无限制」，应用却因 AOSP Doze 白名单状态
  显示“未放行”的误判。界面现在分别处理 Android 后台限制、AOSP 电源白名单
  与 HyperOS 厂商策略。
- 修复 Lite / Full 模型下载在完整 `.part` 文件处发送 EOF Range 并持续收到
  HTTP 416 的恢复阻断；完整文件改为先校验后接管，损坏文件从零重下。
- 修复 Full 中多个 Engine 竞争同一进程级 llama.cpp 状态的问题；运行时改为
  引用计数租约，并由 JNI owner token 阻止旧实例释放新实例。
- 为 Bergamot 子进程 READY 与翻译响应增加期限，停止时先终止进程再异步回收
  管道，避免阻塞前台服务的清理路径。

### Security

- Bergamot 模型同时校验压缩文件与解压文件的长度和 SHA-256；Full 固定
  Hy-MT2 revision、长度和 SHA-256。
- 两个 edition 都把翻译模型保存到应用专属 `no_backup` 目录，模型权重不进入
  APK/AAB。
- 完整的 common/Lite/Full 第三方许可证、版权 notices、固定模型坐标与
  Bergamot MPL 对应源码说明进入 edition-specific `assets/licenses/`，并随
  Release 提供统一 `THIRD-PARTY.zip`。
- 发布 workflow 对 Lite / Full 的签名证书、后端隔离和模型权重缺失做硬断言。

## [0.1.0] - 2026-07-26

### Added

- Android 16 / API 36 屏幕区域捕获、ML Kit OCR、端侧翻译与悬浮结果面板。
- 小米 15 Pro / HyperOS 真机验收记录。
- Apache-2.0 许可、贡献规范、行为准则、治理、安全、隐私和支持政策。
- GitHub Actions 构建、Lint、CodeQL、依赖审查和签名发布工作流。
- Dependabot 的 Gradle 与 GitHub Actions 每周更新策略。

### Changed

- 将构建说明从单台开发机路径调整为通用 JDK 17 / Android SDK 36 流程。
- 明确区分“屏幕内容在设备端处理”和 ML Kit SDK 的诊断/使用元数据网络行为。

### Security

- 发布签名从源码中分离，通过本地忽略文件或 GitHub Actions secrets 注入。
- Issue 与 PR 模板要求删除截图、OCR 文本和日志中的敏感内容。

## 发布规则

发布时将 `Unreleased` 中的内容移动到 `## [x.y.z] - YYYY-MM-DD`，同步提高
`versionCode` 与 `versionName`，完成真机验收后再创建 `vx.y.z` 标签。

[Unreleased]: https://github.com/Arimacose/ScreenTranslation/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/Arimacose/ScreenTranslation/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/Arimacose/ScreenTranslation/compare/v0.3.1...v2.0.0
[0.3.1]: https://github.com/Arimacose/ScreenTranslation/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/Arimacose/ScreenTranslation/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/Arimacose/ScreenTranslation/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/Arimacose/ScreenTranslation/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Arimacose/ScreenTranslation/releases/tag/v0.1.0
