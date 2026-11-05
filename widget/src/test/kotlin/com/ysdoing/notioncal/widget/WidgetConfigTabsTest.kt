package com.ysdoing.notioncal.widget

import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.FormField
import com.ysdoing.notioncal.core.data.view.InputForm
import com.ysdoing.notioncal.core.data.view.LayoutItem
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11g ST-07·AD-05 위젯 편집 화면의 탭 구성(내용·모양·동작)과, 프리셋·입력 양식이 어느 보기에 걸리는지, 입력 양식 편집 줄의 순서.
 * 화면 대신 순수 함수를 본다.
 */
class WidgetConfigTabsTest {
    private fun layoutConfig(mode: LayoutMode, vararg viewIds: String) =
        WidgetConfig(1, layoutJson = WidgetLayout.encode(WidgetLayout(mode, viewIds.map { LayoutItem(it) })))

    @Test
    fun eachWidgetKindGetsOnlyTheTabsThatHaveSettings() {
        val calendar = listOf(ConfigTab.CONTENT, ConfigTab.LOOK)
        assertEquals(calendar, configTabs(ConfigKind.MONTH))
        assertEquals(calendar, configTabs(ConfigKind.AGENDA))
        assertEquals(calendar, configTabs(ConfigKind.SPLIT))
        assertEquals(calendar, configTabs(ConfigKind.DDAY)) // 고정할 디데이
        assertEquals(calendar, configTabs(ConfigKind.BOOK)) // 보여 줄 보기
        assertEquals(listOf(ConfigTab.CONTENT, ConfigTab.LOOK, ConfigTab.ACTION), configTabs(ConfigKind.VIEW))
        assertEquals(listOf(ConfigTab.CONTENT, ConfigTab.LOOK, ConfigTab.ACTION), configTabs(ConfigKind.LAYOUT))
    }

    @Test
    fun widgetsWithOnlyLookSettingsHaveASingleTab() {
        listOf(ConfigKind.LEDGER, ConfigKind.DDAY_LIST, ConfigKind.HABIT).forEach { assertEquals(it.name, listOf(ConfigTab.LOOK), configTabs(it)) }
    }

    @Test
    fun aViewWidgetMakesPresetsForItsChosenView() {
        val config = WidgetConfig(1, viewId = "v1")
        assertTrue(presetsApply(ConfigKind.VIEW, config))
        assertEquals("v1", presetViewId(ConfigKind.VIEW, config))
        assertTrue(presetsApply(ConfigKind.VIEW, WidgetConfig(1)))
        assertNull(presetViewId(ConfigKind.VIEW, WidgetConfig(1))) // 보기를 아직 고르지 않았다
    }

    @Test
    fun aNotionWidgetMakesPresetsOnlyForASingleLayoutsView() {
        val single = layoutConfig(LayoutMode.SINGLE, "v2")
        assertTrue(presetsApply(ConfigKind.LAYOUT, single))
        assertEquals("v2", presetViewId(ConfigKind.LAYOUT, single))
        assertTrue(presetsApply(ConfigKind.LAYOUT, WidgetConfig(1))) // 배치를 안 정하면 단일
        assertNull(presetViewId(ConfigKind.LAYOUT, WidgetConfig(1)))
        listOf(LayoutMode.TABS, LayoutMode.SECTIONS).forEach { mode ->
            assertFalse(mode.name, presetsApply(ConfigKind.LAYOUT, layoutConfig(mode, "v1", "v2"))) // 프리셋 줄이 그려지지 않는 배치
            assertNull(presetViewId(ConfigKind.LAYOUT, layoutConfig(mode, "v1", "v2")))
        }
    }

    @Test
    fun otherWidgetsHaveNoPresets() {
        listOf(ConfigKind.MONTH, ConfigKind.LEDGER, ConfigKind.HABIT, ConfigKind.BOOK).forEach {
            assertFalse(it.name, presetsApply(it, WidgetConfig(1, viewId = "v1")))
            assertNull(presetViewId(it, WidgetConfig(1, viewId = "v1")))
        }
    }

    @Test
    fun theInputFormIsEditedForEveryViewTheWidgetCanAddTo() {
        assertEquals(listOf("v1"), formViewIds(ConfigKind.VIEW, WidgetConfig(1, viewId = "v1")))
        assertEquals(emptyList<String>(), formViewIds(ConfigKind.VIEW, WidgetConfig(1)))
        assertEquals(listOf("a", "b", "c"), formViewIds(ConfigKind.LAYOUT, layoutConfig(LayoutMode.TABS, "a", "b", "c")))
        assertEquals(listOf("a"), formViewIds(ConfigKind.LAYOUT, layoutConfig(LayoutMode.SINGLE, "a")))
        assertEquals(emptyList<String>(), formViewIds(ConfigKind.HABIT, WidgetConfig(1, viewId = "v1")))
    }

    private val amount = ViewColumn("n", "금액", "number")
    private val kind = ViewColumn("k", "분류", "select")
    private val memo = ViewColumn("r", "메모", "rich_text")
    private val columns = listOf(ViewColumn("t", "이름", "title"), amount, kind, memo, ViewColumn("d", "날짜", "date"), ViewColumn("f", "합계", "formula"))

    @Test
    fun checkedPropertiesComeFirstInFormOrderThenTheRestInBoardOrder() {
        val form = InputForm(listOf(FormField("r"), FormField("n")))
        assertEquals(
            listOf(FormRow(memo, true), FormRow(amount, true), FormRow(kind, false)),
            formRows(form, columns),
        )
    }

    @Test
    fun anEmptyFormListsEveryAddablePropertyUnchecked() {
        assertEquals(listOf(amount, kind, memo).map { FormRow(it, false) }, formRows(InputForm(), columns))
    }

    @Test
    fun propertiesGoneFromTheBoardAreNotListed() {
        val form = InputForm(listOf(FormField("gone"), FormField("k")))
        assertEquals(listOf(FormRow(kind, true), FormRow(amount, false), FormRow(memo, false)), formRows(form, columns))
    }
}
