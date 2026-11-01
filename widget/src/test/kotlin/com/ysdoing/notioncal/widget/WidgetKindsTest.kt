package com.ysdoing.notioncal.widget

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 검증 메모 5: 위젯 액션(월 이동·습관 체크·탭)은 누른 위젯의 종류로 다시 그린다. 통합 위젯의 📅 탭에서 ◀/▶를 눌러도
 * 탭 없는 월 위젯으로 그려지지 않는다.
 */
class WidgetKindsTest {
    @Test
    fun providerClassPicksWidget() {
        assertTrue(WidgetKinds.forProvider(TabbedWidgetReceiver::class.java.name) is TabbedWidget)
        assertTrue(WidgetKinds.forProvider(HabitWidgetReceiver::class.java.name) is HabitWidget)
        assertTrue(WidgetKinds.forProvider(AgendaWidgetReceiver::class.java.name) is AgendaWidget)
        assertTrue(WidgetKinds.forProvider(SplitWidgetReceiver::class.java.name) is SplitWidget)
        assertTrue(WidgetKinds.forProvider(NotionCalWidgetReceiver::class.java.name) is NotionCalWidget)
        assertTrue(WidgetKinds.forProvider(null) is NotionCalWidget)
    }
}
