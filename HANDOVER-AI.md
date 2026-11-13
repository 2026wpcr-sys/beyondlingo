# 交接文档（给接手的 AI 工具）

> 最后更新：2026-11-13
> 当前版本：**1.5.3**（versionCode 9），工作区有未提交改动（TTS 通用化，见 §9）

这份文档的目标：**让接手的 AI 在 10 分钟内知道这个项目是什么、能跑什么、卡在哪、怎么改**。
不重复 README 的产品介绍，只讲工程事实与决策依据。

---

## 1. 项目是什么

**无界译 AI（BeyondLingo）** —— Android 端翻译工具，核心功能是**对话翻译**
（双方轮流说，说完一段自动识别 + 翻译 + 上屏 + 朗读）。

对标产品：**翻译鸥**（`com.transgull.translator`，已逆向分析，见
`docs/transgull-simult-reference.md`）。

关键定位：**识别可在本地跑（隐私/离线），翻译走云端**，模型按需下载。

---

## 2. 当前真实状态（别被旧文档误导）

### ✅ 真机验证通过（realme RMX3823 / Android 16 / arm64 / serial `4e1d6407`）

```
说话 → Groq whisper-large-v3 识别 → 翻译引擎 → 译文上屏 → 可选朗读
```

实测日志（可作为基线对照）：

```
BidiSession: segment 76800 samples (4000 ms) rms=0.0466 peak=0.455
BidiSession: seg#0 fixed=zh via=cloud side=LEFT asr=1315ms text='今天天气很好,我们出去走走。'
BidiSession: translate begin seg#0 '...' ChineseSimplified-> Spanish engine=custom
TranslateEngine: endpoint=https://open.bigmodel.cn/api/paas/v4/chat/completions model=glm-4-flash
BidiSession: translate ok seg#0 1234ms 'Hoy hace buen tiempo, salgamos a dar un paseo'
```

### ⚠️ 未验证 / 有坑的地方

| 项 | 状态 |
|---|---|
| **流式识别（边说边出字）** | ❌ 未实现。Whisper 是非流式模型，必须等一段说完 |
| **本地 TTS（Matcha）** | ⚠️ 代码在，但 manifest 里**没有 tts 条目**，所以点了「朗读」用本地档是**静音** |
| **云端 TTS** | ⚠️ 代码刚写完（Azure / OpenAI 兼容），**未在真机验证**。用户 Azure 注册失败，改用 OpenAI 兼容档 |
| **西语真人对话** | ⚠️ 只测过 Translatotron 电话录音样本和用户口述，没有真实双人西语对话验证 |
| **视频翻译 / 文档翻译** | ⚠️ 1.4.0 实现过，之后大改识别层，**回归未验证** |
| **悬浮字幕** | ⚠️ 代码在，realme 权限需手动授予，未完整回归 |

---

## 3. 技术栈

| 项 | 版本 |
|---|---|
| AGP | 8.13.0 |
| Kotlin | 2.2.20 |
| Gradle | 8.14.3 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Java | 17 |
| Compose BOM | 2025.10.00 |
| sherpa-onnx | 2.0.1（本地 AAR，`app/libs/sherpa-onnx-2.0.1.aar`） |
| OkHttp | 4.12.0 |
| Room | 2.8.2 |

**没有版本目录（version catalog）**，依赖全部写在 `app/build.gradle.kts`。

---

## 4. 架构（三个可替换的引擎层）

```
┌─ 对话翻译页 (ui/screen/SimultScreen.kt)
│    两个语言按钮：点哪个 = 谁在说（语种显式指定，不猜）
│
├─ 会话编排 (data/asr/BidirectionalSession.kt)   ★核心
│    AudioRecorder(16k mono) → SimpleVad 切段 → 识别 → 翻译 → onEvent
│    forcedSpeakSide 非空 = 对话模式（语种固定）
│
├─ ① 识别层（可换）
│    ├ WhisperEngine.kt        本地 Whisper（base/small，int8）
│    │    ★transcribeFixedLanguage()：每种语言一个独立 recognizer
│    └ CloudAsrEngine.kt       云端 Groq（whisper-large-v3）
│
├─ ② 翻译层（可换）data/engine/
│    ├ EngineRegistry.kt       注册表，DEFAULT = yandex
│    ├ YandexEngine.kt         免 Key 直连（★海外 IP 可能被拒）
│    └ CustomEngine.kt         OpenAI 兼容（智谱/DeepSeek/Groq/…）
│
├─ ③ 朗读层（可换）
│    ├ TtsEngine.kt            本地 Matcha（sherpa-onnx）
│    └ CloudTtsEngine.kt       云端：Azure / OpenAI 兼容  ★刚写完未验证
│
└─ 模型分发
     ├ BuiltInModels.kt        内置 manifest（云端 URL 清单，非模型文件）
     ├ ModelDownloader.kt      Range 续传 + SHA-256 + .done 标记
     └ ModelManagerScreen.kt   下载 / 设为当前 / API Key 配置
```

