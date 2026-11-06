package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.view.ActionChange
import com.ysdoing.notioncal.core.data.view.ActionOp
import com.ysdoing.notioncal.core.data.view.ViewAction
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phase 12c IX-07 액션 버튼 설정의 순수 부분: 고를 수 있는 속성, 기본 변경, 변경 편집 규칙, 저장 가능 판정, 목록 요약. */
@RunWith(RobolectricTestRunner::class)
class WidgetActionButtonSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val title = ViewColumn("t", "이름", "title")
    private val day = ViewColumn("wh", "날짜", "date")
    private val done = ViewColumn("dn", "완료", "checkbox")
    private val status = ViewColumn("st", "상태", "status", listOf(ViewChoice("o1", "할 일"), ViewChoice("o3", "완료")))
    private val pages = ViewColumn("pg", "쪽수", "number")
    private val memo = ViewColumn("m", "메모", "rich_text")
    private val formula = ViewColumn("f", "점수", "formula")
    private val columns = listOf(title, day, done, status, pages, memo, formula)
    private val blank = ViewAction("", "v1", "", emptyList())

    @Test
    fun onlyWritableSelectStatusCheckboxNumberAndDateColumnsCanBeChanged() {
        assertEquals(listOf(day, done, status, pages), actionColumns(columns))
    }

    @Test
    fun eachTypeStartsFromItsOwnDefaultChange() {
        assertEquals(ActionChange("dn", ActionOp.SET, "true"), defaultChange(done))
        assertEquals(ActionChange("st", ActionOp.SET, "o1"), defaultChange(status))
        assertEquals(ActionChange("pg", ActionOp.SET, "0"), defaultChange(pages))
        assertEquals(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1), defaultChange(day))
    }

    @Test
    fun pickingAViewStartsFromItsFirstChangeableProperty() {
        assertEquals(listOf("wh"), blank.withView("v1", columns).changes.map { it.propId })
        assertEquals(emptyList<ActionChange>(), blank.withView("v2", listOf(title, memo)).changes) // 바꿀 속성이 없는 보기
    }

    @Test
    fun anotherChangeTakesAnUnusedPropertyAndStopsAtTheLimitOrWhenNoneIsLeft() {
        var action = blank.withView("v1", columns)
        action = action.withNextChange(columns).withNextChange(columns)
        assertEquals(listOf("wh", "dn", "st"), action.changes.map { it.propId })
        assertEquals(3, action.withNextChange(columns).changes.size) // 상한 3
        assertEquals(1, blank.withView("v1", listOf(day)).withNextChange(listOf(day)).changes.size) // 남은 속성 없음
    }

    @Test
    fun replacingAndRemovingAChangeTouchesOnlyThatLine() {
        val action = blank.withView("v1", columns).withNextChange(columns)
        val replaced = action.replaceChange(1, ActionChange("dn", ActionOp.SET, "false"))
        assertEquals(listOf("true", "false"), listOf(action.changes[1].value, replaced.changes[1].value))
        assertEquals(listOf("dn"), replaced.withoutChange(0).changes.map { it.propId })
    }

    @Test
    fun anActionIsCompleteOnlyWithANameAChangeAndNumbersThatAreNumbers() {
        val ok = ViewAction("", "v1", "내일로", listOf(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1)))
        assertEquals(ok, ok.complete(columns))
        assertNull(ok.copy(name = " ").complete(columns))
        assertNull(ok.copy(changes = emptyList()).complete(columns))
        val number = ok.copy(changes = listOf(ActionChange("pg", ActionOp.SET, "12abc")))
        assertNull(number.complete(columns))
        assertEquals("40", number.copy(changes = listOf(ActionChange("pg", ActionOp.SET, " 40 "))).complete(columns)!!.changes.single().value)
    }

    @Test
    fun theSummaryNamesThePropertyAndWhatItBecomes() {
        assertEquals("날짜 → 내일로", changeSummary(context, ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1), day))
        assertEquals("날짜 → 오늘로", changeSummary(context, ActionChange("wh", ActionOp.TODAY), day))
        assertEquals("날짜 → 다음 주로", changeSummary(context, ActionChange("wh", ActionOp.SHIFT_DAYS, days = 7), day))
        assertEquals("날짜 → 3일 옮기기", changeSummary(context, ActionChange("wh", ActionOp.SHIFT_DAYS, days = 3), day))
        assertEquals("상태 → 완료", changeSummary(context, ActionChange("st", ActionOp.SET, "o3"), status))
        assertEquals("완료 → 체크", changeSummary(context, ActionChange("dn", ActionOp.SET, "true"), done))
        assertEquals("완료 → 체크 해제", changeSummary(context, ActionChange("dn", ActionOp.SET, "false"), done))
        assertEquals("쪽수 → 40", changeSummary(context, ActionChange("pg", ActionOp.SET, "40"), pages))
        assertEquals("속성 → 오늘로", changeSummary(context, ActionChange("gone", ActionOp.TODAY), null)) // 사라진 속성
    }
}
