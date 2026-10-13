package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** WI-04: 위젯마다 보고 있는 달을 따로 저장한다. WG-04: 위젯별 설정. */
@RunWith(RobolectricTestRunner::class)
class WidgetConfigRepositoryTest {
    private lateinit var db: NotionCalDatabase
    private lateinit var repo: WidgetConfigRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WidgetConfigRepository(db.widgetConfigDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun monthNavigationIsPerWidget() = runTest {
        repo.save(WidgetConfig(1, theme = WidgetTheme.DARK, fontScale = 120))
        repo.shiftMonth(1, +1)
        repo.shiftMonth(1, +1)
        repo.shiftMonth(2, -1)

        assertEquals(2, repo.get(1).monthOffset)
        assertEquals(-1, repo.get(2).monthOffset)
        // 달을 옮겨도 다른 설정은 그대로다.
        assertEquals(WidgetTheme.DARK, repo.get(1).theme)
        assertEquals(120, repo.get(1).fontScale)

        repo.resetMonth(1)
        assertEquals(0, repo.get(1).monthOffset)
        assertEquals(-1, repo.get(2).monthOffset)
    }

    @Test
    fun valuesAreClampedAndSetsRoundTrip() = runTest {
        repo.save(WidgetConfig(3, dataSourceIds = setOf("b", "a"), fontScale = 999, maxPerCell = 42, opacity = -5))
        val saved = repo.get(3)
        assertEquals(setOf("a", "b"), saved.dataSourceIds)
        assertEquals(150, saved.fontScale)
        assertEquals(6, saved.maxPerCell)
        assertEquals(0, saved.opacity)
    }

    @Test
    fun deleteRemovesOnlyGivenWidgets() = runTest {
        repo.save(WidgetConfig(1, hideDone = true))
        repo.save(WidgetConfig(2, hideDone = true))
        repo.delete(listOf(1))
        assertEquals(WidgetConfig(1), repo.get(1))
        assertEquals(true, repo.get(2).hideDone)
    }
}