**设计原则：三层都可替换，每层都不假设其它层是什么。**
识别层不关心翻译用什么，翻译层不关心识别是本地还是云端。

---

## 5. 关键文件清单（改代码先看这些）

| 文件 | 职责 | 改动风险 |
|---|---|---|
| `data/asr/BidirectionalSession.kt` | 会话编排：切段→识别→翻译→事件 | **高**，改错会导致译文不上屏 |
| `data/asr/SimpleVad.kt` | 能量 VAD 切段 | **高**，参数直接决定短句能否识别 |
| `data/asr/WhisperEngine.kt` | 本地识别 + 按语言 recognizer 缓存 | 中 |
| `data/asr/CloudAsrEngine.kt` | Groq 云端识别（multipart 上传 WAV） | 中 |
| `data/asr/CloudTtsEngine.kt` | 云端朗读（Azure / OpenAI 兼容） | 中 |
| `data/model/BuiltInModels.kt` | 内置模型清单（JSON 常量） | 中，SHA-256 错了下载会失败 |
| `vm/SimultViewModel.kt` | 对话页状态机 + 引擎装配 | **高**，超 600 行，逻辑密集 |
| `ui/screen/SimultScreen.kt` | 对话翻译 UI | 低 |
| `data/SettingsStore.kt` | DataStore 全部配置 | 低（加字段即可） |
| `docs/transgull-simult-reference.md` | 翻译鸥逆向结论 | 只读参考 |

---

## 6. 构建与发布（完整流程）

### 环境路径（本机已配好）

```powershell
$env:JAVA_HOME = 'E:\AndroidToolchain\jdk-21.0.12.1+1'
$adb    = 'E:\AndroidToolchain\sdk\platform-tools\adb.exe'
$aapt   = 'E:\AndroidToolchain\sdk\build-tools\35.0.0\aapt.exe'
$ffmpeg = 'E:\ffmpeg\bin\ffmpeg.exe'
```

- 真机 serial：`4e1d6407`（realme RMX3823）
- 模拟器：AVD `st_test`，需设 `$env:ANDROID_AVD_HOME='E:\AndroidToolchain\avd'`
- **模拟器是 x86_64，release 只有 arm64 → release 装不上模拟器，调试必须用 debug 版**

### 构建

```powershell
cd <project>
.\gradlew.bat :app:assembleDebug      # 调试（含 x86_64，可装模拟器）
.\gradlew.bat :app:assembleRelease    # 发布（arm64 only，约 87MB）
.\gradlew.bat :app:testDebugUnitTest  # 单测（11 条，改 manifest/转换表后必跑）
```

### 发版流程

1. 改 `app/build.gradle.kts`：`versionCode +1`、`versionName`
2. 改 `BeyondLingoApp.kt`：`const val VERSION`
3. `assembleRelease` → 产物 `app/build/outputs/apk/release/app-release.apk`
4. 复制到 `dist/BeyondLingo-AI-<ver>.apk`，算 SHA-256 存 `.sha256`
5. `git commit` + `git tag -a v<ver>` + `git push origin main && git push origin v<ver>`
6. GitHub Release（`gh` **未安装**，用 API + `curl.exe`）：

```powershell
# token 从 git credential 取，不要硬编码
$cred = ("protocol=https`nhost=github.com`n`n" | git credential fill)
$token = ($cred | Where-Object { $_ -like 'password=*' }).Substring(9)

