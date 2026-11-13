# 同传主链路实施记录

> 2026-11-12， BeyonLingo（debug 构建）。所有结论标注 observed / inferred。

## 本轮完成

按 WAYFINDER 的决策，落地**同声传译主链路**（模型先用 assets 内的 14M int8，云下载后置）。

### 新增文件

| 文件 | 职责 |
|---|---|
| `app/src/main/java/.../data/asr/AudioRecorder.kt` | 16kHz / 单声道 / PCM16 麦克风采集，100ms 分块，采集线程只搬运 |
| `app/src/main/java/.../data/asr/AsrEngine.kt` | sherpa-onnx 流式封装，产出 `Partial` / `Final`，端点检测用 `EndpointConfig` |
| `app/src/main/java/.../data/asr/WavUtil.kt` | WAV 解析，供测试回放 |
| `app/src/main/java/.../vm/SimultViewModel.kt` | 字幕状态机 + 翻译编排 |
| `app/src/main/java/.../ui/screen/SimultScreen.kt` | 全屏双语字幕 UI |
| `docs/transgull-simult-reference.md` | 翻译鸥方案拆解（前一轮产出） |

### 改动

- `app/build.gradle.kts`：sherpa-onnx AAR 依赖、`abiFilters = [arm64-v8a, x86_64]`
- `AndroidManifest.xml`：`RECORD_AUDIO`、`FOREGROUND_SERVICE_MICROPHONE`
- `MainActivity.kt`：`Routes.SIMULT` + 导航
- `TranslateScreen.kt`：顶栏麦克风入口（`Icons.Default.Mic`）

## 状态机设计（参考翻译鸥）

```
音频 chunk → AsrEngine.accept()
   ├─ Partial(text)  → 更新临时字幕（可被覆盖）
   └─ Final(text,id) → 追加一条 SubtitleLine(translating=true)
                        → 异步翻译 → 原地替换 translated
```

对应翻译鸥的 `subtitleRevision` / `cursorSegmentId` / `emitPendingFinalSegment`：
我们用 `segmentId` 定位、`translating` 标志表示待译、失败时 `failed=true` 并显示错误文案。

## 验证（observed）

构建：`:app:assembleDebug` **BUILD SUCCESSFUL**
环境：Android 模拟器 `st_test`（x86_64 / SDK 34，带窗口启动）

因模拟器无麦克风输入，加了 debug-only 的「[测试] 回放示例音频验证链路」按钮，
用 `assets/asr/test.wav` 按实时节奏（每 100ms 一个 1600 采样块）喂给引擎。

点击后 UI dump 实测结果：

```
T=[对我做了介绍那么我想说的是大家如果对我的研究感兴趣呢]   ← 原文，识别正确
T=[⚠ 引擎返回 HTTP 401，请稍后重试或切换引擎]              ← 译文位置，翻译层报错
T=[开始同传] / [清空] / [同声传译]
```

**结论**：识别、端点切段、字幕上屏、翻译状态机、失败降级全部工作。
401 是模拟器上 GLM 引擎未配 API Key 的数据问题（observed），非链路缺陷；
失败时 UI 正确显示错误而非崩溃。

## 过程中修正的错误（observed）

1. `AsrEngine.kt` 注释里写了 `assets/asr/*.int8.onnx` —— `/*` 在 Kotlin 块注释中开启嵌套注释，导致 `Unclosed comment`。改为纯文字描述。
2. `OnlineRecognizerConfig` **没有** `rule1MinTrailingSilence` 等顶层参数；端点规则实际在 `EndpointConfig(rule1/rule2/rule3: EndpointRule)` 里。经 `javap` 确认后改为嵌套构造。
3. `SimultViewModel` 漏 import `WavUtil`，补上。
4. 首次启动模拟器用了 `-no-window`，桌面不可见；后改为带窗口 `Start-Process` 启动。

## 补强（同日）

