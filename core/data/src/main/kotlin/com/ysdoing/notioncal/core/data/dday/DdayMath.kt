package com.ysdoing.notioncal.core.data.dday

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** DD-02 세는 방식. DOWN: D-12 / D-Day / D+3(지난 뒤 숨김). UP: 지난 뒤에도 D+100. DAYS: 지난 뒤 '100일째'. */
enum class DdayMode { DOWN, UP, DAYS }

/**
 * 디데이 표기 한 개. [text]는 'D-12'·'D-Day · 2/3일째'·'100일째'. [main]은 위젯에서 크게 쓰는 앞부분('D-Day').
 * [digits]는 숫자 자릿수(위젯이 네 자리면 한 단계 작은 글자를 쓴다). [sortKey]는 목록 순서(진행 중·오늘 → 가까운 앞날 → 최근 지난 날).
 */
data class DdayLabel(
    val main: String,
    val sub: String?,
    val inProgress: Boolean,
    val hidden: Boolean,
    val digits: Int,
    val sortKey: Long,
) {
    val text: String get() = if (sub == null) main else "$main · $sub"
}

/** DD-02 디데이 계산(순수 함수). 날짜는 Asia/Seoul LocalDate(NS-07)로 받는다. 기간 일정은 시작일 기준이다. */
object DdayMath {
    const val DEFAULT_HIDE_AFTER_DAYS = 7

    fun label(
        start: LocalDate,
        end: LocalDate?,
        today: LocalDate,
        mode: DdayMode = DdayMode.DOWN,
        hideAfterDays: Int = DEFAULT_HIDE_AFTER_DAYS,
    ): DdayLabel {
        val diff = ChronoUnit.DAYS.between(today, start) // 앞날이면 양수
        val last = end?.takeIf { it.isAfter(start) }
        val inProgress = last != null && !today.isBefore(start) && !today.isAfter(last)
        val main = when {
            diff > 0 -> "D-$diff"
            mode == DdayMode.DAYS -> "${-diff + 1}일째"
            diff == 0L || inProgress -> "D-Day"
            else -> "D+${-diff}"
        }
        val sub = if (inProgress && mode != DdayMode.DAYS) {
            "${ChronoUnit.DAYS.between(start, today) + 1}/${ChronoUnit.DAYS.between(start, last) + 1}일째"
        } else {
            null
        }
        val daysPast = ChronoUnit.DAYS.between(last ?: start, today)
        val hidden = mode == DdayMode.DOWN && daysPast > hideAfterDays
        val past = diff < 0 && !inProgress
        val sortKey = if (past) PAST_OFFSET - diff else diff.coerceAtLeast(0)
        return DdayLabel(main, sub, inProgress, hidden, abs(diff).toString().length, sortKey)
    }

    /** 지난 디데이를 앞날 뒤에 두기 위한 간격(앞날 diff는 이보다 훨씬 작다). */
    private const val PAST_OFFSET = 1_000_000L
}
