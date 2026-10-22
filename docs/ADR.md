# 架构决策记录（ADR）

本文件记录 STranslate for Android 的关键决策及其理由。格式参考 [Michael Nygard 的 ADR 模式](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)。

---

## ADR-001：不移植 WPF，改为 Android 原生重写

**状态**：已采纳

**背景**

STranslate 是 WPF + .NET 的 Windows 桌面应用。评估「能否移植到 Android」时需要明确：是代码移植，还是重新实现。

**决策**

Android 原生重写（Kotlin + Compose），只复用与平台无关的部分。

**理由**

WPF 的核心机制在 Android 上无对应物：

| WPF 机制 | Android 对应 | 可移植性 |
| --- | --- | --- |
| XAML 声明式 UI | Compose（范式不同） | ❌ |
| `SetWindowsHookEx` 全局钩子 | AccessibilityService | ❌ |
| `RegisterHotKey` 全局热键 | 无直接对应 | ❌ |
| GDI+ 屏幕截图 | MediaProjection | ❌ |
| .NET DLL 反射加载插件 | 无对应机制 | ❌ |
| `System.Windows.Controls.Control` | Compose `@Composable` | ❌ |

能复用的是纯逻辑层：

| 可复用 | 形式 |
| --- | --- |
| HTTP 协议 | 直接重写为 OkHttp 调用 |
| `LangEnum` 33 种语言 | 枚举定义照搬 |
| 数据模型 | 字段结构参照 |
| 交互流程 | 设计思路参照 |

**后果**

- 正面：获得原生性能与正确的手势/无障碍集成
- 负面：UI 层需完全重写，约占总工作量 60%

---

## ADR-002：三个免费/兼容引擎而非官方 API 优先

**状态**：已采纳

**背景**

STranslate 提供 19 个翻译插件。Android 首版应选哪几个？

**决策**

Google 免费直连 + OpenAI 兼容 + Yandex 免费直连。

**理由**

| 引擎 | 免 Key | 覆盖场景 | 风险 |
| --- | --- | --- | --- |
| Google 直连 | ✅ | 通用翻译，质量好 | 逆向协议，可能失效 |
| OpenAI 兼容 | ❌ | 可接任意 LLM 服务端 | 需用户配置，但可控性最强 |
| Yandex 直连 | ✅ | 俄语系优势 | 逆向协议 |

三者形成互补：

- 零配置即可用（Google/Yandex）
- 一个失效可切另一个
- OpenAI 兼容是「终极兜底」，只要用户有任何一个 LLM API 就能用

**拒绝的方案**

| 方案 | 拒绝理由 |
| --- | --- |
| 只做 OpenAI | 用户必须先去申请 Key，首启体验差 |
| 只做 Google | 逆向协议单点依赖，一旦失效 App 变砖 |
| 做 DeepL/百度/腾讯 | 都要用户注册申请，且免费额度小 |

**后果**

- 正面：开箱即用 + 可扩展
- 负面：需维护三套协议解析，Google 接口变更需跟进

---

## ADR-003：单引擎展示而非多引擎并排

**状态**：已采纳

**背景**

STranslate 桌面版支持多引擎并排对比，可直观比较不同翻译质量。

**决策**

v1.0 单引擎展示，设置里切换。

**理由**

手机屏幕宽度有限。三个引擎并排会导致：

- 每条译文被压缩到 1/3 宽度，长句换行严重
- 视觉噪音大，反而降低可读性
- 用户 90% 场景只需要一个可用的译文

**后果**

- 正面：界面简洁，代码简单（单结果状态而非结果列表）
- 负面：失去对比能力
- 缓解：v1.1 可加「对比」按钮展开

---

## ADR-004：无障碍 + 剪贴板双通道

**状态**：已采纳

**背景**

划词翻译是 STranslate 的核心体验之一。Android 上有多种触发方式可选。

**决策**

同时实现无障碍服务（监听选中）和剪贴板监听（兜底）。

**理由**

单通道都有致命缺陷：

