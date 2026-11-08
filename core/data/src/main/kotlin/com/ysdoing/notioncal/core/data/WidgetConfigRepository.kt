package com.ysdoing.notioncal.core.data

import com.ysdoing.notioncal.core.data.db.WidgetConfigDao
import com.ysdoing.notioncal.core.data.db.WidgetConfigEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 위젯 테마(WS-04). MATERIAL_YOU는 배경화면 색을 쓰고 다크 모드를 따른다(S25-10). */
enum class WidgetTheme { SYSTEM, LIGHT, DARK, MATERIAL_YOU }

enum class LineSpacing { COMPACT, NORMAL, WIDE }

/** WG-11 통합 위젯의 탭(📅 일정 · ✅ 습관 · 💰 가계부). */
enum class WidgetTab { CALENDAR, HABIT, LEDGER }

/** 위젯 하나의 설정(WG-04)과 보고 있는 달(WI-04). */
data class WidgetConfig(
    val appWidgetId: Int,
    val monthOffset: Int = 0,
    /** 비어 있으면 앱에서 켠 DB 전체. */
    val dataSourceIds: Set<String> = emptySet(),
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
    val opacity: Int = 100,
    val fontScale: Int = 100,
    val maxPerCell: Int = 0,
    val lineSpacing: LineSpacing = LineSpacing.NORMAL,
    val hideDone: Boolean = false,
    /** WG-11 통합 위젯에서 고른 탭. 저장 때 다른 값과 함께 쓰이므로 모델에 둬야 월 이동·설정 저장 뒤에도 남는다. */
    val selectedTab: WidgetTab = WidgetTab.CALENDAR,
    /** LD-06 가계부 금액 숨김(●●●, 막대만). */
    val hideAmounts: Boolean = false,
    /** DD-03·04 디데이 위젯이 보여 줄 고정 일정. null이면 가장 가까운 디데이. */
    val ddayPageId: String? = null,
    /** WX-04 머리에 오늘 날씨. */
    val showWeather: Boolean = false,
    /** VW-04 보기 위젯이 보여 줄 보기. null이면 아직 고르지 않음. */
    val viewId: String? = null,
    /** 보드 위젯의 열 넘김 위치(Phase 11a). */
    val viewPage: Int = 0,
    /** 숫자 ± 한 번에 더하고 빼는 값(Phase 11c IX-03). */
    val numberStep: Double = 1.0,
    /** 프리셋 목록 JSON(Phase 11c AD-05, 위젯당 최대 5개). 해석은 프리셋 모델이 한다. */
    val presetsJson: String = "",
    /** 위젯 배치 JSON(Phase 11d WL-01). 해석은 `WidgetLayout`이 한다. */
    val layoutJson: String = "",
    /** 탭 배치에서 마지막으로 본 탭 번호(WL-02). */
    val lastTab: Int = 0,
    /** 보기별 입력 양식 JSON(Phase 11g AD-02). 해석은 `InputForms`가 한다. */
    val formJson: String = "",
    /** 액션 버튼 목록 JSON(Phase 12c IX-07). 해석은 `ViewActions`가 한다. */
    val actionsJson: String = "",
    /** 타임라인 보기가 보는 창의 이동량(주 또는 달 단위, Phase 12). 캘린더의 [monthOffset]과 단위가 달라 따로 둔다. */
    val timelineOffset: Int = 0,
) {
    companion object {
        val FONT_SCALE_RANGE = 70..150
        val MAX_PER_CELL_RANGE = 0..6
    }
}

internal fun WidgetConfigEntity.toModel() = WidgetConfig(
    appWidgetId = appWidgetId,
    monthOffset = monthOffset,
    dataSourceIds = dataSourceIds.split(',').filter { it.isNotBlank() }.toSet(),
    theme = runCatching { WidgetTheme.valueOf(theme) }.getOrDefault(WidgetTheme.SYSTEM),
    opacity = opacity.coerceIn(0, 100),
    fontScale = fontScale.coerceIn(WidgetConfig.FONT_SCALE_RANGE),
    maxPerCell = maxPerCell.coerceIn(WidgetConfig.MAX_PER_CELL_RANGE),
    lineSpacing = runCatching { LineSpacing.valueOf(lineSpacing) }.getOrDefault(LineSpacing.NORMAL),
    hideDone = hideDone,
    selectedTab = runCatching { WidgetTab.valueOf(selectedTab) }.getOrDefault(WidgetTab.CALENDAR),
    hideAmounts = hideAmounts,
    ddayPageId = ddayPageId,
    showWeather = showWeather,
    viewId = viewId,
    viewPage = viewPage.coerceAtLeast(0),
    numberStep = numberStep.takeIf { it.isFinite() && it > 0 } ?: 1.0,
    presetsJson = presetsJson,
    layoutJson = layoutJson,
    lastTab = lastTab.coerceAtLeast(0),
    formJson = formJson,
    actionsJson = actionsJson,
    timelineOffset = timelineOffset,
)