- **前台服务保活**：新增 `service/SimultForegroundService.kt`（`foregroundServiceType="microphone"`），
  在 `SimultViewModel.start()` 拉起、`stop()` 停止。解决 RECORD_AUDIO 作为 while-in-use 权限
  在 Activity 退后台后被系统掐掉的问题。Manifest 已声明服务。
- **端点阈值下调**：`rule2MinTrailingSilence` 从 1.2s → **0.6s**（`rule1` 2.4→2.0）。
  实测效果：同一段 5.6 秒音频从「整段一条」变成「两条分段」，字幕跟得上说话节奏，
  对应翻译鸥 `endpointLatencyAdjustmentLevel` 的可调思路。

补强后重新构建（BUILD SUCCESSFUL in 1m 41s）并在模拟器复测，UI dump 观测：

```
T=[对我做了介绍]
T=[⚠ 引擎返回 HTTP 401，请稍后重试或切换引擎]
T=[那么我想说的是大家如果对我的研究感兴趣呢]
T=[⚠ 引擎返回 HTTP 401，请稍后重试或切换引擎]
```

两段各自独立走完「识别 → 端点 → 上屏 → 翻译状态机」，分段正确、失败降级正常。

## 翻译引擎配通（同日，用户要求「让译文真正显示」）

### 诊断

401 的根因不是链路：模拟器上主页当前引擎是「智谱 GLM」但未配 API Key。
`SimultViewModel` 原本沿用 `settings.engineId`，所以一起报 401。

### 修正（SimultViewModel）

1. **引擎固定为免配置的 Yandex**（`EngineRegistry.DEFAULT_ENGINE_ID`），不受主页
   GLM 未配 Key 影响，开箱可用。
2. **目标语言改用 `settings.foreignLang`**（默认西语），不再用 `defaultTargetLang`。
   原因：ASR 输出是中文，沿用 defaultTargetLang=简体中文 等于「中文→中文」不翻译。
3. `source = LangEnum.Auto` 保持不变。

### 端点评测（observed，主机实测）

`POST https://translate.yandex.net/api/v1/tr.json/translate`
`?ucid={guid}&srv=android&format=text`，UA `ru.yandex.translate/3.20.2024`，
body `text=...&lang=...`：

| lang | 结果 |
|---|---|
| `en` | ✅ `{"code":200,"lang":"zh-en","text":["Introduced me, ..."]}` |
| `es` | ✅ `{"code":200,"lang":"zh-es","text":["Me presentó, ..."]}` |
| `zh-en` | ✅ 同上 |
| `auto-en` | ❌ HTTP 400（Yandex 不接受 `auto-*` 组合） |
| `zh` | ✅ 但返回原文（zh-zh） |

代码里 `src=auto` 时传单码 `tgt`（如 `en`/`es`），Yandex 会自行检测源语言，实测可用。

### 端到端复测（observed，模拟器）

UI dump：

```
T=[对我做了介绍]
T=[Me presentó]
T=[那么我想说的是大家如果对我的研究感兴趣呢]
T=[Entonces, ¿qué quiero decir es si todos están interesados en mi investigación?]
```

logcat：`AsrEngine: model loaded in 1985 ms`。

**全链路打通**：音频 → 本地 ASR → 端点切段 → 双语字幕 → 云端翻译 → 译文上屏。
截图：`../../simult-final.png`（原文小字在上 / 译文大字在下）。

## 第二批：模型云分发 + 标点 + 悬浮窗 + TTS + 视频/文档翻译

> 按用户指令顺序执行：先 2（模型与能力补强），再 3（功能扩展），最后 1（真机复测）。

### 2a 模型云分发（observed：编译通过 + 模拟器可进页面）

