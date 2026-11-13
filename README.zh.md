# 无界译 AI · BeyondLingo AI

> **跨越语言边界，让沟通自由发生。**
> *Break Language Barriers. Speak Freely.*

> 📖 **English?** → [README.md](README.md)（英文摘要版）

由 **无界者（BeyondDev）** 开发与维护的 Android 原生翻译应用。

基于 [STranslate](https://github.com/STranslate/STranslate)（MIT，作者 zggsong）二次开发。

---

## 特性

- **双引擎**：Yandex 免费直连（免配置） / 自定义引擎（智谱 GLM、DeepSeek、硅基流动、Moonshot、Groq、OpenAI）
- **33 种语言**
- **双向自动判定方向**：只声明「我的母语」与「对方语言」，母语进来译成对方语言，
  对方语言进来译成母语，无需手动切换
- **划词翻译**：无障碍服务监听选中文本 + 长按事件，悬浮窗展示译文
  - 支持标准选区（浏览器、输入框）与自绘控件长按（WhatsApp、微信）
- **两级触发**：
  - ① 输入框防抖（停止输入自动翻译）
  - ② 无障碍划词（长按选中文字即翻译）
- **静默模式**：划词后不弹窗，结果直写通知栏
- **图片取词（OCR）**：从相册选图或拍照，本地识别文字后直接翻译
  - ML Kit 拉丁文字模型，完全离线，图片不上传
- **翻译历史**：Room 本地存储，可搜索、收藏、复制
- **原生体验**：Kotlin + Jetpack Compose，单 Activity

### 1.4.0 新增

- **同声传译**：麦克风 → 本地流式识别 → 云端翻译 → 双语字幕。
  **音频全程不出设备**，只有识别后的文字发去翻译。
- **悬浮字幕**：切到别的 App 也能继续看译文。
- **可选本地朗读**：Matcha 中英 TTS，默认关闭。
- **视频翻译**：选本地视频 → 抽音频 → 识别 → 翻译 → 双语 `.srt`（可导出）。
  视频只读不重编码。
- **文档翻译**：`txt` / `md` / `docx`，docx 用自研解析（不依赖 Android 上会崩的
  Apache POI），带 GB18030 编码探测，中文 GBK 文本不会乱码。
- **模型自带托管**：ASR / 标点 / TTS 模型可放自己的云空间按需下载，
  带 SHA-256 校验与断点续传，不必全部打进 APK。

## 快速开始

### 环境

| 工具 | 版本 |
| --- | --- |
| JDK | 21 |
| Android SDK | 36 |
| minSdk | 26 (Android 8.0) |

### 构建

```powershell
$env:JAVA_HOME = "<JDK 21 路径>"
$env:ANDROID_HOME = "<SDK 路径>"

cd beyondlingo
.\gradlew.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

### 安装

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 首次使用

1. **选引擎**：设置页选 Yandex（免配置）或自定义引擎
2. **配智谱**（可选）：自定义引擎 → 点「智谱 GLM」预设 → 填 API Key
3. **开划词**（可选）：授予悬浮窗权限 + 开启无障碍服务

### 引擎配置

**Yandex**：免配置，装上就能用。逆向协议，可能随时失效。

**自定义引擎**：内置 6 个预设

| 服务 | baseUrl | 默认模型 |
| --- | --- | --- |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash`（免费） |
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |
| 硅基流动 | `https://api.siliconflow.cn` | `Qwen/Qwen2.5-7B-Instruct` |
| Moonshot | `https://api.moonshot.cn` | `moonshot-v1-8k` |
| Groq | `https://api.groq.com/openai` | `llama-3.3-70b-versatile` |
| OpenAI | `https://api.openai.com` | `gpt-4o-mini` |

高级选项支持自定义：请求路径、鉴权头名称、鉴权前缀、额外请求头。

## 权限说明

| 权限 | 用途 | 必需 |
| --- | --- | --- |
| `INTERNET` | 翻译请求 | ✅ |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗显示译文 | 划词需要 |
| `BIND_ACCESSIBILITY_SERVICE` | 监听选中文本 | 划词需要 |
| `FOREGROUND_SERVICE` | 悬浮窗服务常驻 | 划词需要 |
| `POST_NOTIFICATIONS` | 悬浮窗/翻译结果通知 | 划词需要 |

## 文档

- [README.md](README.md) — 英文摘要版（English summary）
- [NOTICE.md](NOTICE.md) — 二次开发声明与归属
- [PROJECT.md](PROJECT.md) — 完整技术设计（架构、协议、数据模型）
- [docs/ADR.md](docs/ADR.md) — 架构决策记录

## 关于本项目

**这是一个二次开发项目，不是 STranslate 的官方 Android 版本。**

- 上游 [STranslate](https://github.com/STranslate/STranslate) 是 Windows 桌面软件（WPF / .NET）
- 本项目是 Android 原生重写（Kotlin / Compose）
- 复用了上游的翻译协议、语言枚举与交互设计思路
- UI、系统服务集成、自定义引擎等由 BeyondDev 独立实现

问题反馈请找 **BeyondDev**，不要打扰上游原作者。

## 开发者

**无界者（BeyondDev）**

- 中文口号：跨越语言边界，让沟通自由发生。
- 英文口号：Break Language Barriers. Speak Freely.

## 许可

MIT License —— 与上游一致。详见 [NOTICE.md](NOTICE.md)。
