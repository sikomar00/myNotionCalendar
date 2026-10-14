package com.ysdoing.notioncal.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NS-09 알림 저장 규칙: 확인 표시 유지, 이름 바뀜은 확인할 때까지 남김, 제목·날짜만 동기화를 막음. */
class SchemaIssueTest {
    private val dateMissing = SchemaIssue(SchemaIssue.DATE, SchemaIssue.MISSING, oldName = "Date")
    private val doneMissing = SchemaIssue(SchemaIssue.DONE, SchemaIssue.MISSING)
    private val renamed = SchemaIssue(SchemaIssue.TITLE, SchemaIssue.RENAMED, "Name", "제목")

    @Test
    fun onlyRequiredRolesBlockSync() {
        assertTrue(dateMissing.blocksSync)
        assertTrue(SchemaIssue(SchemaIssue.TITLE, SchemaIssue.TYPE_CHANGED).blocksSync)
        assertFalse(doneMissing.blocksSync)
        assertFalse(renamed.blocksSync)
    }

    @Test
    fun mergeKeepsAcknowledgementAndPendingRenames() {
        val stored = SchemaIssue.acknowledge(listOf(doneMissing)) + renamed
        // 다음 동기화: 완료는 여전히 없음, 이름 바뀜은 이미 새 이름을 기록해 다시 잡히지 않음.
        val merged = SchemaIssue.merge(stored, listOf(doneMissing))
        assertEquals(listOf(doneMissing.copy(acknowledged = true), renamed), merged)
        // 확인하면 이름 바뀜은 지우고 나머지는 확인 표시.
        assertEquals(listOf(doneMissing.copy(acknowledged = true)), SchemaIssue.acknowledge(merged))
        // 문제가 풀리면 알림도 사라진다.
        assertEquals(emptyList<SchemaIssue>(), SchemaIssue.merge(SchemaIssue.acknowledge(merged), emptyList()))
    }

    @Test
    fun jsonRoundTrip() {
        val list = listOf(dateMissing, renamed)
        assertEquals(list, SchemaIssue.parse(SchemaIssue.encode(list)))
        assertEquals(null, SchemaIssue.encode(emptyList()))
        assertEquals(emptyList<SchemaIssue>(), SchemaIssue.parse("깨진 JSON"))
        assertEquals(mapOf("DATE" to "Date"), SchemaIssue.parseNames(SchemaIssue.encodeNames(mapOf("DATE" to "Date"))))
    }
}