| 文件 | 职责 |
|---|---|
| `data/model/ModelManifest.kt` | manifest 数据结构 + `org.json` 手写解析（不引 kotlinx.serialization） |
| `data/model/ModelDownloader.kt` | 断点续传（HTTP Range）+ 逐文件 SHA-256 校验 + `.done` 标记 + manifest 本地缓存 |
| `data/asr/SherpaExternalLoader.kt` | 反射 `newFromFile` 的生产封装，失败返回 null（**不做错误的 assets 回退**） |
| `vm/ModelManagerViewModel.kt` | 拉 manifest / 下载进度 / 就绪状态 |
| `ui/screen/ModelManagerScreen.kt` | 模型管理页 |
| `docs/models-manifest.example.json` | 给用户放云空间的模板（含 asr / punctuation / tts 三类） |

设计要点：
- 每个模型文件**独立直链**，不做压缩包解压（Android 无 bz2 依赖，避免引第三方库）
- `filesDir/models/<id>/` 落盘，校验全通过才写 `.done`
- 云空间要求：HTTPS 直链 + 支持 Range；网盘类不行

### 2b 大模型切换
`AsrEngine` 增加 `externalPaths`（encoder/decoder/joiner/tokens 四绝对路径）。
非 null 时走反射加载，失败自动回退内置 assets 模型，并打日志
`model=assets-14M-zh(fallback)`。启动日志实测：`model=assets-14M-zh loaded in 1841 ms`。

### 2c 标点恢复
`data/asr/PunctuationEngine.kt`，模型 `sherpa-onnx-punct-ct-transformer-zh-en-int8`
（官方文档确认只需 `model.int8.onnx` 一个文件，72MB，中英双语，实测 0.01s/句）。
接入点：`handleUpdate` 的 Final 分支 —— **原文照旧无标点展示，补标点后的文本送翻译**，
既改善断句又不改变用户看到的识别结果。

### 3a 悬浮窗
| 文件 | 职责 |
|---|---|
| `data/SimultOverlayBus.kt` | 单例 StateFlow（保留最近 6 条），ViewModel → Service 单向 |
| `service/SimultOverlayService.kt` | TYPE_APPLICATION_OVERLAY + ComposeView，底部悬浮双语字幕 |

与现有 `FloatingWindowService`（640 行划词专用）分离，不混装。
需要 `SYSTEM_ALERT_WINDOW`；未授权时点「悬浮」直接跳系统授权页。

### 3b TTS
`data/asr/TtsEngine.kt`，Matcha zh-en（acoustic + vocoder + tokens），
`AudioTrack` MODE_STATIC 播放。默认**关闭**，同传页「朗读」按钮切换。

### 3c 视频翻译
| 文件 | 职责 |
|---|---|
| `data/video/Srt.kt` | 时间戳格式化 + 双语 SRT 渲染（原文行 + 译文行同一条目） |
| `data/video/VideoTranslator.kt` | SAF 落盘 → FFmpeg 抽 16k wav → 分块识别 → 逐段翻译 → 写 SRT |
| `vm/VideoViewModel.kt` / `ui/screen/VideoScreen.kt` | 选择/进度/Media3 播放/导出 SRT |

依赖：`dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7`（官方已退役，社区 drop-in，只取 audio 变体）
+ `androidx.media3:media3-exoplayer/ui:1.9.0`。实测依赖可解析。

**已知近似**：SRT 时间轴按「累计喂入样本数」推算，端点检测的静音尾部让上界略偏后；
精确到 token 级需 sherpa-onnx 时间戳输出，当前未启用。

### 3d 文档翻译
| 文件 | 职责 |
|---|---|
| `data/document/DocumentParser.kt` | txt / md / docx 解析，统一成 Block 序列 |
| `data/document/TextDecoder.kt` | BOM → 严格 UTF-8 试解 → GB18030 三级探测（中文 GBK 文本真实痛点） |
| `data/document/DocumentTranslator.kt` | 按块翻译 + 超长块按句切 + Markdown/纯文本导出 |
| `vm/DocumentViewModel.kt` / `ui/screen/DocumentScreen.kt` | 选择/进度/预览/导出 |

docx 用 `java.util.zip` + `XmlPullParser` **自研**（不用 POI：依赖 java.awt，Android 会崩，且 12MB+）。
明确丢弃 `w:delText`（修订删除内容）和 `w:instrText`（域代码），递归进入 `w:sdt`。
标题识别优先用 `w:outlineLvl`（中文模板的 styleId 常是 `a3`/`1` 而非 `Heading1`）。