| 通道 | 优势 | 缺陷 |
| --- | --- | --- |
| 无障碍 | 自动触发，最接近桌面划词 | 国内 ROM 限制、银行/支付类 App 屏蔽、可能被系统杀 |
| 剪贴板 | 兼容性好、不依赖无障碍 | 需用户主动复制、Android 10+ 后台受限 |

两者结合：

- 无障碍可用时 → 自动划词
- 无障碍被限制 → 用户复制后自动翻译
- 都不可用 → 主界面手动输入

**后果**

- 正面：覆盖绝大多数场景
- 负面：工程量增加约 40%，需处理两通道触发的去重（见 `TriggerGate`）

---

## ADR-005：Kotlin + Jetpack Compose

**状态**：已采纳

**背景**

技术栈选择。

**决策**

Kotlin + Jetpack Compose。

**理由**

| 方案 | 优势 | 劣势 |
| --- | --- | --- |
| **Compose** | 状态驱动、代码简洁、动画原生 | 学习曲线、编译稍慢 |
| XML View | 成熟、稳定、内存低 | 样板代码多、状态管理繁琐 |
| Flutter | 跨平台 | 悬浮窗/无障碍仍需原生桥接，反而更绕 |

本项目的核心复杂度在**系统服务集成**（无障碍、悬浮窗、剪贴板），不在 UI 本身。Compose 让 UI 代码最短，把精力留给服务层。

**后果**

- 正面：`TranslateUiState` 密封类 + `collectAsState()` 即可完成状态渲染
- 负面：Compose 编译较慢，首次构建约 2-3 分钟

---

## ADR-006：minSdk 26

**状态**：已采纳

**背景**

最低支持版本选择。

**决策**

minSdk 26（Android 8.0）。

**理由**

关键 API 的引入节点：

| API | 引入版本 | 本项目用途 |
| --- | --- | --- |
| `NotificationChannel` | 26 | 前台服务通知（必需） |
| `TYPE_APPLICATION_OVERLAY` | 26 | 悬浮窗（必需） |
| `AccessibilityService` 稳定 API | 26+ | 划词 |

若 minSdk 24：

- `NotificationChannel` 需走兼容分支
- 悬浮窗需用已废弃的 `TYPE_PHONE`
- 增加约 15% 的兼容代码

覆盖损失：Android 8.0 以下设备不足 1%。

**后果**

- 正面：无兼容分支，代码干净
- 负面：极老设备不支持（可忽略）

---

## ADR-007：不做 OCR

**状态**：已采纳

**背景**

STranslate 的截图 OCR 翻译是其亮点之一。

**决策**

v1.0 不做，留待 v2.0。

**理由**

Android 上 OCR 截图翻译的完整链路：

1. `MediaProjection` 请求录屏权限（用户每次都要确认）
2. 前台服务捕获屏幕帧
3. 用户框选区域（需全屏覆盖层）
4. ML Kit 识别文字
5. 翻译
6. 译文覆盖显示（需再次精确对齐坐标）

这是一个**独立的完整模块**，工程量约等于核心翻译器的 1.5 倍。而手机上的使用频率远低于桌面（手机本身就能快速复制）。

**后果**

- 正面：v1.0 可快速交付
- 负面：缺少一个差异化功能
- 缓解：ML Kit 依赖已预留在 `build.gradle.kts` 注释中

---

## ADR-008：插件系统改为配置化引擎

**状态**：待定（v2.0 之后评估）

**背景**

STranslate 的插件系统是其最大特色——19 个官方插件 + 社区可自由扩展。Android 上如何实现？

**分析**

| 方案 | 可行性 | 说明 |
| --- | --- | --- |
| DEX 动态加载 | ⚠️ 低 | Android 9+ 限制 `DexClassLoader` 加载外部 DEX，需 `WRITE_EXTERNAL_STORAGE` 且 Play 政策不允许 |
| WebView + JS | ✅ 中 | 用户写 JS 脚本解析 HTTP，沙箱内执行 |
| **配置化引擎** | ✅ 高 | 用户填 URL + 请求模板 + 响应路径，无代码 |
| Lua 脚本引擎 | ✅ 高 | 嵌入 LuaJ，能力强但需学习语法 |

