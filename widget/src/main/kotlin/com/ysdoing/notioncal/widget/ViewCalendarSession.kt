package com.ysdoing.notioncal.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.WidgetConfig
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Phase 12a VR-CAL 캘린더 보기를 담은 위젯이 읽는 앱 설정(주 시작 요일·주차)과 공휴일. 둘 다 Room·설정 저장소에서만 읽는다(WG-02).
 * 캘린더가 없는 위젯은 읽지 않는다.
 */
internal object CalendarSession {
    suspend fun load(deps: WidgetDependencies, config: WidgetConfig): CalendarInfo {
        val settings = deps.settings().settings.first()
        val (from, to) = calendarRange(LocalDate.now(APP_ZONE), config.monthOffset, settings.weekStart)
        return CalendarInfo(settings.weekStart, settings.showWeekNumbers, deps.holidays().observe(from, to).first())
    }

    /** 이후 변경(설정, 달 이동)은 Flow가 반영한다. [enabled]가 false면 읽지 않고 처음 값을 돌려준다. */
    @Composable
    fun info(deps: WidgetDependencies, initial: CalendarInfo, config: WidgetConfig, enabled: Boolean): CalendarInfo {
        if (!enabled) return initial
        val settings by remember { deps.settings().settings }.collectAsState(null)
        val weekStart = settings?.weekStart ?: initial.weekStart
        // 그릴 때마다 오늘을 다시 계산한다(PRD §7-4). 자정에 살아 있는 세션도 다시 그리게 관찰하는 오늘(WidgetToday).
        val range = calendarRange(widgetToday(), config.monthOffset, weekStart)
        val holidays by remember(range) { deps.holidays().observe(range.first, range.second) }.collectAsState(initial.holidays)
        return CalendarInfo(weekStart, settings?.showWeekNumbers ?: initial.showWeekNumbers, holidays)
    }
}
