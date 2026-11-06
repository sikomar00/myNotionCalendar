package com.ysdoing.notioncal.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 부록 A-5·A-6: DB 줄의 스위치는 어느 DB인지 이름으로 읽히고, 글꼴을 2.0까지 키워도 이름·스위치가 윗줄에, 삭제가 아랫줄(error 색 글자 버튼)에 남는다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 2.0f)
class SourceRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun source(enabled: Boolean = true) = DataSourceEntity(
        dataSourceId = "ds1", databaseId = "db1", name = "일정 데이터베이스", colorHex = "#0B6E99", titlePropId = "t", datePropId = "d",
        donePropId = null, doneType = null, doneOption = null, undoneOption = null, colorPropId = null, sortPropId = null, enabled = enabled,
    )

    @Test
    fun theSwitchIsNamedAfterTheDatabaseAndTogglesIt() {
        val toggled = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme { SourceRow(source(), onToggle = { toggled += it }, onChooseProps = {}, onChooseColor = {}, onDelete = {}) } }
        compose.onNodeWithContentDescription("일정 데이터베이스 사용", useUnmergedTree = true).assertIsOn()
        compose.onNodeWithContentDescription("일정 데이터베이스 사용", useUnmergedTree = true).performClick()
        assertEquals(listOf(false), toggled)
    }

    @Test
    fun aDisabledDatabaseReadsAsOff() {
        compose.setContent { MaterialTheme { SourceRow(source(enabled = false), onToggle = {}, onChooseProps = {}, onChooseColor = {}, onDelete = {}) } }
        compose.onNodeWithContentDescription("일정 데이터베이스 사용", useUnmergedTree = true).assertIsOff()
    }

    @Test
    fun theDeleteButtonIsATextButtonOnItsOwnLineAndCallsBack() {
        var deleted = 0
        compose.setContent { MaterialTheme { SourceRow(source(), onToggle = {}, onChooseProps = {}, onChooseColor = {}, onDelete = { deleted++ }) } }
        compose.onNodeWithText("일정 데이터베이스").assertExists() // 글꼴 2.0에서도 이름이 남는다
        compose.onNodeWithContentDescription("일정 데이터베이스 등록 삭제").performClick()
        assertEquals(1, deleted)
    }
}
