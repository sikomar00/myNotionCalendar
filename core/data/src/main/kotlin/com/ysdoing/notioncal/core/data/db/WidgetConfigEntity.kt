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
    /** LD-06 가계부 금액 숨김(●●●, v10). */
    @ColumnInfo(defaultValue = "0") val hideAmounts: Boolean = false,
    /** DD-03·04 디데이 위젯이 보여 줄 고정 일정(v11). */
    val ddayPageId: String? = null,
    /** WX-04 머리에 오늘 날씨(v11). */
    @ColumnInfo(defaultValue = "0") val showWeather: Boolean = false,
    /** VW-04 보기 위젯이 보여 줄 등록한 보기(v13). */
    val viewId: String? = null,
    /** v17(Phase 11a): 보드 위젯이 보여 주는 첫 열의 위치(‹ ›로 넘긴다). 다른 위젯은 쓰지 않는다. */
    @ColumnInfo(defaultValue = "0") val viewPage: Int = 0,
    /** v18(Phase 11c IX-03): 숫자 ± 한 번에 더하고 빼는 값. */
    @ColumnInfo(defaultValue = "1.0") val numberStep: Double = 1.0,
    /** v18(Phase 11c AD-05): 프리셋 목록(JSON, 위젯당 최대 5개). 없으면 빈 글자. */
    @ColumnInfo(defaultValue = "") val presetsJson: String = "",
    /** v19(Phase 11d WL-01): 위젯 배치(`WidgetLayout` JSON: 단일·탭·섹션과 보기 목록). 없으면 빈 글자. */
    @ColumnInfo(defaultValue = "") val layoutJson: String = "",
    /** v19(WL-02): 탭 배치에서 마지막으로 본 탭 번호(위젯마다 기억). */
    @ColumnInfo(defaultValue = "0") val lastTab: Int = 0,
    /** v20(Phase 11g AD-02): 보기별 새 항목 입력 양식(`InputForms` JSON: 보기 ID → 속성·순서·기본값·날짜 칩). 없으면 빈 글자. */
    @ColumnInfo(defaultValue = "") val formJson: String = "",
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
