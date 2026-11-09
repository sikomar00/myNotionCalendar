package com.ysdoing.notioncal.widget

import com.ysdoing.notioncal.core.data.view.FormField
import com.ysdoing.notioncal.core.data.view.InputForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 11h 레시피 양식이 날짜를 오늘로 시작하는 보기에서는 위젯 프리셋 줄을 눌러 만든 항목도 오늘 날짜로 만든다
 * (안 그러면 가계부의 이번 달 합계 타일이 날짜 없는 항목을 빼 버린다).
 */
class PresetDateTest {
    private val today = LocalDate.of(2026, 11, 5)

    @Test
    fun aFormThatStartsTheDateAtTodayGivesTodayToAPresetItem() {
        assertEquals(today, presetDate(InputForm(dateToday = true), today))
        assertEquals(today, presetDate(InputForm(listOf(FormField("n")), dateToday = true), today))
    }

    @Test
    fun withoutThatFlagOrWithoutAFormThePresetItemHasNoDateAsBefore() {
        assertNull(presetDate(null, today))
        assertNull(presetDate(InputForm(), today))
        assertNull(presetDate(InputForm(titleDate = true, oncePerDay = true), today))
    }

    @Test
    fun hidingTheDateChipsMeansNoDateEvenIfTheFlagWasStored() {
        assertNull(presetDate(InputForm(showDate = false, dateToday = true), today)) // 저장된 글자를 손으로 고친 경우도 날짜 칩 규칙을 따른다
    }
}
