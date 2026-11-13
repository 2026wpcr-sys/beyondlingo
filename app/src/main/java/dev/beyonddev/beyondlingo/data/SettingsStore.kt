package dev.beyonddev.beyondlingo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.beyonddev.beyondlingo.data.engine.EngineConfig
import dev.beyonddev.beyondlingo.data.engine.EngineRegistry
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 应用设置。
 *
 * 字段对应 STranslate 的设置项，见 docs/ADR.md 与 PROJECT.md。
 */
data class AppSettings(
    val engineId: String = EngineRegistry.DEFAULT_ENGINE_ID,

    /** 各引擎配置，key = engineId */
    val engineConfigs: Map<String, EngineConfig> = emptyMap(),

    val defaultSourceLang: LangEnum = LangEnum.Auto,
    val defaultTargetLang: LangEnum = LangEnum.ChineseSimplified,

    /**
     * 双语模式：只声明「母语」与「对方语言」，由 [dev.beyonddev.beyondlingo.util.DirectionResolver]
     * 按输入内容自动判定翻译方向。
     *
     * 开启后 [defaultSourceLang] / [defaultTargetLang] 不再直接决定方向，
     * 但仍作为「最近一次有效方向」被写回，便于退出双语模式时恢复。
     */
    val bilingualMode: Boolean = false,
    /** 双语模式下的母语（通常是中文） */
    val homeLang: LangEnum = LangEnum.ChineseSimplified,
    /** 双语模式下的对方语言 */
    val foreignLang: LangEnum = LangEnum.Spanish,

    /** 输入后自动翻译 */
    val autoTranslate: Boolean = true,
    /** 自动翻译防抖延迟 */
    val autoTranslateDelayMs: Long = 800L,

    /** 翻译成功后自动复制 */
    val copyAfterTranslate: Boolean = false,

    /** 历史上限，0 = 不限制 */
    val historyLimit: Int = 1000,

    /** 无障碍划词 */
    val selectionTranslateEnabled: Boolean = true,
    /** 静默自动翻译：划词/复制后直接出结果，不弹悬浮窗 */
    val silentAutoTranslate: Boolean = false,

    /** 悬浮窗自动消失 */
    val floatingAutoDismiss: Boolean = true,
    val floatingAutoDismissMs: Long = 5000L,

    /** 常驻悬浮球：复制文字后点球即翻译（最可靠的取词方式） */
    val bubbleEnabled: Boolean = false,

    /** 主题：system / light / dark */
    val themeMode: String = "system",
    /** 当前使用的离线 ASR 模型 id；空 = 用内置默认（whisper-base-int8） */
    val asrModelId: String = "",
    /** 云端 ASR（Groq）API Key；空 = 未配置，云端档不可用 */
    val cloudAsrApiKey: String = "",
    /** 云端 ASR 模型 id，默认 whisper-large-v3 */
    val cloudAsrModel: String = "whisper-large-v3",
    /** Azure TTS API Key；空 = 未配置，朗读走本地 Matcha */
    val azureTtsKey: String = "",
    /** Azure TTS 区域（如 eastus / westus2），须与 Key 所在资源匹配 */
    val azureTtsRegion: String = "eastus",
) {
    fun configFor(engineId: String): EngineConfig =
        engineConfigs[engineId] ?: EngineConfig.EMPTY
}

