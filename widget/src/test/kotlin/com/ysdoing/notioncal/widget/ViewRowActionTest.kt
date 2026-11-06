package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.ActionChange
import com.ysdoing.notioncal.core.data.view.ActionOp
import com.ysdoing.notioncal.core.data.view.ViewAction
import com.ysdoing.notioncal.core.data.view.ViewActions
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 12c IX-07 위젯 행 옆 액션 버튼: 그 보기의 첫 액션 하나를 행마다 칩으로 둔다(2×2보다 넓을 때). 눌리면 보기·행·액션·위젯 번호를 실어
 * 콜백이 돌고, 액션이 있는 보기의 행 시트 intent는 위젯 번호를 싣는다(시트가 액션 목록을 읽는다).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class ViewRowActionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val wide = DpSize(320.dp, 200.dp)
    private val compact = DpSize(150.dp, 200.dp)

    private val tomorrow = ViewAction("a1", "v1", "내일로", listOf(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1)))
    private val done = ViewAction("a2", "v1", "완료로", listOf(ActionChange("dn", ActionOp.SET, "true")))
    private val elsewhere = ViewAction("a3", "v2", "다른 보기", listOf(ActionChange("wh", ActionOp.TODAY)))

    private fun board(rows: Int = 2) = ViewBoard("v1", "할 일", "table", listOf("이름", "분류", "날짜"), (1..rows).map { ViewRow("p$it", "https://notion.so/p$it", listOf("할 일 $it", "집", "10/2$it")) }, now, null)

    private fun state(vararg actions: ViewAction) = ViewWidgetState(board(), WidgetConfig(1, viewId = "v1", actionsJson = ViewActions.encode(actions.toList())), now)

    @Test
    fun everyRowGetsTheFirstActionsChipThatRunsItOnThatRow() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(tomorrow, done), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewRowAction")).assertCountEquals(2) // 둘째 액션은 시트에만 있다
        onAllNodes(hasTextEqualTo("내일로")).assertCountEquals(2)
        onAllNodes(hasTextEqualTo("완료로")).assertCountEquals(0)
        val run = hasRunCallbackClickAction<ViewActionRunAction>(
            actionParametersOf(ViewCellAction.ViewKey to "v1", ViewCellAction.PageKey to "p1", ViewActionRunAction.ActionKey to "a1", ViewActionRunAction.WidgetKey to 1),
        )
        onAllNodes(hasTestTag("viewRowAction"))[0].assert(run)
        onAllNodes(hasTestTag("viewRowAction"))[0].assert(hasContentDescriptionEqualTo("내일로, 이 행에 적용"))
    }

    @Test
    fun theCompactSizeHasNoChipButTheRowSheetStillGetsTheWidgetNumber() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(compact)
        provideComposable { ViewWidgetContent(state(tomorrow), systemFontScale = 1f) }
        onAllNodes(hasTestTag("viewRowAction")).assertCountEquals(0)
        onAllNodes(hasTestTag("viewRow"))[0].assert(hasStartActivityClickAction(viewRowIntent(context, "v1", "p1", appWidgetId = 1)))
    }

    @Test
    fun aViewWithoutActionsKeepsTheOldRowAndSheetIntent() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state(elsewhere), systemFontScale = 1f) } // 다른 보기의 액션만 있다
        onAllNodes(hasTestTag("viewRowAction")).assertCountEquals(0)
        onAllNodes(hasTestTag("viewRow"))[0].assert(hasStartActivityClickAction(viewRowIntent(context, "v1", "p1")))
    }

    @Test
    fun theRowSheetIntentIsUniquePerWidgetAndCarriesTheWidgetNumber() {
        val one = viewRowIntent(context, "v1", "p1", 7)
        val two = viewRowIntent(context, "v1", "p1", 8)
        assertEquals(7, one.getIntExtra(com.ysdoing.notioncal.core.data.AppIntents.EXTRA_APPWIDGET_ID, 0))
        assertTrue(one.data != two.data) // PendingIntent가 두 위젯의 같은 행에서 서로를 덮지 않는다
        assertEquals(null, viewRowIntent(context, "v1", "p1").extras?.get(com.ysdoing.notioncal.core.data.AppIntents.EXTRA_APPWIDGET_ID))
    }

    @Test
    fun denseRowsWithChipsTranslateAndStayUnderTheViewIdBudget() = runTest(timeout = 5.minutes) {
        val failures = mutableListOf<String>()
        for (size in listOf(wide, DpSize(501.dp, 458.dp), DpSize(501.dp, 581.dp), DpSize(411.dp, 800.dp))) for (font in listOf(70, 150)) {
            val dense = ViewWidgetState(board(rows = 30), WidgetConfig(1, fontScale = font, viewId = "v1", actionsJson = ViewActions.encode(listOf(tomorrow))), now)
            runCatching { glanceIdsUsed(renderRemoteViews(context, size) { ViewWidgetContent(dense) }) }
                .onSuccess { if (it > GLANCE_VIEW_ID_BUDGET) failures += "${size.width}x${size.height} font=$font uses $it view ids" }
                .onFailure { failures += "${size.width}x${size.height} font=$font: ${it.message}" }
        }
        assertTrue(failures.joinToString(System.lineSeparator()), failures.isEmpty())
    }
}
