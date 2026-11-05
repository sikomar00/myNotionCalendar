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

    /** Phase 11c IX-03·AD-05: 숫자 ± 단계값과 프리셋 목록은 위젯마다 저장되고, 잘못된 단계값(0·음수·NaN)은 1로 읽는다. */
    @Test
    fun numberStepAndPresetsRoundTripAndABadStepFallsBackToOne() = runTest {
        repo.save(WidgetConfig(4, numberStep = 5.0, presetsJson = "[{\"name\":\"점심\"}]"))
        assertEquals(5.0, repo.get(4).numberStep, 0.0)
        assertEquals("[{\"name\":\"점심\"}]", repo.get(4).presetsJson)
        listOf(0.0, -2.0, Double.NaN).forEach { bad ->
            repo.save(WidgetConfig(5, numberStep = bad))
            assertEquals(1.0, repo.get(5).numberStep, 0.0)
        }
    }

    /** Phase 11d WL-02: 배치(보기 목록·탭 이름·비율)와 마지막으로 본 탭 번호는 위젯마다 저장된다. 탭 번호는 0 아래로 읽지 않는다. */
    @Test
    fun layoutAndLastTabRoundTripPerWidgetAndANegativeTabReadsAsZero() = runTest {
        repo.save(WidgetConfig(6, layoutJson = "{\"mode\":\"TABS\"}", lastTab = 3))
        repo.save(WidgetConfig(7, layoutJson = "{\"mode\":\"SECTIONS\"}", lastTab = -2))
        assertEquals(listOf("{\"mode\":\"TABS\"}", 3), listOf(repo.get(6).layoutJson, repo.get(6).lastTab))
        assertEquals(listOf("{\"mode\":\"SECTIONS\"}", 0), listOf(repo.get(7).layoutJson, repo.get(7).lastTab))
    }

    /** Phase 11g AD-02: 입력 양식은 위젯마다 저장되고 다른 설정을 저장해도 남는다. */
    @Test
    fun formJsonRoundTripsPerWidgetAndSurvivesOtherSaves() = runTest {
        repo.save(WidgetConfig(8, formJson = "{\"v1\":{}}"))
        repo.save(WidgetConfig(9))
        repo.save(repo.get(8).copy(theme = WidgetTheme.DARK))
        assertEquals("{\"v1\":{}}", repo.get(8).formJson)
        assertEquals("", repo.get(9).formJson)
    }

    /** Phase 12c IX-07: 액션 버튼 목록은 위젯마다 저장되고 다른 설정을 저장해도 남는다. */
    @Test
    fun actionsJsonRoundTripsPerWidgetAndSurvivesOtherSaves() = runTest {
        repo.save(WidgetConfig(10, actionsJson = "[{\"id\":\"a1\"}]"))
        repo.save(WidgetConfig(11))
        repo.save(repo.get(10).copy(theme = WidgetTheme.DARK))
        assertEquals("[{\"id\":\"a1\"}]", repo.get(10).actionsJson)
        assertEquals("", repo.get(11).actionsJson)
    }

    /** WL-02: 탭을 누르면 그 위젯의 탭 번호만 바뀌고 나머지 설정·다른 위젯은 그대로다. */
    @Test
    fun selectingALayoutTabKeepsTheRestAndOtherWidgets() = runTest {
        repo.save(WidgetConfig(6, viewId = "v", lastTab = 1))
        repo.save(WidgetConfig(7, lastTab = 2))
        repo.selectLayoutTab(6, 4)
        repo.selectLayoutTab(7, -3)
        assertEquals(listOf("v", 4), listOf(repo.get(6).viewId, repo.get(6).lastTab))
        assertEquals(0, repo.get(7).lastTab)
    }

    /** WG-11: 고른 탭은 위젯마다 따로이고, 달 이동·설정 저장(행 전체 upsert) 뒤에도 남는다. */
    @Test
    fun selectedTabIsPerWidgetAndSurvivesOtherSaves() = runTest {
        repo.selectTab(1, WidgetTab.HABIT)
        repo.selectTab(2, WidgetTab.CALENDAR)
        repo.shiftMonth(1, +1)
        repo.resetMonth(1)
        repo.save(repo.get(1).copy(theme = WidgetTheme.DARK))

        assertEquals(WidgetTab.HABIT, repo.get(1).selectedTab)
        assertEquals(WidgetTab.CALENDAR, repo.get(2).selectedTab)
        assertEquals(WidgetTab.CALENDAR, repo.get(3).selectedTab) // 저장 전 기본값
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
