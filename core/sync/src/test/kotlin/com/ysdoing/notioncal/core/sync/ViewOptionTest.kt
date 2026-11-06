package com.ysdoing.notioncal.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** VW-05 보기 종류: table·list는 그대로, chart·board·gallery·timeline·calendar는 위젯이 각자의 그림으로 그리고, 나머지는 고를 수 없다. */
class ViewOptionTest {
    private fun option(type: String?) = ViewOption("v", "이름", type, registered = false)

    @Test
    fun everyViewKindTheWidgetDrawsCanBeChosen() {
        // 표·목록·차트·보드·갤러리·캘린더·타임라인 모두 각자의 그림이 있다(못 그리는 설정이면 위젯이 알아서 목록으로 대체한다).
        listOf("table", "list", "chart", "board", "gallery", "calendar", "timeline").forEach { assertEquals(it, true, option(it).supported) }
    }

    @Test
    fun dashboardsFormsMapsAndUnreadableViewsCannotBeChosen() {
        listOf("dashboard", "form", "map", "feed", "brand_new", null).forEach { assertEquals(false, option(it).supported) }
    }
}
