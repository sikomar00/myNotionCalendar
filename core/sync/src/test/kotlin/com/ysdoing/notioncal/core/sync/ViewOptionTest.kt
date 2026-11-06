package com.ysdoing.notioncal.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** VW-05 보기 종류: table·list는 그대로, chart는 차트로, board·gallery·timeline·calendar는 목록으로 표시(보드·캘린더는 위젯이 따로 그린다), 나머지는 고를 수 없다. */
class ViewOptionTest {
    private fun option(type: String?) = ViewOption("v", "이름", type, registered = false)

    @Test
    fun tableAndListAreShownAsTheyAre() {
        listOf("table", "list").forEach {
            assertEquals(true, option(it).supported)
            assertEquals(false, option(it).shownAsList)
        }
    }

    @Test
    fun chartsAreChosenAndDrawnAsCharts() {
        // Phase 12b: 차트 보기는 고를 수 있고 목록이 아니라 차트로 그린다(못 그리는 설정은 위젯이 알아서 목록으로 대체한다).
        assertEquals(true, option("chart").supported)
        assertEquals(false, option("chart").shownAsList)
    }

    @Test
    fun boardCalendarAndGalleryHaveTheirOwnDrawingAndOnlyTheTimelineIsShownAsAList() {
        listOf("board", "gallery", "calendar").forEach {
            assertEquals(true, option(it).supported)
            assertEquals(false, option(it).shownAsList)
        }
        assertEquals(true, option("timeline").supported)
        assertEquals(true, option("timeline").shownAsList)
    }

    @Test
    fun dashboardsFormsMapsAndUnreadableViewsCannotBeChosen() {
        listOf("dashboard", "form", "map", "feed", "brand_new", null).forEach {
            assertEquals(false, option(it).supported)
            assertEquals(false, option(it).shownAsList)
        }
    }
}
