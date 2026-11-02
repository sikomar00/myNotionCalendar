package com.ysdoing.notioncal.core.data.view

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** VW-04·06 등록한 보기의 스냅샷을 위젯이 읽는 모양으로 돌려주고, 등록을 지우면 행도 함께 지운다. */
@RunWith(RobolectricTestRunner::class)
class ViewRepositoryTest {
    private lateinit var db: NotionCalDatabase
    private var widgetUpdates = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun repo() = ViewRepository(db.viewDao(), { widgetUpdates++ })

    /** 등록(upsert)한 뒤 스냅샷을 채운다. `replace`는 등록이 지워졌으면 되살리지 않기 때문이다. */
    private suspend fun seed(view: ViewSnapshotEntity, rows: List<ViewRowEntity>) {
        db.viewDao().upsert(view)
        db.viewDao().replace(view, rows)
    }

    @Test
    fun observeSplitsColumnsAndCellsInOrder() = runTest {
        val sep = VIEW_CELL_SEPARATOR
        seed(
            ViewSnapshotEntity("v1", "ds", "할 일", "table", columns = "이름${sep}분류", syncedAt = 5, lastError = "Network"),
            listOf(ViewRowEntity("v1", 1, "p2", "u2", "둘째${sep}"), ViewRowEntity("v1", 0, "p1", "u1", "첫째${sep}집")),
        )
        val board = repo().observe("v1").first()!!
        assertEquals(listOf("이름", "분류"), board.columns)
        assertEquals(listOf("p1", "p2"), board.rows.map { it.pageId })
        assertEquals(listOf("첫째", "집"), board.rows[0].cells)
        assertEquals(listOf("둘째", ""), board.rows[1].cells) // 빈 값도 열 자리를 지킨다
        assertEquals("Network", board.lastError)
    }

    @Test
    fun unknownViewIsNull() = runTest { assertNull(repo().observe("nope").first()) }

    @Test
    fun onlyNotFoundErrorMarksTheViewAsGone() = runTest {
        // VW-06: 노션이 404로 답한 보기만 '찾을 수 없음'이다. 네트워크 실패 같은 일시 오류는 아니다.
        db.viewDao().upsert(ViewSnapshotEntity("gone", "ds", "지워짐", "table", lastError = VIEW_ERROR_NOT_FOUND))
        db.viewDao().upsert(ViewSnapshotEntity("flaky", "ds", "불안정", "table", lastError = "Network"))
        db.viewDao().upsert(ViewSnapshotEntity("fine", "ds", "정상", "table"))
        assertTrue(repo().observe("gone").first()!!.notFound)
        assertFalse(repo().observe("flaky").first()!!.notFound)
        assertFalse(repo().observe("fine").first()!!.notFound)
    }

    @Test
    fun removeDropsRowsAndRequestsWidgetUpdate() = runTest {
        seed(ViewSnapshotEntity("v1", "ds", "할 일", "table"), listOf(ViewRowEntity("v1", 0, "p1", "u1", "a")))
        repo().remove("v1")
        assertEquals(emptyList<String>(), db.viewDao().all().map { it.viewId })
        assertEquals(0, db.viewDao().rows("v1").size)
        assertEquals(1, widgetUpdates)
    }
}