### 模拟器验证（observed，UI dump）

```
主页顶栏：同声传译 / 图片取词 / 历史 / 更多 / 设置
更多菜单：视频翻译、文档翻译
视频翻译页：选择视频 / 开始翻译 / 说明文案
文档翻译页：选择文档 / 开始翻译 / 格式说明
同传页：开始同传 / 朗读 / 悬浮 / 模型 / 测试回放
模型管理页：manifest 地址输入 + 拉取模型列表
```

端到端回放复测（阈值 0.6s + 标点接入后）：

```
对我做了介绍                                    → Me presentó
那么我想说的是大家如果对我的研究感兴趣呢        → Entonces, ¿qué quiero decir es si todos están interesados en mi investigación?
```

logcat：`AsrEngine: model=assets-14M-zh loaded in 1841 ms`（未配云 manifest 时回退内置模型，符合预期）

APK 体积：177.1 MB（含 sherpa so + 模型 assets + ffmpeg-kit + media3）

## 第三批：真机 arm64 复测（1）

设备：realme RMX3823 / Android 16 / arm64-v8a，`4e1d6407`

### 验证结果（observed）

| 项 | 结果 | 证据 |
|---|---|---|
| arm64 安装 | ✅ | `adb install -r` Success |
| 冷启动 | ✅ 无崩溃 | topResumedActivity = MainActivity |
| 模型加载 | **857 ms** | `AsrEngine: model=assets-14M-zh loaded in 857 ms` |
| 文件回放链路 | ✅ | 两段识别正确 + 西语译文 |
| **真实麦克风** | ✅ 链路工作 | 见下方 |
| 前台服务保活 | ✅ | `ServiceRecord{...SimultForegroundService}` |
| 悬浮窗 | ✅ | 截图 `overlay-ok.png`，底部黑色双语字幕面板 |
| 录音权限 | ✅ | `RECORD_AUDIO: granted=true` |

真实麦克风实测（UI + 悬浮窗同步显示）：

```
原文：师叔打
译文：Pelea del tío
（partial）他今天可以比
```

**链路完全打通**：麦克风 → arm64 本地 ASR → 端点切段 → Yandex 翻译 → 全屏双语字幕 + 悬浮窗同步。

### 但识别质量印证了 issue #441 的结论

「师叔打」这类结果不是链路 bug，是 **14M 小模型的抗噪上限**：
真实对话距离 + 环境底噪下识别明显失准，与 sherpa-ncnn issue #441 的社区实测一致。
算力不是瓶颈（真机 RTF 远低于 1），**瓶颈是模型规格**。

→ 解法已就绪：模型管理页填 manifest 即可切到中英双语大模型（2a/2b 已完成）。

### 本轮修掉的两个真机独有 bug

1. **`ComposeView` 挂 WindowManager 崩溃**
   ```
   IllegalStateException: ViewTreeLifecycleOwner not found from androidx.compose.ui.platform.ComposeView
   ```
   根因：`ComposeView` 需要 ViewTreeLifecycleOwner / SavedStateRegistryOwner /
   ViewModelStoreOwner 三个 owner，纯 Service 里只继承了 `LifecycleService`（只提供第一个）。
   修法：**改用原生 View**（LinearLayout + TextView）渲染悬浮窗 —— 内容只有几行文字，
   原生更轻也更稳，彻底绕开 owner 问题。

2. **realme 不响应 `ACTION_MANAGE_OVERLAY_PERMISSION`**
   点击「悬浮」后停在原页，用户无从授权。修法：逐级降级
   `MANAGE_OVERLAY_PERMISSION` → `APPLICATION_DETAILS_SETTINGS` → 文案指引；
   并给 `startActivity` 加 `FLAG_ACTIVITY_NEW_TASK`。

### 另一个观察

