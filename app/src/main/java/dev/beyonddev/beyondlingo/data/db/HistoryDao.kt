package dev.beyonddev.beyondlingo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    /** 最近历史（时间倒序） */
    @Query("SELECT * FROM history ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<HistoryEntity>>

    /** 全文搜索（原文 + 译文） */
    @Query(
        """
        SELECT * FROM history
        WHERE source_text LIKE '%' || :query || '%'
           OR target_text LIKE '%' || :query || '%'
        ORDER BY created_at DESC
        LIMIT 200
        """
    )
    fun search(query: String): Flow<List<HistoryEntity>>

    /** 收藏列表 */
    @Query("SELECT * FROM history WHERE is_favorite = 1 ORDER BY created_at DESC")
    fun observeFavorites(): Flow<List<HistoryEntity>>

    /** 查询已存在的相同翻译（用于去重/命中检测） */
    @Query(
        """
        SELECT * FROM history
        WHERE source_text = :sourceText
          AND source_lang = :sourceLang
          AND target_lang = :targetLang
          AND engine_id = :engineId
        ORDER BY created_at DESC
        LIMIT 1
        """
    )
    suspend fun findExisting(
        sourceText: String,
        sourceLang: String,
        targetLang: String,
        engineId: String,
    ): HistoryEntity?

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    /** 同内容同语言同引擎则更新，而非新增 */
    @Insert
    suspend fun insertAll(entities: List<HistoryEntity>)

    @Query("UPDATE history SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    /** 超出上限时删除最旧的记录 */
    @Query(
        """
        DELETE FROM history WHERE id IN (
            SELECT id FROM history
            WHERE is_favorite = 0
            ORDER BY created_at ASC
            LIMIT :overflow
        )
        """
    )
    suspend fun trimOldest(overflow: Int)

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int
}
