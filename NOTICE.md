# NOTICE — 无界译 AI / BeyondLingo AI

## 本项目

| 项 | 内容 |
| --- | --- |
| **中文名称** | 无界译 AI |
| **英文名称** | BeyondLingo AI |
| **开发者** | 无界者（BeyondDev） |
| **中文口号** | 跨越语言边界，让沟通自由发生。 |
| **英文口号** | Break Language Barriers. Speak Freely. |
| **开始开发** | 2026-10 |
| **许可** | MIT |

## 二次开发声明

**无界译 AI（BeyondLingo AI）是一个二次开发项目，不是 STranslate 的官方 Android 版本。**

本项目基于以下上游作品进行 Android 平台的重写与扩展：

| 项 | 内容 |
| --- | --- |
| **上游项目** | STranslate |
| **原作者** | zggsong |
| **上游许可** | MIT License |
| **上游地址** | https://github.com/STranslate/STranslate |
| **上游形态** | Windows 桌面应用（WPF / .NET） |

### 本项目复用了什么

依据 MIT License 的授权，本项目复用了上游项目的以下内容：

1. **翻译引擎的 HTTP 协议实现**
   - Yandex 翻译：`translate.yandex.net/api/v1/tr.json` 的 `ucid` / `srv=android` / `format=text` 参数约定与 User-Agent 伪装
   - OpenAI 兼容协议：`/v1/chat/completions` 的请求结构与温度参数
   - 相关源码位置：`src/Plugins/STranslate.Plugin.Translate.YandexBuiltIn/Main.cs`、`src/Plugins/STranslate.Plugin.Translate.OpenAI/Main.cs`

2. **语言枚举定义**
   - `LangEnum` 的 33 种语言列表与命名
   - 相关源码位置：`src/STranslate.Plugin/LangEnum.cs`

3. **交互设计思路**
   - 并发隔离机制（`TranslationOperation` 的设计思想）
   - 缓存优先策略
   - 输入防抖延迟触发

### 本项目完全独立实现的部分

以下内容**不包含**任何来自上游的代码，由 BeyondDev 独立编写：

- 全部 Android 平台实现（Kotlin）
- 全部 UI（Jetpack Compose）
- 无障碍服务（`SelectionAccessibilityService`）
- 悬浮窗服务（`FloatingWindowService`）
- 剪贴板监听（`ClipboardWatcher`）
- 本地语言检测启发式算法（`LanguageDetector`）
- 触发去重机制（`TriggerGate`）
- 自我写入过滤（`SelfWriteMarker`）
- 自定义引擎与预设体系（`CustomEngine` / `CustomEnginePresets`）
- Room 数据层
- DataStore 设置层
- 启动页与品牌体系

### 上游未实现、本项目新增的能力

| 能力 | 说明 |
|---|---|
| 自定义引擎预设 | 智谱 GLM / DeepSeek / 硅基流动 / Moonshot / Groq / OpenAI 一键填充 |
| 三级自动翻译 | 输入防抖 / 剪贴板监听 / 静默全自动 |
| 静默模式 | 划词或复制后不弹窗，结果直写通知栏 |
| 后台自动恢复 | 进程重启后自动恢复剪贴板监听 |

## 使用者须知

如果你是第一次接触到本应用，请注意：

1. **这是无界者（BeyondDev）的独立项目**，不是 DeepSeek 或 STranslate 官方产品
2. **问题反馈请找 BeyondDev**，不要打扰 STranslate 原作者
3. **上游 STranslate 是 Windows 桌面软件**，与本 Android 应用是不同的产品
4. 如发现任何问题，优先怀疑本项目的实现，而非上游协议本身

## 上游署名保留

根据 MIT License 的要求，本项目的源代码中保留了上游协议的来源标注，例如：

```kotlin
/**
 * 协议来源：STranslate `Plugins/STranslate.Plugin.Translate.YandexBuiltIn/Main.cs`
 * （MIT，作者 zggsong）
 */
```

## 许可

本项目采用 **MIT License**，与上游保持一致。

你可以自由使用、修改、分发，但需保留本 NOTICE 与 LICENSE 中的版权声明。

---

**无界译 AI / BeyondLingo AI**
由 无界者（BeyondDev）开发与维护
跨越语言边界，让沟通自由发生。
Break Language Barriers. Speak Freely.