internal fun WidgetConfig.toEntity() = WidgetConfigEntity(
    appWidgetId = appWidgetId,
    monthOffset = monthOffset,
    dataSourceIds = dataSourceIds.sorted().joinToString(","),
    theme = theme.name,
    opacity = opacity.coerceIn(0, 100),
    fontScale = fontScale.coerceIn(WidgetConfig.FONT_SCALE_RANGE),
    maxPerCell = maxPerCell.coerceIn(WidgetConfig.MAX_PER_CELL_RANGE),
    lineSpacing = lineSpacing.name,
    hideDone = hideDone,
    selectedTab = selectedTab.name,
    hideAmounts = hideAmounts,
    ddayPageId = ddayPageId,
    showWeather = showWeather,
    viewId = viewId,
    viewPage = viewPage,
    numberStep = numberStep.takeIf { it.isFinite() && it > 0 } ?: 1.0, // NaN은 SQLite가 NULL로 저장해 열 제약에 걸린다
    presetsJson = presetsJson,
    layoutJson = layoutJson,
    lastTab = lastTab.coerceAtLeast(0),
    formJson = formJson,
    actionsJson = actionsJson,
    timelineOffset = timelineOffset,
)

@Singleton
class WidgetConfigRepository @Inject constructor(private val dao: WidgetConfigDao) {
    /** 저장된 설정이 없으면 기본값(설정 화면을 건너뛰고 놓은 위젯). */
    suspend fun get(appWidgetId: Int): WidgetConfig = dao.get(appWidgetId)?.toModel() ?: WidgetConfig(appWidgetId)

    /** 저장된 행이 있을 때만(기본값이 아니라). 놓은 뒤 한 번도 저장하지 않은 새 위젯인지 가를 때 쓴다(Phase 11h 레시피). */
    suspend fun saved(appWidgetId: Int): WidgetConfig? = dao.get(appWidgetId)?.toModel()

    /** 위젯 화면이 구독한다. 달 이동·설정 저장이 바로 반영된다. */
    fun observe(appWidgetId: Int): Flow<WidgetConfig> =
        dao.observe(appWidgetId).map { it?.toModel() ?: WidgetConfig(appWidgetId) }.distinctUntilChanged()

    suspend fun save(config: WidgetConfig) = dao.upsert(config.toEntity())

    /** WI-04: 이전(-1)·다음(+1) 달. */
    suspend fun shiftMonth(appWidgetId: Int, delta: Int) {
        val current = get(appWidgetId)
        save(current.copy(monthOffset = current.monthOffset + delta))
    }

    /** WI-04: 오늘 버튼. */
    suspend fun resetMonth(appWidgetId: Int) = save(get(appWidgetId).copy(monthOffset = 0))

    /** 타임라인(VR-TML)의 이전(-1)·다음(+1) 창. 캘린더의 달 이동과 따로 저장해 한 위젯에 둘이 있어도 서로 움직이지 않는다. */
    suspend fun shiftTimeline(appWidgetId: Int, delta: Int) {
        val current = get(appWidgetId)
        save(current.copy(timelineOffset = current.timelineOffset + delta))
    }

    /** 타임라인의 오늘 버튼. */
    suspend fun resetTimeline(appWidgetId: Int) = save(get(appWidgetId).copy(timelineOffset = 0))

    /** 날짜가 바뀌면 넘겨 둔 달·타임라인 창을 모든 위젯에서 오늘로 되돌린다(다음 날에는 오늘이 보이게). */
    suspend fun resetAllOffsets(): Int = dao.resetOffsets()

    /** WG-11: 통합 위젯의 탭. 위젯마다 따로 저장한다. */
    suspend fun selectTab(appWidgetId: Int, tab: WidgetTab) = save(get(appWidgetId).copy(selectedTab = tab))

    /** Phase 11d WL-02: 노션 위젯의 마지막으로 본 탭 번호. 위젯마다 따로 저장한다. */
    suspend fun selectLayoutTab(appWidgetId: Int, index: Int) = save(get(appWidgetId).copy(lastTab = index.coerceAtLeast(0)))

    suspend fun delete(appWidgetIds: List<Int>) = dao.delete(appWidgetIds)
}
