package com.ysdoing.notioncal.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** VW-05 보기 종류: table·list는 그대로, board·gallery·timeline·calendar는 목록으로 표시, 나머지는 고를 수 없다. */
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
    fun otherSupportedKindsAreShownAsList() {
        listOf("board", "gallery", "timeline", "calendar").forEach {
            assertEquals(true, option(it).supported)
            assertEquals(true, option(it).shownAsList)
        }
    }

    @Test
    fun dashboardsFormsChartsMapsAndUnreadableViewsCannotBeChosen() {
        listOf("dashboard", "form", "chart", "map", "feed", "brand_new", null).forEach {
            assertEquals(false, option(it).supported)
            assertEquals(false, option(it).shownAsList)
        }
    }
}
