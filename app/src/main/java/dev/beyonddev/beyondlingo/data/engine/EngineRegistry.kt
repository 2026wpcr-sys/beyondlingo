package dev.beyonddev.beyondlingo.data.engine

/**
 * 引擎注册表。
 *
 * 单例，负责按 [TranslateEngine.id] 查找引擎实例。
 * 引擎实例有状态（如 YandexEngine 的 ucid 缓存），因此这里保持单例复用。
 *
 * 当前引擎：
 * - [YandexEngine]  免费直连，免配置，默认引擎
 * - [CustomEngine]  完全自定义（内置智谱 / DeepSeek 等预设）
 */
object EngineRegistry {

    /** 默认引擎 ID：Yandex 免配置，开箱可用 */
    const val DEFAULT_ENGINE_ID = "yandex"

    /** 展示顺序即此列表顺序 */
    private val ORDER = listOf("yandex", "custom")

    private val engines: Map<String, TranslateEngine> by lazy {
        listOf(
            YandexEngine(),
            CustomEngine(),
        ).associateBy { it.id }
    }

    /** 全部引擎，按展示顺序 */
    fun all(): List<TranslateEngine> = ORDER.mapNotNull { engines[it] }

    /** 按 ID 查找，找不到回退到默认引擎 */
    fun get(id: String?): TranslateEngine =
        engines[id] ?: engines.getValue(DEFAULT_ENGINE_ID)

    /** 按 ID 查找，找不到返回 null */
    fun getOrNull(id: String?): TranslateEngine? = engines[id]

    /** ID 是否已注册 */
    fun contains(id: String): Boolean = engines.containsKey(id)
}