首次点击顶部图标无响应 —— 启动页（SplashScreen）期间点击被吞。
不影响使用，但如果要打磨可在 splash 期间屏蔽交互或让 splash 更快退出。

## 第四批：双向同传（中文 ⇄ 西班牙语）+ 双向状态机

> 用户需求：**我说中文对方看西语字幕，对方说西语我要中文**。

### 为什么必须换 Whisper（observed，官方模型清单核实）

sherpa-onnx 官方**流式** ASR 模型的语言只有：中文、英文、中英双语、韩语、法语、孟加拉语、俄语。
**没有西班牙语。** 社区的 bookbot 西语模型是**音素级**（输出 IPA，不适合直接喂翻译）。

因此双向同传的识别侧改用 **Whisper**（多语种 + 自带语言检测 + 自带标点）。

### Whisper tiny 真机实测（RMX3823 / arm64-v8a / SDK 36）

模型：`sherpa-onnx-whisper-tiny` int8 = encoder 12.34MB + decoder 85.69MB + tokens 0.78MB = **98.8MB**

| 样本 | 输入 | 输出 | lang | RTF |
|---|---|---|---|---|
| 英语（自带） | AFTER EARLY NIGHTFALL… | After early nightfall, the yellow lamps would light up here and there the squalid quarter of the brothels. | en | 0.079 |
| **西语** | Buenos dias, como estas? Me alegra mucho verte hoy. | **Buenos días, ¿cómo estás? Me alegra mucho verte hoy.** | es | 0.066 |
| 中文 | 今天天气不错，我们下午一起去看电影吧。 | 今天天气不错我们下午一起去看电影吧 | zh | 0.063 |

关键收益：
- 西语识别**完全正确**，且自动补上正字法（`días` `¿cómo estás?`）
- `OfflineRecognizerResult.lang` 直接给出 en/es/zh —— **这就是双向方向路由的依据**
- Whisper 自带标点与大小写，补上了 Zipformer 缺标点的短板
- 模型加载 939ms；RTF 0.063–0.079，余量 12–15 倍

> 局限：测试音频是在线 TTS 合成音（干净、无口音、无底噪），真实人声会更差。
> 这只是**可行性下限**，真实场景需真人复核。

### 新增/改造的代码

| 文件 | 职责 |
|---|---|
| `data/asr/SherpaExternalLoader.kt` | 增加 `offlineRecognizerOrNull`（`OfflineRecognizer_newFromFile` 在 JNI 符号表中已核实） |
| `data/asr/WhisperEngine.kt` | OfflineRecognizer 封装，输出 `Result(text, lang)`；支持 assets / 外部路径双模式 |
| `data/asr/SimpleVad.kt` | 能量 VAD 切段（零依赖零体积，避免再加一个模型文件）；speechRms 0.012 / silence 650ms / minSpeech 320ms / maxSegment 15s |
| `data/asr/BidirectionalResolver.kt` | 左右语言槽 + 方向判定 + **UNKNOWN 继承上一轮**（照搬翻译鸥 `inheritMatchingRecognizingLanguage` 思路） |
| `data/asr/BidirectionalSession.kt` | 采集线程只做 VAD → Channel → 独立协程做识别+翻译（不阻塞采集） |
| `vm/SimultViewModel.kt` | `startBidirectional(left, right)`、事件分发、`SubtitleLine.side/directionLabel` |
| `ui/screen/SimultScreen.kt` | `DirectionBar` 语言对选择（我方 ⇄ 对方）、字幕带「我方/对方」标签 |

### 云分发全链路真机验证（observed）

用 `adb reverse tcp:8000 tcp:8000` + 开发机 `python -m http.server 8000` 模拟云空间：

```
拉取模型列表 → 找到 1 个模型（v1） / Whisper tiny 多语种 / asr-offline · 3 个文件
点下载 → 传输 90MB → SHA-256 校验通过 → 「已就绪」
设备侧文件：encoder.onnx 12937772 / decoder.onnx 89855401 / tokens.txt 816730 / .done
```