**倾向方案：配置化引擎**

用户配置示例：

```json
{
  "id": "my-engine",
  "name": "我的翻译服务",
  "request": {
    "url": "https://api.example.com/translate",
    "method": "POST",
    "headers": { "Authorization": "Bearer {apiKey}" },
    "body": {
      "text": "{text}",
      "from": "{source}",
      "to": "{target}"
    }
  },
  "response": {
    "path": "data.result",
    "type": "string"
  }
}
```

**优势**

- 覆盖 90% 的自建/第三方翻译 API
- 用户无需写代码
- 可导出分享配置

**劣势**

- 无法处理需要复杂预处理的服务（如需要签名、token 交换）
- JSON path 表达能力有限

**状态**

v1.0 不实现。若 v1.1 后有明确需求再评估。

---

## ADR-009：参考 STranslate 的并发隔离设计

**状态**：已采纳

**背景**

用户在输入框快速打字时，每次输入变化都触发翻译。旧请求可能比新请求后返回，导致「后发先至」——显示的是上一次输入的结果。

**STranslate 的解法**

`TranslationOperation` 对象：

```csharp
var operation = _translationCoordinator.BeginAutomaticOperation(
    InputText, SourceLang, TargetLang, ct);

ResetAllServices(operation);
var result = await operation.TranslateAsync(plugin, source, target);

if (!result.IsSuccess || !operation.IsCurrent(plugin.TransResult))
    return;  // 已过期，丢弃结果
```

三个关键点：

1. **每次请求创建新操作对象**，持有递增 ID
2. **插件写临时结果**，不直接写 UI 绑定的结果对象
3. **发布前检查 `IsCurrent()`**，过期则丢弃

**本项目的实现**

```kotlin
class TranslateViewModel : ViewModel() {
    private var currentJob: Job? = null
    private var operationCounter = 0L

    fun translate(text: String, source: LangEnum, target: LangEnum) {
        currentJob?.cancel()

        val opId = ++operationCounter
        currentJob = viewModelScope.launch {
            _uiState.value = TranslateUiState.Translating(text)

            val outcome = engine.translate(text, source, target, config)

            // 关键：检查是否已被新请求取代
            if (opId != operationCounter) return@launch

            _uiState.value = when (outcome) {
                is TranslateOutcome.Success -> TranslateUiState.Success(...)
                is TranslateOutcome.Failure -> TranslateUiState.Error(...)
            }
        }
    }
}
```

**理由**

这是**必须复刻**的设计。Android 上更容易出现此问题，因为：

- 触屏输入速度快（连续打字）
- 网络延迟波动大（移动网络切换）
- 防抖只在输入时生效，不保护已发出的请求

**后果**

- 正面：结果永远对应最新输入
- 负面：需注意 `opId` 的比较时机（必须在 `await` 之后）

---

## ADR-010：历史记录用 Room 而非 DataStore

**状态**：已采纳

**背景**

需要存储翻译历史，支持搜索、分页、收藏。

**决策**

Room（SQLite 封装）。

**理由**

| 方案 | 搜索 | 分页 | 结构化查询 | 适用规模 |
| --- | --- | --- | --- | --- |
| DataStore | ❌ 需全量加载后过滤 | ❌ | ❌ | < 100 条 |
| **Room** | ✅ SQL LIKE | ✅ LIMIT/OFFSET | ✅ | 无限制 |
| 内存列表 + 文件 | ⚠️ 手动 | ⚠️ 手动 | ❌ | < 500 条 |

历史记录典型规模：数千条。DataStore 每次读取要反序列化整个 JSON，不可接受。

**后果**

- 正面：SQL 查询高效，`Flow` 自动推送变更
- 负面：需要定义 Entity/Dao/Database，代码量增加约 200 行

---

*最后更新：2026-10-21*