# 建 release
POST https://api.github.com/repos/2026wpcr-sys/beyondlingo/releases
# 传资产（★大文件偶发 SEC_E_MESSAGE_ALTERED，重试 3-5 次必成功）
POST https://uploads.github.com/repos/.../releases/<id>/assets?name=<file>
```

**签名密钥 SHA-256**：`7303c315ec922568754ad130f10388a4bd9f0e46d3a3f7811c179ccfa8e2ec6b`
（1.4.0 起用这个，1.4.0→1.5.3 可直接覆盖升级；1.2.0 及更早签名不同需先卸载）

---

## 7. 第三方服务配置

| 用途 | 服务 | 获取 | 存哪 |
|---|---|---|---|
| 云端识别 | **Groq** whisper-large-v3 | https://console.groq.com/keys （免费） | 模型页 → Groq 档 → API Key |
| 翻译（推荐） | **智谱 GLM** glm-4-flash | https://open.bigmodel.cn | 设置页 → 自定义引擎 |
| 翻译（备选） | Yandex 免 Key | 内置 | 默认引擎（**海外 IP 可能 403**） |
| 朗读 | Azure 神经语音 | portal.azure.com Speech F0 | 模型页 → 朗读语音 → Azure |
| 朗读（替代） | 任意 OpenAI 兼容 TTS | 自选 | 模型页 → 朗读语音 → OpenAI兼容 |

**注意**：用户当前在**哥斯达黎加**（不是中国大陆）。
- Groq / OpenAI / Azure 从当地直连正常
- **Yandex 免 Key 接口从当地 IP 返回 403**，所以翻译必须用智谱或其它有 Key 的服务

---

## 8. 踩过的坑（★ 最重要，别重复踩）

### 8.1 Whisper 的 `setOption("language")` 完全无效
实测同一段音频，自动检测 / 固定语言 / 固定+prompt **三种配置输出字节级一致**。
Whisper 的语言是 decoder 初始 token，**只能在构造 `OfflineWhisperModelConfig(language=)` 时指定**。
→ 解决方案：`WhisperEngine.transcribeFixedLanguage()` 按语言各建一个 recognizer 常驻。

### 8.2 sherpa-onnx 的 Kotlin wrapper 只暴露 AssetManager 构造器
外部绝对路径会 native crash（无 Java 异常，crash buffer 空）。
→ 解决方案：`SherpaExternalLoader` 用 `sun.misc.Unsafe.allocateInstance` + 反射调 `newFromFile(config)`。

### 8.3 VAD 的 `minSpeechMs` 直接决定短句能不能识别
曾经设 500ms → 「你好」（约 400ms）被当碎片丢弃 → 用户反馈「我讲话都不识别」。
现在 **250ms**。改这个参数前先想清楚：太大丢短句，太小收噪声。

### 8.4 事件丢失导致「翻译成功但界面卡翻译中…」
`BidirectionalSession` 的 forced 分支曾只调 `translate()` 而丢掉返回值，
`Event.Translated` 从未发出。→ 现在两条分支都分发 `when (outcome)`。
**改这个文件时务必确认每个识别分支都发出了 Translated 事件。**

### 8.5 Kotlin `init` 块早于属性声明会拿到 null
`ModelManagerViewModel` 的 `init` 写在 `_state` 之前 → 协程访问 null 直接 FATAL。
**init 必须放在它访问的属性之后。**

### 8.6 Kaggle 数据集不能混用数据源
Remote Files 与本地上传**二选一**（官方限制），且单文件网页上传限 25MB。
→ 最终方案：模型托管在 **HuggingFace 官方仓库**（`csukuangfj/sherpa-onnx-whisper-*`），
manifest 内置在 App 里（`BuiltInModels.kt`），不依赖 Kaggle。

### 8.7 编译期 `Method too large`
`mapOf()` 塞 2965 个条目会让 `<clinit>` 超 JVM 64KB 方法上限。
→ 繁简转换表拆成 6 个 500 条的小 map 再 fold（见 `ZhSimplifyTable.kt`，生成脚本 `tools/gen-zh-simplify.py`）。

### 8.8 单元测试里 `org.json` 是 stub
Android SDK 自带的 org.json 方法全抛 "not mocked"。
→ `testImplementation("org.json:json:20231013")` 引真实实现。

### 8.9 其他
- `abiFilters` 写在 `defaultConfig` 会被 buildType **追加**而非替换 → 只在 buildTypes 里写
- PowerShell 写 `.ps1` 必须**纯 ASCII**（GBK 会吞引号），中文放 `.md`
- 大文件上传 GitHub 偶发 `SEC_E_MESSAGE_ALTERED`（Windows schannel），重试即可
- realme 不响应 `ACTION_MANAGE_OVERLAY_PERMISSION`，悬浮窗权限需手动授予
- `pm clear` 在 realme 上被拒（缺 CLEAR_APP_USER_DATA），用卸载重装代替

---

## 9. 未提交的改动（接手第一件事）

当前工作区有 TTS 通用化改动，**已编译通过、已装真机、UI 已验证，但未提交**：

```
M  data/SettingsStore.kt            + ttsProvider/ttsBaseUrl/ttsPath/ttsModel/ttsVoice/ttsApiKey
M  vm/ModelManagerViewModel.kt      + setCloudTts() + state 字段
M  vm/SimultViewModel.kt            azureTts → cloudTts，按 provider 装配
M  ui/screen/ModelManagerScreen.kt  TTS 卡片改为三档：本地 / Azure / OpenAI兼容
D  data/asr/AzureTtsEngine.kt       已删除（被 CloudTtsEngine 取代）
?? data/asr/CloudTtsEngine.kt       新增：通用云端 TTS（Azure + OpenAI 兼容）
```

**背景**：用户 Azure 注册失败，要求 TTS 接口可自定义以便换服务商。

**接手后建议**：
1. `assembleRelease` 打成 **1.5.4** 发版
2. 用 OpenAI 兼容档真机验证一次（填 OpenAI 或硅基流动的 Key）

---

## 10. 建议的下一步（按价值排序）

1. **补本地 TTS 的 manifest 条目**（`kind="tts"`，Matcha 三件套）
   → 现在没配云端 Key 时朗读是静音的，这是功能缺口
2. **流式识别**（用户明确提过「翻译鸥是边说边出字」）
   → 需要接流式 ASR：Soniox（翻译鸥同款）/ AssemblyAI；本地 Zipformer 流式**没有西语**
3. **验证视频翻译 / 文档翻译 / 悬浮字幕**（大改识别层后未回归）
4. **真实西语双人对话验证**（当前只有电话录音样本）
5. 对话页的说话方切换可考虑「自动交替」（现在需手动点按钮）

---

## 11. 术语表

| 术语 | 含义 |
|---|---|
| **对话翻译** | 双方轮流说、说完一段再翻译的模式（当前主打） |
| **forcedSpeakSide** | 对话模式下由按钮显式指定的说话方，语种由此固定 |
| **asr-offline** | manifest 里的模型类型：非流式识别（Whisper） |
| **asr-cloud** | manifest 里的模型类型：云端识别（Groq），无文件要下载 |
| **manifest** | 模型清单 JSON，内置在 `BuiltInModels.kt`，用户也可填自定义 URL |
| **`.done` 标记** | 模型下载并 SHA-256 校验通过后写入的标记文件 |
| **via=cloud / via=local** | 日志里标识本次识别走的是云端还是本地 |

---

## 12. 常用调试命令

```powershell
# 抓应用日志（只相关的 tag）
adb -s 4e1d6407 logcat -d -s BidiSession:* TranslateEngine:* CloudAsr:* CloudTts:* WhisperEngine:*

# 看当前界面文字（比截图准）
adb -s 4e1d6407 shell uiautomator dump /sdcard/ui.xml
adb -s 4e1d6407 exec-out cat /sdcard/ui.xml

# 看设备上已下载的模型
adb -s 4e1d6407 shell run-as dev.beyonddev.beyondlingo.debug ls files/models/

# 看持久化配置（DataStore）
adb -s 4e1d6407 shell run-as dev.beyonddev.beyondlingo.debug cat files/datastore/settings.preferences_pb

# 单测
.\gradlew.bat :app:testDebugUnitTest --tests '*BuiltInModelsTest*'
```

**注意**：release 版 `run-as` 不可用（非 debuggable），调试必须用 debug 版。

---

## 13. 相关文档

| 文件 | 内容 |
|---|---|
| `HANDOVER.md` | 更详细的历史交接（35KB，按批次记录） |
| `PROJECT.md` | 产品功能清单与规格（47KB） |
| `docs/simult-implementation.md` | 对话翻译实现细节与实测数据 |
| `docs/transgull-simult-reference.md` | 翻译鸥逆向分析结论 |
| `docs/ADR.md` | 架构决策记录 |
| `WAYFINDER.md` + `wayfinder/*.md` | 决策票据 |
