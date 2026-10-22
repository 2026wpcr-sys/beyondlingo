package dev.beyonddev.beyondlingo.data.engine

/**
 * 翻译引擎统一接口。
 *
 * 设计参照 STranslate 的 `ITranslatePlugin`（MIT），但做了 Android 化调整：
 * - `TranslateResult` 改为 sealed interface，强制调用方处理失败分支
 * - 引擎配置通过参数传入而非引擎内部持状态，便于测试与多实例
 * - 去掉 WPF 的 `Control GetSettingUI()`，改为纯数据配置
 */
interface TranslateEngine {

    /** 引擎唯一标识，用于持久化与注册表查找 */
    val id: String

    /** 显示名称 */
    val displayName: String

    /** 是否需要用户提供 API Key */
    val requiresApiKey: Boolean

    /** 是否支持某个语言 */
    fun supports(lang: LangEnum): Boolean

    /** 该引擎支持的全部目标语言 */
    fun supportedTargets(): List<LangEnum>

    /**
     * 执行翻译。
     *
     * 实现约定：**不抛异常**，所有错误都通过 [TranslateOutcome.Failure] 返回。
     * 这样调用方无需 try/catch，状态机更清晰。
     */
    suspend fun translate(
        text: String,
        source: LangEnum,
        target: LangEnum,
        config: EngineConfig,
    ): TranslateOutcome
}

/** 翻译结果 */
sealed interface TranslateOutcome {

    /** 成功 */
    data class Success(
        val text: String,
        /** 引擎返回的实际检测语言（部分引擎支持） */
        val detectedSource: LangEnum? = null,
        /** 耗时（毫秒），由调用方填充 */
        val elapsedMs: Long = 0L,
    ) : TranslateOutcome

    /** 失败 */
    data class Failure(
        /** 面向用户的错误信息 */
        val message: String,
        /** 原始异常，用于日志 */
        val cause: Throwable? = null,
        /**
         * 是否可重试：
         * - true  → 网络抖动、超时，UI 显示「重试」
         * - false → 配置错误、语言不支持，UI 显示「去设置」
         */
        val retryable: Boolean = false,
    ) : TranslateOutcome
}

/** 引擎运行时配置 */
data class EngineConfig(
    /** API Key，部分引擎可为空 */
    val apiKey: String = "",
    /** 自定义 endpoint，OpenAI 兼容 / 自定义引擎必填 */
    val baseUrl: String = "",
    /** 模型名，OpenAI 兼容 / 自定义引擎必填 */
    val model: String = "",
    /** 自定义 system prompt，留空使用默认 */
    val systemPrompt: String = "",
    /** 请求路径，如 `/chat/completions`；留空按 OpenAI 惯例自动补全 */
    val apiPath: String = "",
    /** 鉴权头名称，默认 `Authorization` */
    val authHeader: String = "",
    /** 鉴权前缀，默认 `Bearer ` */
    val authPrefix: String = "",
    /** 额外请求头，每行 `key:value` */
    val extraHeaders: String = "",
) {
    companion object {
        val EMPTY = EngineConfig()
    }
}

/** 引擎错误类型，统一文案 */
object EngineErrors {
    const val NETWORK = "网络连接失败，请检查网络后重试"
    const val TIMEOUT = "请求超时，请稍后重试"
    const val EMPTY_RESULT = "引擎返回了空结果"
    const val PARSE_FAILED = "无法解析引擎响应"
    const val MISSING_API_KEY = "请先在设置中填写 API Key"
    const val MISSING_BASE_URL = "请先在设置中填写服务地址"
    const val MISSING_MODEL = "请先在设置中填写模型名称"
    const val UNSUPPORTED_SOURCE = "该引擎不支持所选源语言"
    const val UNSUPPORTED_TARGET = "该引擎不支持所选目标语言"
}
