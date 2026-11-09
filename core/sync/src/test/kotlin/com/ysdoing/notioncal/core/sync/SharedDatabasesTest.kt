package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.notion.SharedDataSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** NS-02 DB 추가 목록: 이미 등록한 data source는 '등록됨', 등록 안 한 것을 먼저·이름순으로 보인다. */
class SharedDatabasesTest {
    @Test
    fun registeredGoLastAndEachGroupIsSortedByName() {
        val shared = listOf(
            SharedDataSource("db-tx", "ds-tx", "거래내역"),
            SharedDataSource("db-cal", "ds-cal", "Calendar"),
            SharedDataSource("db-books", "ds-books", "Books"),
            SharedDataSource("db-new", "ds-new", "reading list"),
            SharedDataSource("db-blank", "ds-blank", ""),
        )
        val result = sharedDatabases(shared, registeredIds = setOf("ds-tx", "ds-cal"))
        // 대소문자를 무시한 이름순, 제목 없는 DB는 등록 안 한 것 중 끝.
        assertEquals(listOf("Books", "reading list", "", "Calendar", "거래내역"), result.map { it.name })
        assertEquals(listOf(false, false, false, true, true), result.map { it.registered })
        assertEquals("db-books" to "ds-books", result.first().let { it.databaseId to it.dataSourceId }) // 조회는 부모 DB id로
    }
}
