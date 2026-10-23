package dev.beyonddev.beyondlingo.data

import android.content.Context
import dev.beyonddev.beyondlingo.data.db.AppDatabase
import dev.beyonddev.beyondlingo.data.db.HistoryEntity
import dev.beyonddev.beyondlingo.data.engine.EngineConfig
import dev.beyonddev.beyondlingo.data.engine.EngineRegistry
import dev.beyonddev.beyondlingo.data.engine.LangEnum
import dev.beyonddev.beyondlingo.data.engine.TranslateOutcome
import kotlinx.coroutines.flow.first

/**
 * 翻译仓库：编排引擎调用 + 历史缓存。
 *
 * 对应 STranslate 的 `TranslateService` + `SqlService` 的组合职责。
 *
 * 缓存策略（参照 `ExecuteTranslateAsync(checkCacheFirst: true)`）：
 * 先用 (text, sourceLang, targetLang, engineId) 查历史，命中则直接返回，
 * 未命中才请求引擎。
 */
class TranslateRepository(context: Context) {

    private val dao = AppDatabase.get(context).historyDao()
    private val settingsStore = SettingsStore(context)

    /**
     * 执行翻译。
     *
     * @param checkCacheFirst 是否优先查缓存。用户手动重试时应传 false 强制请求。
     */
    suspend fun translate(
        text: String,
        source: LangEnum,
        target: LangEnum,
        engineId: String,
        config: EngineConfig,
        checkCacheFirst: Boolean = true,
    ): TranslateOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return TranslateOutcome.Failure("请输入要翻译的文本")
        }

        // 防御：源语言与目标语言相同时，翻译毫无意义（引擎会原样返回）。
        // 这种情况通常来自用户误设源语言，或划词时文本与预设语言不符。
        // 自动降级为「自动检测源语言」，让引擎自己判断。
        val effectiveSource = if (source != LangEnum.Auto && source.code == target.code) {
            LangEnum.Auto
        } else {
            source
        }

        val engine = EngineRegistry.get(engineId)

        // 1. 查缓存（DB 异常不应中断翻译）
        if (checkCacheFirst) {
            val cached = try {
                dao.findExisting(
                    sourceText = trimmed,
                    sourceLang = effectiveSource.code,
                    targetLang = target.code,
                    engineId = engineId,
                )
            } catch (_: Exception) {
                null
            }
            if (cached != null) {
                return TranslateOutcome.Success(
                    text = cached.targetText,
                    detectedSource = LangEnum.fromCode(cached.detectedLang),
                    elapsedMs = 0L,
                )
            }
        }

        // 2. 调引擎（引擎内部已兜底，这里再加一层保险）
        val started = System.currentTimeMillis()
        val outcome = try {
            engine.translate(trimmed, effectiveSource, target, config)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            TranslateOutcome.Failure(
                message = t.message ?: "翻译失败，请重试",
                cause = t,
                retryable = true,
            )
        }

        // 3. 成功则落库
        if (outcome is TranslateOutcome.Success) {
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
            persist(trimmed, outcome, effectiveSource, target, engineId, elapsed)
            return outcome.copy(elapsedMs = elapsed)
        }

        return outcome
    }

    private suspend fun persist(
        sourceText: String,
        outcome: TranslateOutcome.Success,
        source: LangEnum,
        target: LangEnum,
        engineId: String,
        elapsedMs: Long,
    ) {
        try {
            dao.insert(
                HistoryEntity(
                    sourceText = sourceText,
                    targetText = outcome.text,
                    sourceLang = source.code,
                    targetLang = target.code,
                    detectedLang = outcome.detectedSource?.code,
                    engineId = engineId,
                    elapsedMs = elapsedMs,
                )
            )

            // 超限裁剪（保留收藏）
            val limit = currentHistoryLimit()
            if (limit > 0) {
                val total = dao.count()
                if (total > limit) {
                    dao.trimOldest(total - limit)
                }
            }
        } catch (_: Exception) {
            // 落库失败不影响翻译结果返回
        }
    }

    private suspend fun currentHistoryLimit(): Int =
        settingsStore.settings.first().historyLimit

    /** 供外部（悬浮窗等）直接调用的简化入口，自动读取当前设置 */
    suspend fun translateWithCurrentSettings(
        text: String,
        checkCacheFirst: Boolean = true,
    ): TranslateOutcome {
        val settings = settingsStore.settings.first()
        val engineId = settings.engineId
        val config = settings.configFor(engineId)

        return translate(
            text = text,
            source = settings.defaultSourceLang,
            target = settings.defaultTargetLang,
            engineId = engineId,
            config = config,
            checkCacheFirst = checkCacheFirst,
        )
    }
}
