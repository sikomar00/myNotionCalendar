package com.ysdoing.notioncal.core.data.quickinput

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** WI-07 해석 결과. 날짜·시간을 찾지 못한 항목은 null이고, 해석에 쓴 글자는 [title]에서 빠진다. */
data class QuickInput(
    val title: String,
    val date: LocalDate? = null,
    /** 기간 일정의 마지막 날(포함). */
    val endDate: LocalDate? = null,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
) {
    val hasSchedule: Boolean get() = date != null || start != null
}

/**
 * WI-07 한국어 빠른 입력 해석(규칙 기반). 예: "내일 3시 회의", "10/20~22 출장", "다음 주 월요일 저녁 7시 반 동창회".
 *
 * 규칙
 * - 날짜를 먼저, 시각을 나중에 찾는다. 같은 글자를 두 번 쓰지 않도록 찾은 부분은 지운다.
 * - 연도가 없는 날짜가 오늘보다 앞이면 다음 해로, 'N일'만 있으면 이번 달(지났으면 다음 달)로 본다.
 * - 오전·오후 표시가 없는 1~7시는 오후로 본다(일정 입력에서 새벽 1~7시는 드물다).
 * - 주는 월요일에 시작한다('이번 주 월요일'은 이번 주의 월요일).
 */
object QuickInputParser {
    private const val DAY_NAMES = "월화수목금토일"
    private const val PERIOD = "오전|오후|아침|낮|저녁|밤|새벽"
    private const val TILDE = "\\s*(?:~|-|부터)\\s*"
    private const val UNTIL = "(?:\\s*까지)?"
    private const val PARTICLE = "(?:\\s*(?:에|에는|부터))?"

    // 시각 하나: (오후)? 3시 (반|30분)? 또는 15:30. '3시간'(길이)은 시각이 아니다.
    private const val TIME = "(?:($PERIOD)\\s*)?(?:(\\d{1,2})\\s*시(?!간)(?:\\s*(반|\\d{1,2}\\s*분))?|(\\d{1,2}):(\\d{2}))"