模型加载（**反射 newFromFile 从云端绝对路径**）：
```
SherpaExternalLoader: OfflineRecognizer loaded from file, ptr=-5476376622775525056
WhisperEngine: whisper=Whisper tiny 多语种（中/西/英） loaded in 487 ms
```

方向路由：
```
BidiSession: skip unresolved: lang='en' text='I'm a Jew'      ← 不匹配中/西且无上一轮 → 跳过
BidiSession: seg#0 lang=zh side=LEFT inherited=false asr=198ms text='就黏黏'
```

真实对话音频下的实际输出（截图 `bidi-cn.png`）：

| 中文识别 | 西语译文 |
|---|---|
| 他都不知道我們現在的 | Él ni siquiera sabe lo que somos ahora |
| 是个神价的马上的信也到期的跟咖了 | Tiene un precio divino, y si no lo crees de inmediato, caducará. |
| 我相信期待的鱗 | Creo en la escala de expectativas |

中文识别质量**明显优于** 14M Zipformer（繁体的「們」「鱗」都识别对了）。

### 本批修掉的三个真 bug

1. **`NetworkOnMainThreadException`** —— `fetchManifest` 用同步 OkHttp `execute()`，
   在 `viewModelScope`（Main 调度器）里直接调用。改为 `suspend` + `withContext(Dispatchers.IO)`。
   （`download()` 本来就有 `flowOn(Dispatchers.IO)`，无需改。）
2. **manifest 带 BOM 解析失败** —— PowerShell `Set-Content -Encoding UTF8` 会写 BOM，
   `org.json` 直接抛异常且 message 为 null（极难排查）。
   修法：`ModelManifestParser.parse` 先剥 `\uFEFF`，落盘时也剥掉；
   并加 `require(clean.startsWith("{"))` 让错误可读。
3. **异常 message 为 null 时 UI 显示「拉取失败：null」** —— 加 `describe(t)` 兜底。

### 待验证

- **西语侧的真实语音识别质量**：本批只验了中文侧 + TTS 合成的西语。
  需要真人说西语复核（Whisper tiny 在真人西语上的表现）。
- **面对面 UI**：翻译鸥有 `faceToFace` 模式（手机放桌上两人各看一半），本项目尚未实现，
  当前是单一列表 + 方向标签。

## 第五批：Whisper base + 简繁统一 + 云空间交付包

### tiny vs base 真机对比（RMX3823 / arm64 / SDK 36）

测试样本：英语真人（sherpa-onnx 自带）+ **西语真人电话对话**（Google Translatotron
的 Fisher-Grounded ST 公开样本，有 ground truth）+ 中/西 TTS 合成音。

| 样本 | Ground truth | tiny int8 (98.8MB) | base int8 (153.2MB) |
|---|---|---|---|
| ES real 1 | Y usted no sabrá a menos que usted pida. | ❌ `y usted nos habrá a menos que usted pida` | ✅ `y usted no sabrá a menos que usted pida` |
| ES real 2 | Deja de hacer eso. | ❌ `They have the access.`（**lang 误判 en**） | ✅ `Dejad de hacer eso.`（lang=es） |
| ES real 3 | nadar con delfines | ⚠️ `Nadar con del fines` | ✅ `Nadar con del finas` |
| EN real | (已知全文) | ✅ 正确 | ✅ 正确 |
| ZH tts | 今天天气不错… | ✅ 简体 | ⚠️ **繁体**「今天天氣不錯我們…」 |
| ES tts | Buenos dias, como estas? | ✅ `¿cómo estás?` | ⚠️ `¿` → `,` |

**决定用 base 的理由**：双向同传的方向路由**完全依赖 `lang` 字段**，
而 tiny 在真人西语上会把西语误判成英语（样本 2）——这会直接导致翻译方向错误。
base 三条全部识别为西语。RTF 0.132–0.168，仍有 6 倍余量。体积代价 +55MB。

### 简繁统一（`data/asr/ZhSimplify.kt` + `ZhSimplifyTable.kt`）

