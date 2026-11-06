package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.ViewQueryIds
import com.ysdoing.notioncal.core.notion.ViewReader
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.ViewDto
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phase 10-7 진단 '노션과 비교' 보기 확장: 보기 쿼리(노션이 보기에 넣은 페이지 id와 순서)와 위젯이 가진 행을 비교한다. */
@RunWith(RobolectricTestRunner::class)
class ViewComparerTest {
    private lateinit var db: NotionCalDatabase
    private var queried = emptyMap<String, ViewQueryIds>()
    private val failing = mutableSetOf<String>()
    private val limits = mutableListOf<Int>()

    private val viewReader = object : ViewReader {
        override suspend fun viewIds(dataSourceId: String) = emptyList<String>()
        override suspend fun view(viewId: String) = ViewDto(id = viewId)
        override suspend fun rows(dataSourceId: String, view: ViewDto, limit: Int) = emptyList<PageDto>()
        override suspend fun viewQueryIds(viewId: String, limit: Int): ViewQueryIds {
            limits += limit
            if (viewId in failing) throw NotionException(NotionError.Network)
            return queried.getValue(viewId)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun view(id: String, type: String, vararg pages: String) {
        val snapshot = ViewSnapshotEntity(id, "ds", "보기 $id", type, columns = "이름", syncedAt = 1)
        db.viewDao().upsert(snapshot)
        db.viewDao().replace(snapshot, pages.mapIndexed { i, page -> ViewRowEntity(id, i, page, "u", "행") })
    }

    private suspend fun compare() = ViewComparer(viewReader, db, UnconfinedTestDispatcher()).compare()

    @Test
    fun aViewWhoseRowsMatchTheQueryInIdsAndOrderMatches() = runTest {
        view("v1", "table", "a", "b", "c")
        queried = mapOf("v1" to ViewQueryIds(listOf("a", "b", "c"), complete = true))
        val result = compare().single()
        assertTrue(result.matches)
        assertEquals(listOf(3, 3), listOf(result.queryCount, result.localCount))
    }

    @Test
    fun missingExtraAndReorderedRowsAreTold() = runTest {
        view("v1", "table", "a", "x", "c")
        view("v2", "table", "b", "a")
        queried = mapOf("v1" to ViewQueryIds(listOf("a", "b", "c"), true), "v2" to ViewQueryIds(listOf("a", "b"), true))
        val (first, second) = compare().sortedBy { it.viewId }
        assertEquals(listOf(1, 1, true), listOf(first.missingLocally, first.extraLocally, first.sameOrder)) // b가 없고 x가 더 있다
        assertEquals(listOf(0, 0, false), listOf(second.missingLocally, second.extraLocally, second.sameOrder)) // 순서만 다르다
        assertFalse(first.matches || second.matches)
    }

    @Test
    fun onlyTheFirstRowsTheWidgetKeepsAreCompared() = runTest {
        // 위젯은 앞 50행만 받는다. 보기에 더 있어도 그 뒤는 비교하지 않는다(요청도 그만큼만 한다).
        val rows = (0 until com.ysdoing.notioncal.core.notion.VIEW_ROW_LIMIT).map { "p$it" }
        view("v1", "table", *rows.toTypedArray())
        queried = mapOf("v1" to ViewQueryIds(rows + (com.ysdoing.notioncal.core.notion.VIEW_ROW_LIMIT until com.ysdoing.notioncal.core.notion.VIEW_ROW_LIMIT + 10).map { "p$it" }, complete = true))
        assertTrue(compare().single().matches)
        assertEquals(listOf(com.ysdoing.notioncal.core.notion.VIEW_ROW_LIMIT), limits)
    }

    @Test
    fun aNotionErrorIsReportedPerViewAndDoesNotStopTheOthers() = runTest {
        view("v1", "table", "a")
        view("v2", "table", "a")
        failing += "v1"
        queried = mapOf("v2" to ViewQueryIds(listOf("a"), true))
        val results = compare().associateBy { it.viewId }
        assertEquals("Network", results.getValue("v1").error)
        assertTrue(results.getValue("v2").matches)
    }

    @Test
    fun theKnownBoardRelationQuickFilterExceptionIsMarkedAsACandidate() = runTest {
        // 9-A 실측: board 보기 + relation 빠른 필터 1건은 보기 쿼리와 데이터 소스 쿼리가 다르다(원인 미확인). 불일치가 나도 board는 후보로 표시한다.
        view("b1", "board", "a")
        view("t1", "table", "a")
        queried = mapOf("b1" to ViewQueryIds(emptyList(), true), "t1" to ViewQueryIds(emptyList(), true))
        val results = compare().associateBy { it.viewId }
        assertTrue(results.getValue("b1").knownExceptionCandidate)
        assertFalse(results.getValue("t1").knownExceptionCandidate)
    }

    @Test
    fun theSummaryNamesTheKindOfEachMismatchWithoutTitles() = runTest {
        view("v1", "table", "a", "x")
        queried = mapOf("v1" to ViewQueryIds(listOf("a", "b"), true))
        val line = compare().single().summary()
        assertTrue(line, line.contains("불일치") && line.contains("노션에만 1") && line.contains("앱에만 1"))
    }
}
