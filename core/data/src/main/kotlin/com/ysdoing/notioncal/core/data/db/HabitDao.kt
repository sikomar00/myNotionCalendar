package com.ysdoing.notioncal.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** HB-01 🌱 습관 캐시. */
@Dao
interface HabitDao {
    /** HB-04: 켜 둔 DB의 '진행 중' 습관만. 순서(비면 뒤) → 이름순. */
    @Query(
        """
        SELECT h.* FROM habits h JOIN data_sources d ON h.dataSourceId = d.dataSourceId
        WHERE d.enabled = 1 AND h.active = 1
        ORDER BY h.sortOrder IS NULL, h.sortOrder, h.name
        """,
    )
    fun observeActive(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE pageId = :pageId")
    suspend fun get(pageId: String): HabitEntity?

    @Query("SELECT * FROM habits WHERE dataSourceId = :dataSourceId")
    suspend fun forSource(dataSourceId: String): List<HabitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(habits: List<HabitEntity>)

    @Query("DELETE FROM habits WHERE dataSourceId = :dataSourceId")
    suspend fun deleteForSource(dataSourceId: String)

    @Query("DELETE FROM habits")
    suspend fun deleteAll()

    /** 전체 조회 결과로 그 DB의 습관을 통째로 바꾼다(노션에서 지운 습관도 빠진다). */
    @Transaction
    suspend fun replaceForSource(dataSourceId: String, habits: List<HabitEntity>) {
        deleteForSource(dataSourceId)
        insert(habits)
    }
}
