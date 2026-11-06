package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.ActionChange
import com.ysdoing.notioncal.core.data.view.ActionOp
import com.ysdoing.notioncal.core.data.view.ViewAction
import com.ysdoing.notioncal.core.data.view.ViewActions
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Phase 12c IX-07: 행 옆 액션 칩이 목록·표 행에서 어떻게 보이는지 기준 이미지를 남긴다(예시 데이터, 개인 데이터 아님). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class ViewRowActionScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val tomorrow = ViewAction("a1", "v1", "내일로", listOf(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1)))
    private val rows = listOf("장보기" to "집", "보고서 쓰기" to "회사", "운동" to "집", "치과 예약" to "병원").mapIndexed { i, (title, place) -> ViewRow("p$i", "", listOf(title, place, "11/0${i + 4}")) }
    private val board = ViewBoard("v1", "할 일", "table", listOf("이름", "분류", "날짜"), rows, now, null)
    private fun state() = ViewWidgetState(board, WidgetConfig(1, viewId = "v1", actionsJson = ViewActions.encode(listOf(tomorrow))), now)

    @Test
    fun screenshotTableWithRowAction() = runTest {
        renderRemoteViews(context, DpSize(501.dp, 300.dp)) { ViewWidgetContent(state(), systemFontScale = 1f, staticRows = true) }
            .captureRoboImage("src/test/screenshots/viewrow_action_table.png")
    }

    @Test
    fun screenshotListWithRowAction() = runTest {
        renderRemoteViews(context, DpSize(300.dp, 300.dp)) { ViewWidgetContent(state(), systemFontScale = 1f, staticRows = true) }
            .captureRoboImage("src/test/screenshots/viewrow_action_list.png")
    }
}
