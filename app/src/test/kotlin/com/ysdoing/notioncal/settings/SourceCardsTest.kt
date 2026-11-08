package com.ysdoing.notioncal.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 설정 DB 탭(2026-11-08 정리): 종류별 소제목 아래 카드, 짝 DB(습관 기록·습관, 거래내역·예산·카테고리)는 저장소처럼 함께 켜고 지우므로
 * 카드 하나·스위치 하나·삭제 하나. 부록 A-5·A-6: 스위치는 이름으로 읽히고 글꼴 2.0에서도 이름이 남는다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SourceCardsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun source(id: String, name: String, kind: SourceKind = SourceKind.CALENDAR, link: String? = null, enabled: Boolean = true) = DataSourceEntity(
        dataSourceId = id, databaseId = "db-$id", name = name, colorHex = "#0B6E99", titlePropId = "t", datePropId = "d",
        donePropId = null, doneType = null, doneOption = null, undoneOption = null, colorPropId = null, sortPropId = null, enabled = enabled,
        kind = kind.name, linkDataSourceId = link,
    )

    /** 등록 순서: 일정, 습관 기록, 습관, 거래내역, 예산, 카테고리, 두 번째 일정, 짝 잃은 예산. */
    private val sources = listOf(
        source("cal", "Calendar"),
        source("log", "✅ 습관 기록", SourceKind.HABIT_LOG, link = "habit"),
        source("habit", "🌱 습관", SourceKind.HABIT),
        source("tx", "💰 거래내역", SourceKind.LEDGER),
        source("budget", "예산", SourceKind.BUDGET, link = "tx"),
        source("cat", "카테고리", SourceKind.CATEGORY, link = "tx"),
        source("courses", "Courses"),
        source("lost", "옛 예산", SourceKind.BUDGET, link = "gone"),
    )

    private val actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, mutableStateOf(""))

    @Test
    fun groupsPairsIntoOneCardAndOrdersByKindKeepingRegistrationOrder() {
        val groups = groupSources(sources)
        assertEquals(
            listOf("Calendar", "Courses", "✅ 습관 기록 · 🌱 습관", "💰 거래내역 · 예산 · 카테고리", "옛 예산"),
            groups.map { it.title() },
        )
        assertEquals(listOf("cal", "courses", "log", "tx", "lost"), groups.map { it.main.dataSourceId }) // 스위치·삭제는 대표 DB로
    }

    @Test
    fun aPairCardHasOneSwitchAndOneDeleteNamedAfterTheWholePair() {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { MaterialTheme { Column { DatabasesContent(groupSources(sources), { id, on -> toggled += id to on }, actions) } } }
        compose.onAllNodesWithContentDescription("✅ 습관 기록 · 🌱 습관 사용").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("✅ 습관 기록 · 🌱 습관 등록 삭제").assertCountEquals(1)
        compose.onNodeWithContentDescription("✅ 습관 기록 · 🌱 습관 사용").assertIsOn().performClick()
        assertEquals(listOf("log" to false), toggled)
        listOf("일정", "습관", "가계부").forEach { compose.onNodeWithText(it).assertExists() } // 종류 소제목
        compose.onAllNodesWithText("색·강조").assertCountEquals(2) // 색·속성은 일정 DB 카드에만
    }

    /** '속성 연결'은 대표 DB의 (databaseId, dataSourceId)로 다시 연결 화면을 연다. 짝을 잃은 카드(옛 예산)에는 없다. */
    @Test
    fun reconnectOpensTheMainDatabaseAndIsHiddenOnOrphanCards() {
        val opened = mutableListOf<Pair<String, String>>()
        val withReconnect = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, mutableStateOf(""), onReconnect = { db, ds -> opened += db to ds })
        compose.setContent { MaterialTheme { Column { DatabasesContent(groupSources(sources), { _, _ -> }, withReconnect) } } }
        compose.onAllNodesWithText("속성 연결").assertCountEquals(4) // 일정 둘·습관·가계부, 옛 예산은 없음
        compose.onAllNodesWithText("속성 연결")[2].performClick()
        assertEquals(listOf("db-log" to "log"), opened)
    }

    @Test
    @Config(fontScale = 2.0f)
    fun aDisabledCalendarCardReadsAsOffAndKeepsItsNameAtLargeFonts() {
        var deleted = 0
        val off = SourceGroup(SourceGroupKind.CALENDAR, source("cal", "일정 데이터베이스", enabled = false), listOf(source("cal", "일정 데이터베이스", enabled = false)))
        compose.setContent { MaterialTheme { SourceCard(off, SourceCardActions({}, {}, {}, { deleted++ })) } }
        compose.onNodeWithContentDescription("일정 데이터베이스 사용").assertIsOff()
        compose.onNodeWithText("일정 데이터베이스").assertExists()
        compose.onNodeWithContentDescription("일정 데이터베이스 등록 삭제").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun screenshotDatabaseTab() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DatabasesContent(groupSources(sources.dropLast(1)), { _, _ -> }, actions)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/settings_databases.png")
    }
}
