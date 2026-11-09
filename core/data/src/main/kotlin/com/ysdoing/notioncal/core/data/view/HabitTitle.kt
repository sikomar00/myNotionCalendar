package com.ysdoing.notioncal.core.data.view

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 해빗트래커의 하루 한 줄 제목: 그날의 'M월 d일'(RC-03). 새 항목 시트(제목 비움)와 위젯의 첫 체크가 같은 제목을 만든다. */
object HabitTitle {
    private val FORMAT = DateTimeFormatter.ofPattern("M월 d일")

    fun of(date: LocalDate): String = FORMAT.format(date)
}
