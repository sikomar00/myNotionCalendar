package com.ysdoing.notioncal.core.data

import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** EX-07: 추가 속성 변경의 병합·기준값·적용, 표시할 속성 고르기. */
class ExtraPropsModelTest {
    private val pri = PropValue(optionIds = listOf("o1"))
    private val url = PropValue(text = "https://example.com")

    private val event = EventEntity(
        pageId = "p1", dataSourceId = "ds", title = "회의", startDate = LocalDate.of(2026, 10, 14), endDate = null,
        startTime = null, endTime = null, isAllDay = true, done = false, colorKey = null, sortKey = null, url = "",
        lastEditedTime = 0, extraValues = encodeExtraValues(mapOf("pRi" to pri)),
    )

    @Test
    fun mergeKeepsLatestPerProperty() {
        val first = EventChange(props = mapOf("pRi" to pri, "uRl" to url))
        val later = EventChange(props = mapOf("pRi" to PropValue(optionIds = emptyList())), title = "새 제목")
        val merged = first.mergedWith(later)
        assertEquals(mapOf("pRi" to PropValue(optionIds = emptyList()), "uRl" to url), merged.props)
        assertEquals("새 제목", merged.title)
        assertEquals(null, EventChange(title = "a").mergedWith(EventChange(done = true)).props) // 속성 변경이 없으면 null 유지
    }

    @Test
    fun emptyPropsAreEmptyChange() {
        assertTrue(EventChange(props = emptyMap()).isEmpty)
        assertFalse(EventChange(props = mapOf("pRi" to pri)).isEmpty)
    }

    @Test
    fun baseValuesCoverOnlyChangedProperties() {
        val base = event.valuesFor(EventChange(props = mapOf("pRi" to PropValue(), "uRl" to url)))
        // 값이 없던 속성은 빈 값이 기준이다.
        assertEquals(mapOf("pRi" to pri, "uRl" to PropValue()), base.props)
        assertEquals(null, event.valuesFor(EventChange(title = "x")).props)
    }

    @Test
    fun applyingMergesIntoStoredValues() {
        val next = event.applying(EventChange(props = mapOf("uRl" to url)), emptyList())
        assertEquals(mapOf("pRi" to pri, "uRl" to url), parseExtraValues(next.extraValues))
        // TRASH 되돌리기용 사본에도 남는다.
        assertEquals(next.extraValues, next.snapshot().toEntity().extraValues)
    }

    @Test
    fun shownPropsFollowChoiceAndSkipRemovedOrMapped() {
        val catalog = listOf(
            ExtraProp("pRi", "Pri.", "select", listOf(ColorOption("o1", "1st", "red"))),
            ExtraProp("uRl", "URL", "url"),
            ExtraProp("cTgR", "Ctgr", "select"),
        )
        val source = DataSourceEntity(
            dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
            datePropId = "dAtE", donePropId = null, doneType = null, doneOption = null, undoneOption = null,
            colorPropId = "cTgR", sortPropId = null,
            propCatalog = encodePropCatalog(catalog),
            extraPropIds = encodeIdList(listOf("uRl", "gone", "pRi")),
        )
        assertEquals(listOf("uRl", "pRi"), source.shownExtraProps().map { it.id }) // 고른 순서, 사라진 속성은 뺀다
        assertEquals(listOf("pRi", "uRl"), source.selectableExtraProps().map { it.id }) // 색 기준 속성은 이미 편집기가 있다
    }
}
