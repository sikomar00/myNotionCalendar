package com.ysdoing.notioncal.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 위젯별 설정과 보고 있는 달(PRD §10, WG-04, WI-04). */
@Entity(tableName = "widget_configs")
data class WidgetConfigEntity(
    @PrimaryKey val appWidgetId: Int,
    /** 오늘이 속한 달 기준 이동량. 0이면 항상 이번 달을 보여준다. */
    val monthOffset: Int = 0,
    /** 표시할 data source ID(쉼표 구분). 빈 값이면 앱에서 켠 DB 전체. */
    val dataSourceIds: String = "",
    /** "SYSTEM", "LIGHT", "DARK" */
    val theme: String = "SYSTEM",
    /** 배경 불투명도 0~100. */
    val opacity: Int = 100,
    /** 글자 배율 70~150(%). */
    val fontScale: Int = 100,
    /** 칸당 일정 수. 0이면 높이에 맞춰 자동. */
    val maxPerCell: Int = 0,
    /** "COMPACT", "NORMAL", "WIDE" */
    val lineSpacing: String = "NORMAL",
    val hideDone: Boolean = false,
    /** CAL-03(P1)용 자리. */
    val showPhoneCal: Boolean = false,
    /** WG-11 통합 위젯에서 고른 탭("CALENDAR"·"HABIT", v9). 다른 위젯은 쓰지 않는다. */
    @ColumnInfo(defaultValue = "CALENDAR") val selectedTab: String = "CALENDAR",
)

@Dao
interface WidgetConfigDao {
    @Query("SELECT * FROM widget_configs WHERE appWidgetId = :id")
    suspend fun get(id: Int): WidgetConfigEntity?

    @Query("SELECT * FROM widget_configs WHERE appWidgetId = :id")
    fun observe(id: Int): Flow<WidgetConfigEntity?>

    @Upsert
    suspend fun upsert(config: WidgetConfigEntity)

    @Query("DELETE FROM widget_configs WHERE appWidgetId IN (:ids)")
    suspend fun delete(ids: List<Int>)
}