Whisper base 倾向输出繁体，tiny 倾向简体，混在一起字幕风格不一致。
翻译鸥也有这层处理（DEX 实证 `openccApplied=true, businessLanguage=`）。

**不引 OpenCC 依赖**：Android 侧移植要么词典占数 MB，要么长期无人维护。
改为内置对照表：

- 数据源：OpenCC 官方 `TSCharacters.txt` + `TSPhrases.txt`
- 只收 **BMP 内**的一对一映射（扩展区汉字在 UTF-16 占 2 个 char，Kotlin 无法用 `Char` 表示）
- 词级表优先（整词替换），剩余单字走字级表
- 多义字（乾/後/髮…）按 OpenCC t2s 规则取第一个候选
- 生成脚本：`tools/gen-zh-simplify.py`（可重跑）

**踩到的坑**：
1. `mapOf()` 放 2965 个条目 → `<clinit>` 超过 JVM 64KB 方法上限，
   编译期报 `Method too large`。解法：拆成 6 个 500 条的小 map 再 fold 合并。
2. 首版用 `TSCharacters.txt` 直接过滤，把**「简→繁」反向条目**也收进来了，
   导致某些 key 长度异常。解法：改用 t2s 语义（只取繁体 key）。

单测 6 条全过（`app/src/test/.../ZhSimplifyTest.kt`）。

转换只在 `lang` 以 `zh` 开头时执行，避免误伤日文汉字（ja 也用汉字）。

### 云空间交付包

工作区 `cloud-models/`（**不在 git 里**，模型体积大）：

```
cloud-models/
├── manifest.json                    ← 模板，URL 用 YOUR-CLOUD 占位
├── whisper-base/  encoder.int8.onnx 29.1MB + decoder.int8.onnx 130.7MB + tokens.txt 0.8MB
└── whisper-tiny/  encoder.int8.onnx 12.9MB + decoder.int8.onnx 89.9MB  + tokens.txt 0.8MB
```

manifest 里两个模型都是 `kind="asr-offline"`（key: encoder/decoder/tokens）。
App 侧 `refreshExternalModel` 取第一个就绪的 asr-offline，因此 **base 排在前面**。

### 端到端验证（本地 HTTP 模拟云空间）

```
拉取 manifest → 找到 2 个模型 → 下载 base 153MB → SHA-256 校验 → 已就绪
设备侧：encoder.onnx 29120534 / decoder.onnx 130672026 / tokens.txt 816730 / .done
WhisperEngine: whisper=Whisper base int8 多语种（推荐，153MB） loaded in 665 ms
BidiSession: seg#0 lang=zh side=LEFT inherited=false asr=1212ms
             text='你不是十九吗二十四怎么二十呢不是十九吗去领悟去领悟'   ← 简体，转换生效
```

## 未完成 / 下一步

- **真机 arm64 复测**（当前只在 x86_64 模拟器跑通）
- **模型云分发**：技术前提已验证（反射 `newFromFile` 可加载绝对路径），待实现下载器 + SHA-256 校验 + 版本管理
- **换大模型**：14M 中文模型在真实麦克风下抗噪差（issue #441），需换中英双语大模型
- **加标点模型**：当前输出无标点，影响译文断句
- **悬浮窗模式**：决策已定（全屏+悬浮双模式），尚未实现
- **TTS**：决策已定（Matcha），尚未实现
- **视频翻译 / 文档翻译**：决策已定，尚未实现
- **前台服务**：已完成（SimultForegroundService，microphone 类型）；回放结束后 `dumpsys` 查不到记录属预期（stop 已停止），运行中未抛 `startForegroundService` 异常即为生效

## 关键提醒

`AsrEngine` 目前从 **assets 相对路径**加载模型。若要接云下载，必须走
`wayfinder/asr-engine-selection.md`「模型外部加载验证」里的反射方案
（`Unsafe.allocateInstance` + private `newFromFile`），否则绝对路径会 native 崩溃。
