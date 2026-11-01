# 无界译 AI · BeyondLingo AI — 项目设计文档

> **跨越语言边界，让沟通自由发生。**
> *Break Language Barriers. Speak Freely.*

**开发者**：无界者（BeyondDev）
**版本**：1.0.0
**包名**：`dev.beyonddev.beyondlingo`
**许可**：MIT

基于 [STranslate](https://github.com/STranslate/STranslate)（MIT，作者 zggsong）二次开发。

---
## 目录

- [项目背景](#项目背景)
- [功能范围](#功能范围)
- [技术架构](#技术架构)
- [模块设计](#模块设计)
- [翻译引擎协议](#翻译引擎协议)
- [数据模型](#数据模型)
- [划词翻译实现](#划词翻译实现)
- [UI 设计](#ui-设计)
- [构建与运行](#构建与运行)
- [开发路线图](#开发路线图)
- [已知风险](#已知风险)
- [逆向分析记录](#逆向分析记录)
- [许可与致谢](#许可与致谢)

---

## 项目背景

### 原项目

[STranslate](https://github.com/STranslate/STranslate) 是一款基于 **WPF (.NET)** 的开箱即用翻译 + OCR 工具，作者 [zggsong](https://github.com/zggsong)，MIT 协议，8.3k stars / 437 forks，2081 次提交。

核心特性：

| 特性 | 说明 |
| --- | --- |
| 多引擎 | 19 个翻译插件 + 7 个 OCR 插件 + TTS + 生词本 |
| 即用即走 | 全局热键、划词、截图、剪贴板监听多种触发 |
| 插件化 | 完整的插件 SDK（`ITranslatePlugin` / `IOcrPlugin` / `ITtsPlugin`） |
| 离线 OCR | 内置模型，无网可用 |

### 为什么重写

原项目是 **Windows 桌面专属**，无法跨平台：

- WPF 依赖 `.NET Framework` + Windows API
- 全局热键依赖 Win32 `RegisterHotKey`
- 划词依赖 Windows 钩子（`SetWindowsHookEx`）
- 截图依赖 GDI+ 屏幕捕获

Android 上这些机制**全部需要重新实现**，且交互范式完全不同（触屏 vs 鼠标、无障碍 vs 全局钩子）。因此不是移植，而是**借鉴其架构与协议，在 Android 上重建**。

### 移植的核心价值

1. **翻译引擎协议**可直接复用——它们是 HTTP 调用，与平台无关
2. **数据模型**（`LangEnum`、`TranslateResult`、历史结构）可复用
3. **交互设计**（自动翻译防抖、缓存优先、历史持久化）可复用

---

> **当前状态（2026-10-21）**：仓库已包含 Gradle Wrapper 和一个可构建的 MVP（Compose 基础翻译页 + Google 直连接口 + 结果复制）。三引擎、完整语言列表、历史/收藏、设置、无障碍划词、剪贴板监听和悬浮窗尚未实现。下文未明确标为已完成的代码与架构均为设计目标/示例。

## 功能范围

### v1.0 目标与进度

| 功能 | 说明 |
| --- | --- |
| ✅ **基础文本翻译（MVP）** | Google 直连、语言代码输入、结果展示与复制；尚无语言选择器/自动检测 UI |
| 🧭 **多引擎** | Google 免费直连 / OpenAI 兼容 / Yandex 免费直连 |
| 🧭 **语言选择** | 33 种语言，支持自动检测源语言 |
| 🧭 **自动翻译** | 输入后防抖自动触发（可关） |
| 🧭 **翻译历史** | Room 持久化，可搜索、可删除、可复制 |
| 🧭 **划词翻译** | 无障碍服务监听选中文本 → 悬浮窗展示 |
| 🧭 **剪贴板辅助** | 仅考虑用户前台使用场景；后台访问受系统限制，不能作为可靠兜底 |
| 🧭 **悬浮窗** | 可拖动、可关闭、显示译文 |
| 🧭 **设置页** | 引擎切换、API Key 配置、自动翻译开关 |

### 本期不做

| 功能 | 原因 |
| --- | --- |
| ❌ **截图 OCR** | 需 MediaProjection 授权 + 区域框选，是独立大模块。v2 考虑 |
| ❌ **TTS 朗读** | Android 有系统 TTS，但需单独做引擎适配 |
| ❌ **生词本** | 依赖 TTS + 词典，v3 考虑 |
| ❌ **插件系统** | Android 无 .NET 反射，需重新设计（见[路线图](#开发路线图)） |
| ❌ **多引擎并排对比** | 已决策为单引擎 + 设置切换，降低复杂度 |

### 决策记录

| 决策点 | 结论 | 理由 |
| --- | --- | --- |
| 核心定位 | B. 核心翻译器 + 划词悬浮 | 兼顾完整度与工程量 |
| 引擎 | Google + OpenAI 兼容 + Yandex | 前两个免配置/可自定义，Yandex 补充免费额度 |
| 结果展示 | 单引擎，设置切换 | 界面简洁，避免多卡片拥挤 |
| 划词触发 | 无障碍 + 剪贴板双通道 | 国内 ROM 常限制无障碍，需兜底 |
| OCR | 不做 | 独立模块，价值不如桌面 |
| 技术栈 | Kotlin + Jetpack Compose | 原生性能，悬浮窗/无障碍好写 |
| 交付 | 完整源码 + 可安装 APK | 直接验证 |

---

## 技术架构

### 整体分层

```
┌─────────────────────────────────────────────┐
│                   UI 层                      │
│  Compose Screens / Components / Theme        │
└──────────────────┬──────────────────────────┘
                   │ StateFlow / collectAsState
┌──────────────────▼──────────────────────────┐
│                ViewModel 层                  │
│  TranslateViewModel / SettingsViewModel      │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│                 数据层                       │
│  ┌────────────┐ ┌─────────┐ ┌────────────┐  │
│  │ Engine     │ │ Room    │ │ DataStore  │  │
│  │ Registry   │ │ History │ │ Settings   │  │
│  └─────┬──────┘ └─────────┘ └────────────┘  │
└────────┼────────────────────────────────────┘
         │
┌────────▼────────────────────────────────────┐
│              引擎实现层                      │
│  GoogleEngine / YandexEngine / OpenAiEngine │
│         （OkHttp + JSON 解析）               │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│              系统服务层                      │
│  AccessibilityService / FloatingService      │
│  ClipboardWatcher                            │
└─────────────────────────────────────────────┘
```

### 技术选型

| 组件 | 选型 | 版本 | 说明 |
| --- | --- | --- | --- |
| 语言 | Kotlin | 2.2.20 | 官方推荐 |
| UI | Jetpack Compose | BOM 2025.10.00 | 声明式，状态驱动 |
| 架构 | MVVM + StateFlow | - | 单向数据流 |
| 数据库 | Room | 2.8.2 | 编译期校验 SQL |
| 设置存储 | DataStore Preferences | 1.1.7 | 替代 SharedPreferences |
| 网络 | OkHttp | 4.12.0 | 成熟稳定，支持拦截器 |
| JSON | org.json（内置） | - | 轻量，无需额外依赖 |
| 协程 | kotlinx-coroutines | 1.10.2 | 结构化并发 |
| 导航 | Navigation Compose | 2.9.5 | 单 Activity 多屏 |
| 权限 | Accompanist Permissions | 0.36.0 | Compose 权限简化 |

### SDK 版本

```
minSdk    = 26   (Android 8.0)   覆盖范围需按目标用户设备数据评估
targetSdk = 36   (Android 16)    满足 Google Play 要求
compileSdk= 36
```

**为什么选择 minSdk 26？**

这是当前产品覆盖范围与实现复杂度的取舍，不是这些能力首次出现的版本。NotificationChannel 和 `TYPE_APPLICATION_OVERLAY` 在 API 26 引入；若降低 minSdk，需要为部分系统能力增加兼容分支。无障碍服务 API 早于 API 26 已存在。设备覆盖比例应依据目标用户数据验证。

---

## 模块设计

### 目录结构

```
app/src/main/java/com/stranslate/android/
├── MainActivity.kt                    入口，承载整个 Compose 导航
├── STranslateApp.kt                   Application 类，DI 容器
│
├── data/
│   ├── engine/
│   │   ├── TranslateEngine.kt         引擎接口 + 数据模型
│   │   ├── LangEnum.kt                33 种语言枚举
│   │   ├── EngineRegistry.kt          引擎注册与工厂
│   │   ├── GoogleEngine.kt            Google 免费直连实现
│   │   ├── YandexEngine.kt            Yandex 免费直连实现
│   │   └── OpenAiEngine.kt            OpenAI 兼容实现
│   │
│   ├── db/
│   │   ├── HistoryEntity.kt           历史记录实体
│   │   ├── HistoryDao.kt              数据访问对象
│   │   └── AppDatabase.kt             Room 数据库单例
│   │
│   └── SettingsStore.kt               DataStore 封装
│
├── service/
│   ├── SelectionAccessibilityService.kt   无障碍：监听选中文本
│   ├── FloatingWindowService.kt           悬浮窗：显示译文
│   └── ClipboardWatcher.kt                剪贴板守护：兜底触发
│
├── ui/
│   ├── screen/
│   │   ├── TranslateScreen.kt         主翻译页
│   │   ├── HistoryScreen.kt           历史页
│   │   └── SettingsScreen.kt          设置页
│   ├── component/
│   │   ├── LanguageSelector.kt        语言选择下拉
│   │   ├── TranslateResultCard.kt     结果卡片
│   │   └── AppTopBar.kt               顶部栏
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   └── nav/
│       └── Destinations.kt            路由定义
│
├── vm/
│   ├── TranslateViewModel.kt          翻译状态机
│   └── SettingsViewModel.kt           设置状态
│
└── util/
    ├── LanguageDetector.kt            源语言检测
    ├── Debouncer.kt                   输入防抖
    └── TextUtils.kt                   文本处理（参照 STranslate）
```

### 核心接口

```kotlin
interface TranslateEngine {
    /** 引擎唯一标识，如 "google" / "openai" / "yandex" */
    val id: String

    /** 显示名称 */
    val displayName: String

    /** 是否需要 API Key */
    val requiresApiKey: Boolean

    /** 该引擎是否支持某个语言 */
    fun supports(lang: LangEnum): Boolean

    /** 执行翻译 */
    suspend fun translate(
        text: String,
        source: LangEnum,
        target: LangEnum,
        config: EngineConfig
    ): TranslateOutcome
}

/** 翻译结果 */
sealed interface TranslateOutcome {
    data class Success(
        val text: String,
        val detectedSource: LangEnum? = null,
        val elapsedMs: Long = 0
    ) : TranslateOutcome

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        /** 是否可重试（网络错误 = true，配置错误 = false） */
        val retryable: Boolean = false
    ) : TranslateOutcome
}

/** 引擎运行时配置 */
data class EngineConfig(
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val systemPrompt: String = ""
)
```

**设计说明**

- `TranslateOutcome` 用 sealed interface 而非异常，强制调用方处理失败分支
- `retryable` 区分「网络抖一下」和「Key 填错了」，UI 可据此决定是否显示重试按钮
- `config` 独立传入而非引擎持有状态，便于测试与多引擎复用

---

## 翻译引擎协议

> 以下协议从 STranslate 源码逆向提取，均为 HTTP 调用，与平台无关。

### Google 免费直连

**来源**：`src/Plugins/STranslate.Plugin.Translate.GoogleBuiltIn/Main.cs`

```
GET https://translate.google.com/translate_a/single
    ?client=gtx
    &dt=t
    &dj=1
    &ie=UTF-8
    &oe=UTF-8
    &sl={source}
    &tl={target}
    &q={text}
```

**响应结构**

```json
{
  "sentences": [
    { "trans": "翻译片段1", "orig": "原文片段1" },
    { "trans": "翻译片段2", "orig": "原文片段2" }
  ],
  "src": "en"
}
```

**解析逻辑**

```kotlin
val arr = json.getJSONArray("sentences")
val sb = StringBuilder()
for (i in 0 until arr.length()) {
    sb.append(arr.getJSONObject(i).optString("trans", ""))
}
val result = sb.toString()
if (result.isBlank()) throw Exception("No result")
```

**注意**

- `dj=1` 是关键，返回结构化 JSON 而非嵌套数组
- 多个 `sentences` 需要**拼接**，不是取第一个
- 语言代码用小写，`zh-CN` → `zh-cn`
- 不需要任何 API Key，但**随时可能被 Google 封禁**

---

### Yandex 免费直连

**来源**：`src/Plugins/STranslate.Plugin.Translate.YandexBuiltIn/Main.cs`

```
POST https://translate.yandex.net/api/v1/tr.json/translate
     ?ucid={随机GUID}&srv=android&format=text

Headers:
  User-Agent: ru.yandex.translate/3.20.2024
  Content-Type: application/x-www-form-urlencoded

Body:
  text={text}
  lang={source}-{target}
```

**关键参数**

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `ucid` | 随机 UUID，去掉连字符 | **360 秒缓存**，过期换新 |
| `srv` | `android` | 伪装成 Android 客户端 |
| `format` | `text` | 返回纯文本数组 |
| `User-Agent` | `ru.yandex.translate/3.20.2024` | **必须**，否则可能拒绝 |

**响应结构**

```json
{ "text": ["翻译结果"] }
```

**解析逻辑**

```kotlin
val arr = json.getJSONArray("text")
if (arr.length() == 0) throw Exception("No result")
val result = arr.getString(0)
```

**ucid 缓存实现**

```kotlin
private var cachedUcid: String? = null
private var ucidExpireAt: Long = 0

private fun getUcid(): String {
    val now = System.currentTimeMillis()
    if (cachedUcid == null || now > ucidExpireAt) {
        cachedUcid = UUID.randomUUID().toString().replace("-", "")
        ucidExpireAt = now + 360_000L
    }
    return cachedUcid!!
}
```

---

### OpenAI 兼容

**来源**：`src/Plugins/STranslate.Plugin.Translate.OpenAI/Main.cs`

```
POST {baseUrl}/v1/chat/completions

Headers:
  Authorization: Bearer {apiKey}
  Content-Type: application/json

Body:
{
  "model": "{model}",
  "messages": [
    { "role": "system", "content": "你是一个翻译引擎。将用户输入翻译成{目标语言}，只输出译文，不要解释。" },
    { "role": "user",   "content": "{text}" }
  ],
  "temperature": 0.3
}
```

**响应结构**

```json
{
  "choices": [
    { "message": { "role": "assistant", "content": "翻译结果" } }
  ]
}
```

**解析逻辑**

```kotlin
val content = json.getJSONArray("choices")
    .getJSONObject(0)
    .getJSONObject("message")
    .getString("content")
```

**适配说明**

这个协议被广泛兼容，用户可填入：

| 服务 | baseUrl 示例 | 说明 |
| --- | --- | --- |
| OpenAI 官方 | `https://api.openai.com` | 需付费 |
| DeepSeek | `https://api.deepseek.com` | 国内可用 |
| 硅基流动 | `https://api.siliconflow.cn` | 有免费额度 |
| Groq | `https://api.groq.com/openai` | 速度快 |
| 本地 Ollama | `http://{ip}:11434` | 需配置 `OLLAMA_HOST` |
| 任意兼容服务 | - | 只要能回答 `/v1/chat/completions` |

**System Prompt 设计**

```
你是一个翻译引擎。将用户输入翻译成{目标语言}，只输出译文，不要解释、不要加引号、不要重复原文。
```

**关键约束**

- `temperature` 设低（0.3），避免 LLM 自由发挥
- 必须严格提取 `choices[0].message.content`
- 部分服务的 baseUrl 已含 `/v1`，需检测避免拼成 `/v1/v1/...`

---

### 语言代码映射

从 STranslate 的 `LangEnum.cs` 提取（33 种），各引擎映射如下：

| LangEnum | Google | Yandex | DeepL（备用） |
| --- | --- | --- | --- |
| Auto | `auto` | `auto` | `auto` |
| ChineseSimplified | `zh-CN` | `zh` | `ZH-HANS` |
| ChineseTraditional | `zh-TW` | `zh` | `ZH-HANT` |
| Cantonese | `yue` | `zh` | `ZH` |
| English | `en` | `en` | `EN` |
| Japanese | `ja` | `ja` | `JA` |
| Korean | `ko` | `ko` | `KO` |
| French | `fr` | `fr` | `FR` |
| Spanish | `es` | `es` | `ES` |
| Russian | `ru` | `ru` | `RU` |
| German | `de` | `de` | `DE` |
| Italian | `it` | `it` | `IT` |
| Turkish | `tr` | `tr` | `TR` |
| PortuguesePortugal | `pt` | `pt` | `PT-PT` |
| PortugueseBrazil | `pt` | `pt` | `PT-BR` |
| Vietnamese | `vi` | `vi` | `—` |
| Indonesian | `id` | `id` | `ID` |
| Thai | `th` | `th` | `—` |
| Malay | `ms` | `ms` | `—` |
| Arabic | `ar` | `ar` | `AR` |
| Hindi | `hi` | `hi` | `—` |
| MongolianCyrillic | `mn` | `mn` | `—` |
| MongolianTraditional | `mn` | `mn` | `—` |
| Khmer | `km` | `km` | `—` |
| NorwegianBokmal | `no` | `no` | `NB` |
| NorwegianNynorsk | `no` | `no` | `NB` |
| Persian | `fa` | `fa` | `—` |
| Swedish | `sv` | `sv` | `SV` |
| Polish | `pl` | `pl` | `PL` |
| Dutch | `nl` | `nl` | `NL` |
| Ukrainian | `uk` | `uk` | `—` |
| Uzbek | `uz` | `uz` | `—` |
| Uyghur | `ug` | `—` | `—` |

> **注意**：不同引擎对同一语言的支持度不同。`supports()` 方法需按此表实现，不支持的语言在 UI 上置灰或提示。

---

## 数据模型

### 历史记录（Room）

```kotlin
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 原文 */
    @ColumnInfo(name = "source_text") val sourceText: String,

    /** 译文 */
    @ColumnInfo(name = "target_text") val targetText: String,

    /** 源语言 */
    @ColumnInfo(name = "source_lang") val sourceLang: String,

    /** 目标语言 */
    @ColumnInfo(name = "target_lang") val targetLang: String,

    /** 实际检测到的源语言（自动检测时有效） */
    @ColumnInfo(name = "detected_lang") val detectedLang: String? = null,

    /** 使用的引擎 ID */
    @ColumnInfo(name = "engine_id") val engineId: String,

    /** 是否收藏 */
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,

    /** 翻译耗时（毫秒） */
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long = 0,

    /** 时间戳 */
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
```

**设计说明**

- 语言用 `String` 存而非枚举，避免枚举改动导致数据库迁移
- `detectedLang` 单独存，自动检测时记录实际语言，方便历史页展示
- `engineId` 存 ID 而非引用，避免引擎删除后历史失效
- 参照 STranslate 的 `HistoryData`（含 `EffectiveSourceLang` / `ServiceDisplayName`）

### 索引

```kotlin
@Entity(
    tableName = "history",
    indices = [
        Index(value = ["created_at"]),      // 时间倒序查询
        Index(value = ["is_favorite"]),     // 收藏筛选
    ]
)
```

### DAO

```kotlin
@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<HistoryEntity>>

    @Query("""
        SELECT * FROM history
        WHERE source_text LIKE '%' || :query || '%'
           OR target_text LIKE '%' || :query || '%'
        ORDER BY created_at DESC
    """)
    fun search(query: String): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("UPDATE history SET is_favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)
}
```

### 设置（DataStore）

```kotlin
data class AppSettings(
    /** 当前使用的引擎 ID */
    val engineId: String = "google",

    /** 各引擎配置，key = engineId */
    val engineConfigs: Map<String, EngineConfig> = emptyMap(),

    /** 默认源语言 */
    val defaultSourceLang: LangEnum = LangEnum.Auto,

    /** 默认目标语言 */
    val defaultTargetLang: LangEnum = LangEnum.ChineseSimplified,

    /** 输入后自动翻译 */
    val autoTranslate: Boolean = true,

    /** 自动翻译防抖延迟（毫秒） */
    val autoTranslateDelayMs: Long = 800,

    /** 翻译后自动复制 */
    val copyAfterTranslate: Boolean = false,

    /** 历史记录上限（0 = 不限制） */
    val historyLimit: Int = 1000,

    /** 开启划词悬浮 */
    val selectionTranslateEnabled: Boolean = false,

    /** 开启剪贴板监听 */
    val clipboardWatchEnabled: Boolean = false,

    /** 悬浮窗是否自动关闭 */
    val floatingAutoDismiss: Boolean = true,

    /** 悬浮窗自动关闭延迟（毫秒） */
    val floatingAutoDismissMs: Long = 5000,

    /** 深色模式：system / light / dark */
    val themeMode: String = "system",
)
```

**参照 STranslate 的设置项**

| STranslate | 本项 | 说明 |
| --- | --- | --- |
| `AutoTranslate` | `autoTranslate` | 一致 |
| `AutoTranslateDelayMs` | `autoTranslateDelayMs` | 一致（原默认值需查证） |
| `CopyAfterTranslation` | `copyAfterTranslate` | 一致 |
| `HistoryLimit` | `historyLimit` | 一致 |
| `SourceLang` / `TargetLang` | `defaultSourceLang` / `defaultTargetLang` | 一致 |

---

## 划词翻译实现

这是 Android 与 Windows 差异最大的部分。

### Windows 方案 vs Android 方案

| 环节 | STranslate (Windows) | 本实现 (Android) |
| --- | --- | --- |
| 监听选中 | `SetWindowsHookEx` 鼠标钩子 + `Ctrl+C+C` | **无障碍服务** 监听 `TYPE_VIEW_TEXT_SELECTION_CHANGED` |
| 获取文本 | 读剪贴板 | `AccessibilityNodeInfo.text` 或选中范围 |
| 展示结果 | WPF 悬浮窗 | `WindowManager` + `TYPE_APPLICATION_OVERLAY` |
| 辅助输入 | 用户在本应用前台粘贴 | 后台剪贴板监听受系统限制，不能保证可用 |

### 无障碍服务

```kotlin
class SelectionAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return

        val node = event.source ?: return
        val selected = extractSelection(node) ?: return

        if (selected.isBlank() || selected.length < 2) return
        if (isOurOwnPackage(event.packageName)) return  // 避免自己触发自己

        // 防重复：同一文本 1.5 秒内不重复触发
        if (!shouldTrigger(selected)) return

        // 拉起悬浮窗
        FloatingWindowService.show(applicationContext, selected, isSelection = true)
    }

    private fun extractSelection(node: AccessibilityNodeInfo): String? {
        // 优先取选中范围文本
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        val full = node.text?.toString()

        if (full != null && start >= 0 && end > start && end <= full.length) {
            return full.substring(start, end).trim()
        }
        // 退化：取整个节点文本（部分 App 不上报 selection 索引）
        return full?.trim()?.takeIf { it.isNotEmpty() && it.length < 5000 }
    }

    override fun onInterrupt() {}
}
```

**`accessibility-service` 配置**（`res/xml/accessibility_service_config.xml`）

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeViewTextSelectionChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_description"
    android:notificationTimeout="100" />
```

**关键配置项**

| 属性 | 值 | 作用 |
| --- | --- | --- |
| `accessibilityEventTypes` | `typeViewTextSelectionChanged` | **只监听选中变化**，降低干扰 |
| `canRetrieveWindowContent` | `true` | **必须**，否则读不到文本 |
| `notificationTimeout` | `100` | 100ms 内的事件合并 |

### 悬浮窗服务

```kotlin
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private fun buildOverlayParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
    }
}
```

**必需的权限**

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".service.SelectionAccessibilityService"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

### 剪贴板兜底

Android 10+ 通常限制后台应用读取剪贴板内容；前台服务本身不会解除该限制。因此剪贴板监听仅可视作尽力而为的辅助触发，不能承诺后台复制即翻译：

| 场景 | 可行性 |
| --- | --- |
| App 在前台 | ✅ 可读 |
| App 在后台（包括运行前台服务） | ❌ 通常无法读取；前台服务不绕过系统限制 |
| App 被杀死 | ❌ 不可读 |

**实现策略**

```kotlin
class ClipboardWatcher(private val context: Context) {

    private var lastText: String? = null
    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener

        val text = clip.getItemAt(0).coerceToText(context).toString().trim()
        if (text.isBlank() || text == lastText) return@OnPrimaryClipChangedListener

        lastText = text
        // 触发翻译
        FloatingWindowService.show(context, text, isSelection = false)
    }

    fun start() { clipboardManager.addPrimaryClipChangedListener(listener) }
    fun stop()  { clipboardManager.removePrimaryClipChangedListener(listener) }
}
```

> **重要**：不存在供普通应用申请的 `READ_CLIPBOARD` 权限。Android 10+ 的后台读取限制不能靠前台服务绕过。若保留监听，应捕获读取失败并提示用户打开应用后主动粘贴；不可将其作为可靠后台能力。

### 触发去重

若未来同时启用无障碍和剪贴板辅助输入，同一段文本可能重复触发，需去重：

```kotlin
object TriggerGate {
    private const val DEDUP_WINDOW_MS = 1500L
    private var lastText: String? = null
    private var lastAt: Long = 0

    @Synchronized
    fun shouldTrigger(text: String): Boolean {
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastAt < DEDUP_WINDOW_MS) return false
        lastText = text
        lastAt = now
        return true
    }
}
```

---

## UI 设计

### 页面结构

```
MainActivity (单 Activity)
└── NavHost
    ├── translate     主翻译页（默认）
    ├── history       历史记录
    └── settings      设置
```

### 主翻译页

```
┌─────────────────────────────────┐
│ STranslate          [历史] [设置]│  TopBar
├─────────────────────────────────┤
│ ┌─────────────────────────────┐ │
│ │ 自动  ▾          [⇄]  ▾ 中文 │ │  语言选择行
│ └─────────────────────────────┘ │
│                                 │
│ ┌─────────────────────────────┐ │
│ │ 在此输入要翻译的文本...      │ │  输入区
│ │                             │ │  (多行，可清空)
│ │                             │ │
│ └─────────────────────────────┘ │
│                     [清空] [翻译]│
│                                 │
│ ┌─────────────────────────────┐ │
│ │ Google 翻译        320ms    │ │  结果卡片
│ │ ─────────────────────────── │ │
│ │ Hello, world!               │ │
│ │                      [复制]  │ │
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```

### 状态机

```kotlin
sealed interface TranslateUiState {
    /** 空闲，无输入 */
    data object Idle : TranslateUiState

    /** 有输入，未翻译 */
    data class Ready(val input: String) : TranslateUiState

    /** 翻译中 */
    data class Translating(val input: String) : TranslateUiState

    /** 翻译成功 */
    data class Success(
        val input: String,
        val output: String,
        val elapsedMs: Long,
        val detectedLang: LangEnum?
    ) : TranslateUiState

    /** 翻译失败 */
    data class Error(
        val input: String,
        val message: String,
        val retryable: Boolean
    ) : TranslateUiState
}
```

### 自动翻译防抖

参照 STranslate 的 `DebounceExecutor`：

```kotlin
class DebounceExecutor(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun schedule(delayMs: Long, action: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            action()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
```

**使用**

```kotlin
fun onInputChanged(text: String) {
    if (!settings.autoTranslate) return
    if (text.isBlank()) {
        debouncer.cancel()
        return
    }
    debouncer.schedule(settings.autoTranslateDelayMs) {
        translate(text)
    }
}
```

### 设计语言

| 项 | 取值 |
| --- | --- |
| 主色 | `#2962FF`（Material Blue 700） |
| 强调色 | `#00BFA5`（Teal A700） |
| 背景 | `#FAFAFA` / 深色 `#121212` |
| 圆角 | 卡片 16dp，按钮 12dp |
| 字体 | 系统默认（Roboto / 思源黑体） |
| 动效 | 结果卡片 `fadeIn + slideInVertically`，250ms |

---

## 构建与运行

### 环境要求

| 工具 | 版本 | 路径 |
| --- | --- | --- |
| JDK | 21 | `<JDK 21 路径>` |
| Android SDK | 36 | `<SDK 路径>` |
| Build Tools | 36.0.0 | `<SDK 路径>\build-tools\36.0.0` |
| Gradle | Wrapper | 自动下载 |

### 首次构建

项目已包含 Gradle Wrapper（Gradle 8.13）。当前 Android Gradle Plugin 8.13.0 要求 JDK 17 或更高版本。

```powershell
# 按本机实际路径设置
$env:JAVA_HOME = "C:\Path\To\JDK"
$env:ANDROID_HOME = "C:\Path\To\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

# 从项目根目录执行
.\gradlew.bat assembleDebug
```

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`。首次构建需联网下载尚未缓存的 Gradle/Android 依赖。
### 安装到手机

```powershell
# 方式一：adb 安装
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 方式二：直接拷贝 APK 到手机点击安装
```

### 本地配置文件

`local.properties`（**不提交到 Git**）

```properties
sdk.dir=E\:\\<sdk-path>
```

### 首次启动引导

App 首次启动时需引导用户：

1. **授予悬浮窗权限** → `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
2. **开启无障碍服务** → `Settings.ACTION_ACCESSIBILITY_SETTINGS`
3. **授予通知权限**（Android 13+，前台服务需要）
4. **配置翻译引擎**（OpenAI 需 Key，Google/Yandex 免配置）

---

## 开发路线图

### v1.0（本期）

- [x] 基础 Gradle 配置、Manifest 与 Gradle Wrapper
- [x] Google 直连翻译 MVP（仅基础实现）
- [ ] 完成 Google 引擎错误处理与支持边界
- [ ] OpenAI 兼容与 Yandex 引擎
- [ ] 语言枚举与映射
- [ ] 主翻译页 + 状态机
- [ ] 历史记录（Room）
- [ ] 设置页（DataStore）
- [ ] 无障碍划词
- [ ] 剪贴板监听
- [ ] 悬浮窗
- [x] 构建 Debug APK（设备运行/网络翻译尚未实机验证）

### v1.1

- [ ] 词典查询（参照 STranslate 的 `IDictionaryPlugin`）
- [ ] 翻译结果回译
- [ ] 短语收藏
- [ ] 多引擎并排对比（可选切换）

### v1.2

- [ ] TTS 朗读（Android `TextToSpeech`）
- [ ] 生词本
- [ ] 导入/导出历史（CSV）

### v2.0

- [ ] 截图 OCR（ML Kit 离线识别）
- [ ] 图片翻译（覆盖原文）
- [ ] 更多引擎（DeepL / 百度 / 腾讯 / 有道）

### 插件系统（远期）

STranslate 的插件系统基于 **.NET 反射 + DLL 动态加载**，Android 无法直接复用。替代方案：

| 方案 | 可行性 | 说明 |
| --- | --- | --- |
| DEX 动态加载 | ⚠️ 低 | Android 9+ 限制 `DexClassLoader` |
| 脚本引擎 | ✅ 高 | 用 Lua/JS 引擎执行用户脚本 |
| 配置化引擎 | ✅ 高 | 用户填 URL + JSON 路径模板，无需写代码 |

**推荐**：配置化引擎。用户只需填：

```json
{
  "name": "MyEngine",
  "url": "https://api.example.com/translate",
  "method": "POST",
  "headers": { "Authorization": "Bearer {key}" },
  "body": { "text": "{text}", "from": "{source}", "to": "{target}" },
  "responsePath": "data.result"
}
```

这能覆盖 90% 的自建/第三方翻译 API，且无需打包插件。

---

## 已知风险

### 1. 免费引擎随时失效

**风险**：Google / Yandex 的直连协议是逆向出来的，Google 历史上多次变更 `translate_a/single` 的接口和反爬策略。

**缓解**：

- 错误提示明确区分「网络问题」和「引擎返回异常」
- 引擎失败时引导用户切换到 OpenAI 兼容（可控性最强）
- 三个引擎互为备份，一个挂了还能用别的

### 2. 无障碍服务的 ROM 兼容性

**风险**：国内定制 ROM（MIUI / EMUI / ColorOS / OriginOS）会：

- 后台杀无障碍服务
- 限制 `AccessibilityNodeInfo.text` 读取（银行、支付类 App）
- 需要用户手动加入「自启动白名单」

**缓解**：

- 提供前台粘贴入口；后台剪贴板读取不作为兜底承诺
- 首次开启时给出各 ROM 的配置指引
- 服务被杀死时通过 `onServiceConnected` 重建

### 3. Android 10+ 剪贴板限制

**风险**：Android 10 起，后台应用通常不能读取剪贴板内容；前台服务不会授予额外读取权限。不同系统版本及 ROM 的回调/读取行为需实机验证。

**缓解**：

- 前台服务仅用于持续展示用户可见的运行状态，不作为剪贴板读取权限方案
- 通知栏常驻提示，明确告知用户它在运行
- 后台读取失败时提示用户回到应用并主动粘贴

### 4. 悬浮窗权限被拒

**风险**：用户拒绝 `SYSTEM_ALERT_WINDOW` 后，划词翻译完全不可用。

**缓解**：

- 首次启动引导授权
- 权限缺失时，改为「复制到剪贴板 + 通知栏显示译文」
- 设置页常驻权限状态检测

### 5. OpenAI 兼容服务的 baseUrl 拼接

**风险**：不同服务的 baseUrl 格式不一（有的含 `/v1`，有的不含），容易拼出 `/v1/v1/chat/completions`。

**缓解**：

```kotlin
fun buildEndpoint(baseUrl: String): String {
    val base = baseUrl.trimEnd('/')
    return if (base.endsWith("/v1")) "$base/chat/completions"
           else "$base/v1/chat/completions"
}
```

---

## 逆向分析记录

### 分析对象

- **仓库**：`https://github.com/STranslate/STranslate`
- **提交**：2081 commits（截至分析时）
- **版本**：STranslate 2.0
- **许可**：MIT
- **本地路径**：`stranslate-src/`（仅为分析记录中的预期路径，当前仓库未包含上游源码副本）

### 源码结构

```
src/
├── STranslate/                 主 WPF 应用
│   ├── Controls/               XAML 控件
│   ├── Converters/             值转换器
│   ├── Core/                   核心逻辑
│   │   ├── TranslationResultCoordinator.cs
│   │   ├── TranslationOperation.cs
│   │   ├── SqlService.cs
│   │   └── Utilities.cs
│   ├── Helpers/                辅助（含 LanguageDetector.cs）
│   ├── Services/               服务层（含 TranslateService.cs）
│   ├── ViewModels/             MVVM
│   └── Views/                  窗口
│
├── STranslate.Plugin/          **插件 SDK（关键）**
│   ├── IPlugin.cs
│   ├── ITranslatePlugin.cs     ← 翻译插件接口 + 结果模型
│   ├── IOcrPlugin.cs
│   ├── ITtsPlugin.cs
│   ├── IVocabularyPlugin.cs
│   ├── IPluginContext.cs       插件运行时上下文
│   ├── LangEnum.cs             ← 33 种语言枚举
│   └── Prompt.cs               LLM 提示词
│
├── Plugins/                    19 个官方插件
│   ├── STranslate.Plugin.Translate.GoogleBuiltIn/   ← Google 协议
│   ├── STranslate.Plugin.Translate.YandexBuiltIn/   ← Yandex 协议
│   ├── STranslate.Plugin.Translate.OpenAI/          ← OpenAI 协议
│   ├── STranslate.Plugin.Translate.DeepL/
│   ├── STranslate.Plugin.Translate.Baidu/
│   └── ...
│
└── docs/                       官方文档（详见 AGENTS.md 导航）
```

### 关键发现

**1. 翻译插件接口设计**

```csharp
public interface ITranslatePlugin : IPlugin
{
    TranslateResult TransResult { get; }
    TranslateResult TransBackResult { get; }
    string? GetSourceLanguage(LangEnum langEnum);
    string? GetTargetLanguage(LangEnum langEnum);
    Task TranslateAsync(TranslateRequest request, TranslateResult result,
                        CancellationToken ct = default);
}
```

**2. 并发隔离机制**（`TranslationOperation`）

SRTranslate 用操作对象实现「旧请求不覆盖新结果」：

- 每次翻译创建 `TranslationOperation`，持有递增 ID
- 插件只写**本次请求独占的临时结果**
- 只有当前操作有效时，结果才发布到 UI
- 被取代的旧任务仍可完成取消和清理，但不写历史、不复制

**这是本项目必须复刻的关键设计**——否则快速输入时会出现「后发先至」的乱序结果。

**3. 缓存优先策略**

```
ExecuteTranslateAsync(checkCacheFirst: true)
  → 用 (InputText, SourceLang, TargetLang) 查 SQLite
  → 命中则直接填充结果，只对未命中的引擎发请求
```

**4. 取词文本后处理**

`HandleCapturedText(text, scope)` 在进入翻译前统一处理：

- 按 `LineBreakHandleType` 处理换行
- 把英文/数字标识符内部的 `_` / `-` 转空格
- **普通键盘输入不走此处理**

**5. 自动翻译防抖**

`OnInputTextChanged` → `DebounceExecutor` → 延迟 `AutoTranslateDelayMs` 后执行

### 未能复用的部分

| 组件 | 原因 |
| --- | --- |
| WPF XAML | Android 用 Compose，范式不同 |
| `SetWindowsHookEx` 全局钩子 | Android 用无障碍服务 |
| `RegisterHotKey` | Android 无全局热键（可用无障碍模拟） |
| GDI+ 截图 | Android 用 MediaProjection |
| .NET 插件 DLL 加载 | Android 无对应机制 |
| `System.Windows.Controls.Control` | 插件 UI 返回 WPF Control，Android 需重设计 |

---

## 许可与致谢

### 本项许可

**MIT License** —— 与上游一致。

### 上游致谢

本项目的**翻译协议、语言枚举、核心交互设计**源自：

- **[STranslate](https://github.com/STranslate/STranslate)** — 作者 [zggsong](https://github.com/zggsong)，MIT
- 组织：[STranslate](https://github.com/STranslate)
- 文档：[stranslate.zggsong.com](https://stranslate.zggsong.com)

**依据 MIT 协议，本项目保留原作者署名，并在代码注释中标注协议来源。**

### 具体引用清单

| 来源文件 | 用于本项目 | 类型 |
| --- | --- | --- |
| `STranslate.Plugin/LangEnum.cs` | `data/engine/LangEnum.kt` | 数据定义 |
| `STranslate.Plugin/ITranslatePlugin.cs` | `data/engine/TranslateEngine.kt` | 接口设计 |
| `Plugins/.../GoogleBuiltIn/Main.cs` | `data/engine/GoogleEngine.kt` | HTTP 协议 |
| `Plugins/.../YandexBuiltIn/Main.cs` | `data/engine/YandexEngine.kt` | HTTP 协议 |
| `Plugins/.../OpenAI/Main.cs` | `data/engine/OpenAiEngine.kt` | HTTP 协议 |
| `Core/TranslationOperation.cs` | `vm/TranslateViewModel.kt` | 并发隔离设计 |
| `Helpers/LanguageDetector.cs` | `util/LanguageDetector.kt` | 语言检测思路 |
| `docs/flow-main-translation.md` | 本文件 | 流程说明 |

### 第三方组件

| 组件 | 许可 | 用途 |
| --- | --- | --- |
| AndroidX / Jetpack Compose | Apache 2.0 | UI 框架 |
| OkHttp | Apache 2.0 | HTTP 客户端 |
| Room | Apache 2.0 | 数据库 |
| DataStore | Apache 2.0 | 设置存储 |
| kotlinx-coroutines | Apache 2.0 | 异步 |
| Accompanist | Apache 2.0 | 权限处理 |

---

## 附录：快速参考

### 关键文件索引

| 任务 | 文件 |
| --- | --- |
| 改翻译逻辑 | `data/engine/*.kt` |
| 改 UI | `ui/screen/*.kt` |
| 改历史存储 | `data/db/*.kt` |
| 改设置项 | `data/SettingsStore.kt` |
| 改划词触发 | `service/SelectionAccessibilityService.kt` |
| 改悬浮窗样式 | `service/FloatingWindowService.kt` |
| 改语言列表 | `data/engine/LangEnum.kt` |

### 常用 adb 命令

```powershell
# 查看无障碍服务状态
adb shell settings get secure enabled_accessibility_services

# 查看悬浮窗权限
adb shell appops get com.stranslate.android SYSTEM_ALERT_WINDOW

# 查看日志
adb logcat -s STranslate:D

# 强停后重启
adb shell am force-stop com.stranslate.android
adb shell am start -n com.stranslate.android/.MainActivity
```

### 调试技巧

| 问题 | 排查方法 |
| --- | --- |
| 无障碍不触发 | `adb shell settings get secure enabled_accessibility_services` 确认服务已启用 |
| 悬浮窗不显示 | `adb shell appops get ... SYSTEM_ALERT_WINDOW` 确认权限 |
| 翻译无响应 | `adb logcat -s STranslate` 看 HTTP 请求日志 |
| 剪贴板不触发 | 确认前台服务在运行（通知栏有常驻通知） |

---

*文档版本：v1.0 · 基于 STranslate 2.0 逆向分析生成*



