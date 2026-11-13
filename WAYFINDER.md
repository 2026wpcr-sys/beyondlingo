# WAYFINDER — 克隆翻译鸥（Android）

## Destination

在 `beyondlingo`（Kotlin + Compose，MIT）基础上补齐三条功能线，产出可安装到手机的 APK：

1. **同声传译** — 麦克风持续听 → 双语字幕 → 可选译文朗读
2. **视频翻译** — 本地视频抽音频 → ASR → 翻译 → 双语字幕
3. **文档翻译** — 导入文档 → 分段翻译 → 导出

不引入自建服务器，不复制翻译鸥的计费体系。

## Notes

- 地基：`beyondlingo/`，35 个 Kotlin 文件，已有双引擎翻译、OCR、划词无障碍服务、悬浮窗、Room 历史
- 参考：`transgull-re/REVERSE-ENGINEERING-REPORT.md`（翻译鸥技术栈、费率、数据流实测结论）
- 领域约束：优先离线（隐私 + 零成本）；翻译复用现有引擎，`glm-4-flash` 免费额度足够验证
- 每个会话先对齐 Destination 再选票据
- 参考 skill：`reverse-engineer-anything`（需要再核对翻译鸥实现时）

## Decisions so far

- [ASR 引擎选型](wayfinder/asr-engine-selection.md) — sherpa-onnx v2.0.1 + 两遍式（流式 Zipformer 出草稿 → SenseVoice 端点重打分）；AAR 在 GitHub Release 不在 Maven Central；无单模型覆盖中英日韩；全功能模型 ≈560MB 必须按需下载。**真机实测 RTF=0.018、首字 15.5ms、堆 6MB（14M int8 模型，RMX3823/Android 16）→ 算力不是瓶颈，体积才是**
- [TTS 引擎选型](wayfinder/tts-engine-selection.md) — sherpa-onnx Matcha zh-en（RTF 0.391，支持 generateWithCallback 流式合成）；日/韩无预训练模型，用系统 TTS 兜底
- [文档解析](wayfinder/document-parsing.md) — MVP 只做 txt/md/docx，docx 用 zip+XmlPullParser 自研（体积 ≈0，不用 POI）；pdf 推迟到 MVP+1；导出只做 .txt/.md
- [翻译层方案](wayfinder/translation-layer.md) — **纯混合（A）**：ASR/TTS 离线，翻译走云端 LLM；不做端侧翻译、不做离线模式；音频不出设备是核心卖点，UI 必须标明「翻译需联网」
- [视频管线](wayfinder/video-audio-extraction.md) — FFmpegKit 已于 2025-01 退役，用 `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` 作 drop-in 替代（需换精简变体）；播放用 Media3 ExoPlayer + side-loaded SRT；**双语靠合并成单 SRT 实现，零自定义渲染**；烧录重编码代价大，默认不做
- [视频输出形态](wayfinder/subtitle-output-form.md) — App 内播放 + 导出 .srt，**不做硬字幕烧录**；视频只读不重编码，FFmpeg 仅用于抽 16k wav
- [同传界面](wayfinder/realtime-ux.md) — **全屏双语字幕（默认）+ 悬浮窗（可切）双模式**；复用已有 `FloatingWindowService`；引擎只跑一份，切模式时 ASR 会话必须无感延续

## Not yet specified

- 同传长时间运行的功耗与热控策略（连续听 30 分钟以上是否可行）
- 是否保留同传历史记录，以及本地存储形态
- 双语字幕的滚动策略（逐句替换 vs 累积滚动）
- 是否需要术语词库（翻译鸥有这个功能）
- 多语种自动检测的触发时机（每段检测 vs 会话级锁定）
- **断网降级**：翻译请求失败时显示原文、排队重试、还是直接中断同传
- **模型分发**：中英模型 198MB + Matcha 93MB，走 APK 内置还是首启下载，以及下载失败/存储不足的处理 —— **技术前提已验证可行**（见 `asr-engine-selection` 的「模型外部加载验证」：反射 `newFromFile` 可从绝对路径加载），剩下的是下载器与版本管理设计

## Out of scope

- 在线会议同传 —— 需要 LiveKit 服务器 + 会议机器人
- 电话翻译 —— 需要 SIP 中继 / PSTN 号码
- 直播悬浮字幕 —— Android 16 MediaProjection 限制多，收益低
- 计费与积分系统 —— 本克隆版不收费
- 服务端翻译模型 —— 翻译鸥的模型在服务端，不可复刻
- 声音克隆 / 个性化音色
