package com.ysdoing.notioncal.feature.quicksheet

import com.ysdoing.notioncal.core.data.view.FormField
import com.ysdoing.notioncal.core.data.view.InputForm
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 새 항목 시트가 만들 날짜·제목·막힘을 정하는 순수 규칙(화면 없이): 고른 칩 > 글에서 읽은 날짜 > 양식의 오늘 기본값, 제목이 비면 그 날짜로,
 * 하루 한 줄이면 그 날짜에 항목이 이미 있을 때 막는다.
 */
class AddResolutionTest {
    private val today = LocalDate.of(2026, 11, 4)
    private val columns = listOf(
        ViewColumn("t", "이름", "title"), ViewColumn("d", "날짜", "date"), ViewColumn("n", "금액", "number"),
        ViewColumn("k", "분류", "select", listOf(ViewChoice("o1", "식비"))), ViewColumn("c", "운동", "checkbox"),
    )

    private fun plan(input: InputForm?, hasDate: Boolean = true) = sheetPlan(columns, hasDate, input, ViewAddPrefill())

    private fun resolve(
        choice: DateChoice = DateChoice.AUTO,
        input: InputForm? = null,
        quickDate: LocalDate? = null,
        quickTitle: String = "",
        pickedIso: String? = null,
        taken: Set<LocalDate> = emptySet(),
        presetOnly: Boolean = false,
    ) = resolveAdd(choice, pickedIso, quickDate, quickTitle, plan(input), today, taken, presetOnly)

    // --- 날짜

    @Test
    fun autoUsesTheDateReadFromTheSentenceThenTheTodayDefaultThenNothing() {
        assertEquals(today.plusDays(1), resolve(quickDate = today.plusDays(1), input = InputForm(dateToday = true)).date)
        assertEquals(today, resolve(input = InputForm(dateToday = true)).date)
        assertNull(resolve(input = InputForm()).date)
        assertNull(resolve(input = null).date)
    }

    @Test
    fun anExplicitChoiceBeatsTheAutoDate() {
        val form = InputForm(dateToday = true)
        assertNull(resolve(DateChoice.EMPTY, form, quickDate = today.plusDays(2)).date)
        assertEquals(today, resolve(DateChoice.TODAY, InputForm()).date)
        assertEquals(today.plusDays(1), resolve(DateChoice.TOMORROW, form).date)
        assertEquals(LocalDate.of(2026, 12, 25), resolve(DateChoice.PICK, form, pickedIso = "2026-12-25").date)
        assertNull(resolve(DateChoice.PICK, form).date) // 직접 고르기를 눌렀지만 아직 날짜를 고르지 않았다
    }

    @Test
    fun theTodayDefaultNeedsTheDateChipsToBeShownAndADateColumn() {
        assertNull(resolve(input = InputForm(showDate = false, dateToday = true)).date)
        assertNull(resolveAdd(DateChoice.AUTO, null, null, "", plan(InputForm(dateToday = true), hasDate = false), today, emptySet(), false).date)
    }

    @Test
    fun autoTodayIsTrueOnlyWhenTheDefaultIsWhatGetsUsed() {
        val form = InputForm(dateToday = true)
        assertTrue(resolve(input = form).autoToday)
        assertFalse(resolve(input = form, quickDate = today.plusDays(1)).autoToday) // 글에서 읽은 날짜가 쓰인다
        assertFalse(resolve(DateChoice.EMPTY, form).autoToday)
        assertFalse(resolve(DateChoice.TODAY, form).autoToday) // 직접 고른 오늘은 칩이 직접 선택돼 보인다
        assertFalse(resolve(input = InputForm()).autoToday)
    }

    // --- 제목

    @Test
    fun aBlankTitleBecomesTheChosenDatesText() {
        val form = InputForm(titleDate = true)
        assertEquals("11월 4일", resolve(input = form).title) // 날짜가 없으면 오늘
        assertEquals("11월 5일", resolve(DateChoice.TOMORROW, form).title) // 내일을 골랐으면 내일 날짜
        assertEquals("12월 25일", resolve(DateChoice.PICK, form, pickedIso = "2026-12-25").title)
        assertEquals("11월 9일", resolve(input = form, quickDate = LocalDate.of(2026, 11, 9)).title) // 글에서 읽은 날짜
        assertEquals("11월 4일", resolve(DateChoice.EMPTY, form).title) // 날짜 없음이면 오늘 날짜를 제목으로
    }

    @Test
    fun theHintIsWhatABlankTitleWouldBecomeAndATypedTitleWins() {
        val form = InputForm(titleDate = true, dateToday = true)
        assertEquals("11월 4일", resolve(input = form).titleHint)
        assertEquals("아침 운동", resolve(input = form, quickTitle = "아침 운동").title)
        assertEquals("11월 4일", resolve(input = form, quickTitle = "아침 운동").titleHint) // 글자를 쳐도 힌트는 같다(화면이 쓰지 않을 뿐)
    }

    @Test
    fun withoutTheFlagABlankTitleStaysBlankAndMakingAPresetNeverFillsOne() {
        assertEquals("", resolve(input = InputForm(dateToday = true)).title)
        assertNull(resolve(input = InputForm(dateToday = true)).titleHint)
        val preset = resolve(input = InputForm(titleDate = true), presetOnly = true)
        assertEquals("", preset.title)
        assertNull(preset.titleHint)
    }

    // --- 하루 한 줄

    @Test
    fun oncePerDayBlocksOnlyTheDaysThatAreAlreadyTaken() {
        val form = InputForm(dateToday = true, oncePerDay = true)
        assertTrue(resolve(input = form, taken = setOf(today)).dayTaken)
        assertFalse(resolve(input = form, taken = setOf(today.minusDays(1))).dayTaken)
        assertFalse(resolve(DateChoice.TOMORROW, form, taken = setOf(today)).dayTaken) // 다른 날을 고르면 만들 수 있다
        assertFalse(resolve(DateChoice.EMPTY, form, taken = setOf(today)).dayTaken) // 날짜 없음은 하루에 속하지 않는다
    }

    @Test
    fun oncePerDayIsOffWithoutTheFlagAndWhenMakingAPreset() {
        assertFalse(resolve(input = InputForm(dateToday = true), taken = setOf(today)).dayTaken)
        assertFalse(resolve(input = InputForm(dateToday = true, oncePerDay = true), taken = setOf(today), presetOnly = true).dayTaken)
    }

    // --- 시트가 입력 양식을 읽는 방식(sheetPlan)

    @Test
    fun aFormThatOnlyStartsTheDateAtTodayDoesNotRestrictWhatTheSentenceIsReadInto() {
        // 일정·할 일 레시피는 날짜만 오늘로 시작하려는 것이지 한 줄 입력의 해석(금액·옵션)을 막으려는 게 아니다.
        val plan = plan(InputForm(dateToday = true))
        assertEquals(listOf("t", "d", "n", "k", "c"), plan.readable.map { it.id })
        assertEquals(emptyList<String>(), plan.fields.map { it.id }) // 속성 편집기는 없다(제목과 날짜만)
    }

    @Test
    fun aFormThatPicksNothingAndHidesTheDateStillMeansTitleOnly() {
        assertEquals(emptyList<String>(), plan(InputForm(showDate = false)).readable.map { it.id })
    }

    @Test
    fun aFormWithFieldsReadsOnlyThoseFieldsAndTheDate() {
        val plan = plan(InputForm(listOf(FormField("n")), dateToday = true))
        assertEquals(listOf("n", "d"), plan.readable.map { it.id })
    }
}