    private val dateRules: List<Pair<Regex, (MatchResult, LocalDate) -> Pair<LocalDate, LocalDate?>?>> = listOf(
        // 2026-10-20
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})") to { m, _ ->
            safeDate(m.int(1), m.int(2), m.int(3))?.let { it to null }
        },
        // 10/20~10/22, 10/20~22, 10.20~22
        Regex("(\\d{1,2})[/.](\\d{1,2})${TILDE}(?:(\\d{1,2})[/.])?(\\d{1,2})(?![\\d:시/.])$UNTIL") to { m, today ->
            val start = monthDay(today, m.int(1), m.int(2)) ?: return@to null
            val endMonth = m.groupValues[3].toIntOrNull() ?: start.monthValue
            val end = safeDate(start.year, endMonth, m.int(4))?.let { if (it < start) it.plusYears(1) else it }
            start to end
        },
        // 10월 20일부터 22일까지, 10월 20일~11월 2일
        Regex("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일${TILDE}(?:(\\d{1,2})\\s*월\\s*)?(\\d{1,2})\\s*일$UNTIL") to { m, today ->
            val start = monthDay(today, m.int(1), m.int(2)) ?: return@to null
            val endMonth = m.groupValues[3].toIntOrNull() ?: start.monthValue
            val end = safeDate(start.year, endMonth, m.int(4))?.let { if (it < start) it.plusYears(1) else it }
            start to end
        },
        // 20일~22일, 20일부터 22일까지
        Regex("(?<![\\d월])(\\d{1,2})\\s*일${TILDE}(\\d{1,2})\\s*일$UNTIL") to { m, today ->
            val start = dayOfComingMonth(today, m.int(1)) ?: return@to null
            val end = safeDate(start.year, start.monthValue, m.int(2))?.let { if (it < start) it.plusMonths(1) else it }
            start to end
        },
        // 10월 20일
        Regex("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일") to { m, today -> monthDay(today, m.int(1), m.int(2))?.let { it to null } },
        // 10/20, 10.20 (시각 3:30과 겹치지 않게 콜론은 쓰지 않는다)
        Regex("(?<![\\d:])(\\d{1,2})[/.](\\d{1,2})(?![\\d:])") to { m, today -> monthDay(today, m.int(1), m.int(2))?.let { it to null } },
        // 다음 달 5일, 이번 달 30일
        Regex("(이번|다음|다다음)\\s*달\\s*(\\d{1,2})\\s*일") to { m, today ->
            val plus = when (m.groupValues[1]) { "이번" -> 0L; "다음" -> 1L; else -> 2L }
            val ym = YearMonth.from(today).plusMonths(plus)
            safeDate(ym.year, ym.monthValue, m.int(2))?.let { it to null }
        },
        // 3일 후, 10일 뒤
        Regex("(\\d{1,3})\\s*일\\s*(?:후|뒤)") to { m, today -> today.plusDays(m.int(1).toLong()) to null },
        // 오늘·내일·모레·글피
        Regex("(오늘|내일|모레|글피)") to { m, today ->
            val plus = when (m.groupValues[1]) { "오늘" -> 0L; "내일" -> 1L; "모레" -> 2L; else -> 3L }
            today.plusDays(plus) to null
        },
        // 이번 주·다음 주·다다음 주 X요일
        Regex("(이번|다음|다다음)\\s*주\\s*([$DAY_NAMES])\\s*요일") to { m, today ->
            val weeks = when (m.groupValues[1]) { "이번" -> 0L; "다음" -> 1L; else -> 2L }
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weeks)
            monday.plusDays(dayIndex(m.groupValues[2]).toLong()) to null
        },
        // X요일: 오늘을 포함해 가장 가까운 그 요일
        Regex("([$DAY_NAMES])\\s*요일") to { m, today ->
            today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dayIndex(m.groupValues[1]) + 1))) to null
        },
        // 20일 (이번 달, 지났으면 다음 달). '3일 후'·'20일~'은 위에서 먼저 처리한다.
        Regex("(?<![\\d월/.])(\\d{1,2})\\s*일(?!\\s*(?:후|뒤|~|부터|간|차))") to { m, today ->
            dayOfComingMonth(today, m.int(1))?.let { it to null }
        },
    )

    private val timeRange = Regex("$TIME${TILDE}$TIME$UNTIL$PARTICLE")
    private val timeSingle = Regex("$TIME$PARTICLE")

    fun parse(input: String, today: LocalDate): QuickInput {
        var text = " ${input.trim()} "
        var date: LocalDate? = null
        var endDate: LocalDate? = null
        for ((regex, rule) in dateRules) {
            val match = regex.find(text) ?: continue
            // 날짜 꼴이지만 없는 날(2월 30일)이면 더 찾지 않는다. 뒤 규칙이 '30일'만 집어 엉뚱한 날을 만들지 않게.
            val (start, end) = rule(match, today) ?: break
            date = start
            endDate = end?.takeIf { it > start }
            text = text.removeRange(match.range).let { removeParticle(it, match.range.first) }
            break
        }

        var start: LocalTime? = null
        var end: LocalTime? = null
        val range = timeRange.find(text)
        if (range != null) {
            val g = range.groupValues
            val s = time(g[1], g[2], g[3], g[4], g[5])
            val e = time(g[6].ifEmpty { g[1] }, g[7], g[8], g[9], g[10], startHint = s, explicitPeriod = g[6].isNotEmpty())
            if (s != null && e != null) {
                start = s
                end = e
                text = text.removeRange(range.range)
            }
        }
        if (start == null) {
            val single = timeSingle.find(text)
            if (single != null) {
                val g = single.groupValues
                time(g[1], g[2], g[3], g[4], g[5])?.let {
                    start = it
                    text = text.removeRange(single.range)
                }
            }
        }
        val title = text.replace(Regex("\\s+"), " ").trim().trim(',', '·')
        return QuickInput(title = title, date = date, endDate = endDate, start = start, end = end)
    }

    /** "내일에 회의"처럼 날짜 뒤에 붙은 조사를 지운다. */
    private fun removeParticle(text: String, at: Int): String {
        val rest = text.substring(at)
        val particle = Regex("^\\s*(?:에는|에|부터|까지)(?=\\s)").find(rest) ?: return text
        return text.substring(0, at) + rest.substring(particle.range.last + 1)
    }

    /**
     * 시각 해석. [startHint]가 있으면(범위의 끝) 표시 없는 시각을 시작보다 뒤가 되게 맞춘다.
     */
    private fun time(
        period: String,
        hourText: String,
        minuteText: String,
        clockHour: String,
        clockMinute: String,
        startHint: LocalTime? = null,
        explicitPeriod: Boolean = true,
    ): LocalTime? {
        val (hour, minute) = when {
            hourText.isNotEmpty() -> hourText.toInt() to when {
                minuteText.isEmpty() -> 0
                minuteText == "반" -> 30
                else -> minuteText.filter(Char::isDigit).toInt()
            }
            clockHour.isNotEmpty() -> clockHour.toInt() to clockMinute.toInt()
            else -> return null
        }
        if (hour > 24 || minute > 59) return null
        var h = when (period) {
            "오후", "저녁", "밤" -> if (hour in 1..11) hour + 12 else hour
            "낮" -> if (hour in 1..6) hour + 12 else hour
            "오전", "아침", "새벽" -> if (hour == 12) 0 else hour
            else -> if (hour in 1..7) hour + 12 else hour
        }
        if (hour == 24) h = 0
        if (startHint != null && !explicitPeriod && h < startHint.hour && h + 12 < 24) h += 12
        return LocalTime.of(h % 24, minute)
    }

    private fun dayIndex(name: String) = DAY_NAMES.indexOf(name)

    private fun MatchResult.int(group: Int) = groupValues[group].toInt()

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /** 연도 없는 월·일. 오늘보다 앞이면 다음 해. */
    private fun monthDay(today: LocalDate, month: Int, day: Int): LocalDate? {
        val thisYear = safeDate(today.year, month, day) ?: return null
        return if (thisYear < today) safeDate(today.year + 1, month, day) else thisYear
    }

    /** 'N일'만 있을 때: 이번 달, 지났으면 다음 달. */
    private fun dayOfComingMonth(today: LocalDate, day: Int): LocalDate? {
        val thisMonth = safeDate(today.year, today.monthValue, day)
        if (thisMonth != null && thisMonth >= today) return thisMonth
        val next = YearMonth.from(today).plusMonths(1)
        return safeDate(next.year, next.monthValue, day)
    }
}
