package com.ysdoing.notioncal.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** LD-05 예산 DB 한 행(v10). 노션에서 날짜 없이 전체 조회해 DB별로 통째로 바꾸는 캐시다. */
@Entity(tableName = "budgets", indices = [Index("dataSourceId")])
data class BudgetEntity(
    @PrimaryKey val pageId: String,
    val dataSourceId: String,
    /** 노션 '기간유형' 옵션 이름(연·분기·월·주). */
    val periodType: String,
    /** '기간값'(2026-10, 2026-W43). */
    val periodKey: String,
    val groupName: String?,
    /** 예산금액(원). 비어 있으면 null(LD-05: 최근 값을 이어 쓴다). */
    val amount: Long?,
)

/** LD-01 카테고리 마스터 한 행(v10): 대분류 → 그룹. 캐시다. */
@Entity(tableName = "category_groups", indices = [Index("dataSourceId"), Index("category")])
data class CategoryGroupEntity(
    @PrimaryKey val pageId: String,
    val dataSourceId: String,
    val category: String,
    val groupName: String?,
)

/** LD-08 중복 후보 중 사용자가 '둘 다 유지'로 고른 쌍(v10). 앱 상태라 DB 복구 때 남긴다. [pageIdA] < [pageIdB]로 저장한다. */
@Entity(tableName = "ledger_dup_keeps", primaryKeys = ["pageIdA", "pageIdB"])
data class LedgerDupKeepEntity(
    val pageIdA: String,
    val pageIdB: String,
    val at: Long,
)

@Dao
interface LedgerDao {
    /** 켜 둔 가계부(거래내역) DB의 [from]~[to] 거래. */
    @Query(
        """
        SELECT e.* FROM events e JOIN data_sources d ON e.dataSourceId = d.dataSourceId
        WHERE d.enabled = 1 AND d.kind = 'LEDGER' AND e.startDate BETWEEN :from AND :to
        """,
    )
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<EventEntity>>

    /** LD-09 반영 기준일: 가져온 거래(예정·앱입력이 아닌 행) 중 가장 늦은 날짜. */
    @Query(
        """
        SELECT MAX(e.startDate) FROM events e JOIN data_sources d ON e.dataSourceId = d.dataSourceId
        WHERE d.enabled = 1 AND d.kind = 'LEDGER' AND e.planned = 0 AND e.manual = 0
        """,
    )
    fun observeAsOf(): Flow<LocalDate?>

    /** LD-07 대분류 칩: 최근 쓴 순. */
    @Query(
        """
        SELECT e.category FROM events e JOIN data_sources d ON e.dataSourceId = d.dataSourceId
        WHERE d.kind = 'LEDGER' AND e.category IS NOT NULL AND e.flow = :flow
        GROUP BY e.category ORDER BY MAX(e.startDate) DESC, COUNT(*) DESC LIMIT :limit
        """,
    )
    suspend fun recentCategories(flow: String, limit: Int): List<String>

    @Query("SELECT b.* FROM budgets b JOIN data_sources d ON b.dataSourceId = d.dataSourceId WHERE d.enabled = 1")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    @Query("SELECT c.* FROM category_groups c JOIN data_sources d ON c.dataSourceId = d.dataSourceId WHERE d.enabled = 1")
    fun observeCategoryGroups(): Flow<List<CategoryGroupEntity>>

    @Query("SELECT * FROM ledger_dup_keeps")
    fun observeDupKeeps(): Flow<List<LedgerDupKeepEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDupKeep(keep: LedgerDupKeepEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudgets(rows: List<BudgetEntity>)

    @Query("DELETE FROM budgets WHERE dataSourceId = :dataSourceId")
    suspend fun deleteBudgets(dataSourceId: String)

    @Transaction
    suspend fun replaceBudgets(dataSourceId: String, rows: List<BudgetEntity>) {
        deleteBudgets(dataSourceId)
        insertBudgets(rows)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategoryGroups(rows: List<CategoryGroupEntity>)

    @Query("DELETE FROM category_groups WHERE dataSourceId = :dataSourceId")
    suspend fun deleteCategoryGroups(dataSourceId: String)

    @Transaction
    suspend fun replaceCategoryGroups(dataSourceId: String, rows: List<CategoryGroupEntity>) {
        deleteCategoryGroups(dataSourceId)
        insertCategoryGroups(rows)
    }

    /** 캐시 초기화·등록 삭제. */
    @Query("DELETE FROM budgets")
    suspend fun deleteAllBudgets()

    @Query("DELETE FROM category_groups")
    suspend fun deleteAllCategoryGroups()
}
