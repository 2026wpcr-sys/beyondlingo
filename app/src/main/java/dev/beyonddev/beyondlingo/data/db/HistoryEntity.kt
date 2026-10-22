package dev.beyonddev.beyondlingo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 翻译历史记录。
 *
 * 字段设计参照 STranslate 的 `HistoryData`：
 * - 保留 `detectedLang`（对应 `EffectiveSourceLang`），记录自动检测的实际语言
 * - 保留 `engineId`（对应 `ServiceDisplayName` 的用途），避免引擎删除后历史失效
 */
@Entity(
    tableName = "history",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["is_favorite"]),
    ],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 原文 */
    @androidx.room.ColumnInfo(name = "source_text")
    val sourceText: String,

    /** 译文 */
    @androidx.room.ColumnInfo(name = "target_text")
    val targetText: String,

    /** 源语言代码（LangEnum.code） */
    @androidx.room.ColumnInfo(name = "source_lang")
    val sourceLang: String,

    /** 目标语言代码 */
    @androidx.room.ColumnInfo(name = "target_lang")
    val targetLang: String,

    /** 实际检测到的源语言代码（自动检测时有效） */
    @androidx.room.ColumnInfo(name = "detected_lang")
    val detectedLang: String? = null,

    /** 使用的引擎 ID */
    @androidx.room.ColumnInfo(name = "engine_id")
    val engineId: String,

    /** 是否收藏 */
    @androidx.room.ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    /** 翻译耗时（毫秒） */
    @androidx.room.ColumnInfo(name = "elapsed_ms")
    val elapsedMs: Long = 0L,

    /** 创建时间戳 */
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)
