package com.ysdoing.notioncal.core.data.calendar

import com.ysdoing.notioncal.core.data.CalendarEvent

/**
 * MV-09 앱 화면 필터. 동기화·위젯과 무관하게 앱의 월·주·3일·목록 보기에서만 숨긴다(위젯은 WidgetConfig를 따른다).
 * - [hiddenSourceIds]: 앱에서 숨길 DB.
 * - [hiddenOptionKeys]: 숨길 색 기준 select 값. [optionKey] 형식("dsId/optionId", 값 없음은 "dsId/").
 * - [hideDone]: 완료 숨김(설정 화면의 MV-05 값과 같다).
 */
data class EventFilter(
    val hiddenSourceIds: Set<String> = emptySet(),
    val hiddenOptionKeys: Set<String> = emptySet(),
    val hideDone: Boolean = false,
) {
    val isActive: Boolean get() = hiddenSourceIds.isNotEmpty() || hiddenOptionKeys.isNotEmpty() || hideDone

    fun apply(events: List<CalendarEvent>): List<CalendarEvent> =
        if (!isActive) {
            events
        } else {
            events.filter { event ->
                event.dataSourceId !in hiddenSourceIds &&
                    optionKey(event.dataSourceId, event.colorOptionId) !in hiddenOptionKeys &&
                    !(hideDone && event.done)
            }
        }

    companion object {
        fun optionKey(dataSourceId: String, optionId: String?): String = "$dataSourceId/${optionId.orEmpty()}"
    }
}