/** 读写 DataStore 的封装 */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            engineId = prefs[KEY_ENGINE_ID] ?: EngineRegistry.DEFAULT_ENGINE_ID,
            engineConfigs = readEngineConfigs(prefs),
            defaultSourceLang = LangEnum.fromCode(prefs[KEY_SOURCE_LANG]),
            defaultTargetLang = LangEnum.fromCode(prefs[KEY_TARGET_LANG])
                .takeIf { it != LangEnum.Auto } ?: LangEnum.ChineseSimplified,
            bilingualMode = prefs[KEY_BILINGUAL] ?: false,
            homeLang = LangEnum.fromCode(prefs[KEY_HOME_LANG])
                .takeIf { it != LangEnum.Auto } ?: LangEnum.ChineseSimplified,
            foreignLang = LangEnum.fromCode(prefs[KEY_FOREIGN_LANG])
                .takeIf { it != LangEnum.Auto } ?: LangEnum.Spanish,
            autoTranslate = prefs[KEY_AUTO_TRANSLATE] ?: true,
            autoTranslateDelayMs = prefs[KEY_AUTO_DELAY] ?: 800L,
            copyAfterTranslate = prefs[KEY_COPY_AFTER] ?: false,
            historyLimit = prefs[KEY_HISTORY_LIMIT] ?: 1000,
            selectionTranslateEnabled = prefs[KEY_SELECTION] ?: true,
            silentAutoTranslate = prefs[KEY_SILENT_AUTO] ?: false,
            floatingAutoDismiss = prefs[KEY_FLOAT_DISMISS] ?: true,
            floatingAutoDismissMs = prefs[KEY_FLOAT_DISMISS_MS] ?: 5000L,
            bubbleEnabled = prefs[KEY_BUBBLE] ?: false,
            themeMode = prefs[KEY_THEME] ?: "system",
            asrModelId = prefs[KEY_ASR_MODEL].orEmpty(),
            cloudAsrApiKey = prefs[KEY_CLOUD_ASR_KEY].orEmpty(),
            cloudAsrModel = prefs[KEY_CLOUD_ASR_MODEL]
                ?.takeIf { it.isNotBlank() }
                ?: dev.beyonddev.beyondlingo.data.asr.CloudAsrEngine.DEFAULT_MODEL,
            azureTtsKey = prefs[KEY_AZURE_TTS_KEY].orEmpty(),
            azureTtsRegion = prefs[KEY_AZURE_TTS_REGION]
                ?.takeIf { it.isNotBlank() }
                ?: "eastus",
        )
    }

    suspend fun setEngineId(id: String) = edit { it[KEY_ENGINE_ID] = id }

    suspend fun setEngineConfig(engineId: String, config: EngineConfig) = edit { prefs ->
        prefs[stringPreferencesKey("cfg_${engineId}_key")] = config.apiKey
        prefs[stringPreferencesKey("cfg_${engineId}_url")] = config.baseUrl
        prefs[stringPreferencesKey("cfg_${engineId}_model")] = config.model
        prefs[stringPreferencesKey("cfg_${engineId}_prompt")] = config.systemPrompt
        prefs[stringPreferencesKey("cfg_${engineId}_path")] = config.apiPath
        prefs[stringPreferencesKey("cfg_${engineId}_authhdr")] = config.authHeader
        prefs[stringPreferencesKey("cfg_${engineId}_authpfx")] = config.authPrefix
        prefs[stringPreferencesKey("cfg_${engineId}_headers")] = config.extraHeaders

        prefs[stringPreferencesKey("cfg_${engineId}_preset")] = config.presetName
    }

    suspend fun setSourceLang(lang: LangEnum) = edit { it[KEY_SOURCE_LANG] = lang.code }
    suspend fun setTargetLang(lang: LangEnum) = edit { it[KEY_TARGET_LANG] = lang.code }
    suspend fun setBilingualMode(enabled: Boolean) = edit { it[KEY_BILINGUAL] = enabled }
    suspend fun setHomeLang(lang: LangEnum) = edit { it[KEY_HOME_LANG] = lang.code }
    suspend fun setForeignLang(lang: LangEnum) = edit { it[KEY_FOREIGN_LANG] = lang.code }
    suspend fun setAutoTranslate(enabled: Boolean) = edit { it[KEY_AUTO_TRANSLATE] = enabled }
    suspend fun setAutoTranslateDelay(ms: Long) = edit { it[KEY_AUTO_DELAY] = ms }
    suspend fun setCopyAfterTranslate(enabled: Boolean) = edit { it[KEY_COPY_AFTER] = enabled }
    suspend fun setHistoryLimit(limit: Int) = edit { it[KEY_HISTORY_LIMIT] = limit }
    suspend fun setSelectionTranslate(enabled: Boolean) = edit { it[KEY_SELECTION] = enabled }
    suspend fun setSilentAutoTranslate(enabled: Boolean) = edit { it[KEY_SILENT_AUTO] = enabled }
    suspend fun setFloatingAutoDismiss(enabled: Boolean) = edit { it[KEY_FLOAT_DISMISS] = enabled }
    suspend fun setFloatingAutoDismissMs(ms: Long) = edit { it[KEY_FLOAT_DISMISS_MS] = ms }
    suspend fun setBubbleEnabled(enabled: Boolean) = edit { it[KEY_BUBBLE] = enabled }
    suspend fun setThemeMode(mode: String) = edit { it[KEY_THEME] = mode }

    suspend fun setAsrModelId(id: String) = edit { it[KEY_ASR_MODEL] = id }

    suspend fun setCloudAsrApiKey(key: String) = edit { it[KEY_CLOUD_ASR_KEY] = key }

    suspend fun setCloudAsrModel(model: String) = edit { it[KEY_CLOUD_ASR_MODEL] = model }

    suspend fun setAzureTts(key: String, region: String) = edit {
        it[KEY_AZURE_TTS_KEY] = key
        it[KEY_AZURE_TTS_REGION] = region
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun readEngineConfigs(prefs: Preferences): Map<String, EngineConfig> {
        val result = mutableMapOf<String, EngineConfig>()
        for (engine in EngineRegistry.all()) {
            val id = engine.id
            val key = prefs[stringPreferencesKey("cfg_${id}_key")].orEmpty()
            val url = prefs[stringPreferencesKey("cfg_${id}_url")].orEmpty()
            val model = prefs[stringPreferencesKey("cfg_${id}_model")].orEmpty()
            val prompt = prefs[stringPreferencesKey("cfg_${id}_prompt")].orEmpty()
            val path = prefs[stringPreferencesKey("cfg_${id}_path")].orEmpty()
            val authHdr = prefs[stringPreferencesKey("cfg_${id}_authhdr")].orEmpty()
            val authPfx = prefs[stringPreferencesKey("cfg_${id}_authpfx")].orEmpty()
            val headers = prefs[stringPreferencesKey("cfg_${id}_headers")].orEmpty()

            val presetName = prefs[stringPreferencesKey("cfg_${id}_preset")].orEmpty()

            val hasAny = key.isNotEmpty() || url.isNotEmpty() || model.isNotEmpty() ||
                prompt.isNotEmpty() || path.isNotEmpty()
            if (hasAny) {
                result[id] = EngineConfig(
                    apiKey = key,
                    baseUrl = url,
                    model = model,
                    systemPrompt = prompt,
                    apiPath = path,
                    authHeader = authHdr,
                    authPrefix = authPfx,
                    extraHeaders = headers,

                    presetName = presetName,
                )
            }
        }
        return result
    }

    private companion object {
        val KEY_ENGINE_ID = stringPreferencesKey("engine_id")
        val KEY_SOURCE_LANG = stringPreferencesKey("source_lang")
        val KEY_TARGET_LANG = stringPreferencesKey("target_lang")
        val KEY_BILINGUAL = booleanPreferencesKey("bilingual_mode")
        val KEY_HOME_LANG = stringPreferencesKey("home_lang")
        val KEY_FOREIGN_LANG = stringPreferencesKey("foreign_lang")
        val KEY_AUTO_TRANSLATE = booleanPreferencesKey("auto_translate")
        val KEY_AUTO_DELAY = longPreferencesKey("auto_delay_ms")
        val KEY_COPY_AFTER = booleanPreferencesKey("copy_after")
        val KEY_HISTORY_LIMIT = intPreferencesKey("history_limit")
        val KEY_SELECTION = booleanPreferencesKey("selection_translate")
        val KEY_SILENT_AUTO = booleanPreferencesKey("silent_auto_translate")
        val KEY_FLOAT_DISMISS = booleanPreferencesKey("floating_dismiss")
        val KEY_FLOAT_DISMISS_MS = longPreferencesKey("floating_dismiss_ms")
        val KEY_BUBBLE = booleanPreferencesKey("bubble_enabled")
        val KEY_THEME = stringPreferencesKey("theme_mode")

        /** 当前使用的离线 ASR 模型 id（空 = 用内置默认） */
        val KEY_ASR_MODEL = stringPreferencesKey("asr_model_id")

        /** 云端 ASR（Groq）API Key */
        val KEY_CLOUD_ASR_KEY = stringPreferencesKey("cloud_asr_api_key")

        /** 云端 ASR 模型 id */
        val KEY_CLOUD_ASR_MODEL = stringPreferencesKey("cloud_asr_model")

        /** Azure TTS API Key */
        val KEY_AZURE_TTS_KEY = stringPreferencesKey("azure_tts_key")

        /** Azure TTS 区域 */
        val KEY_AZURE_TTS_REGION = stringPreferencesKey("azure_tts_region")
    }
}
