package com.ysdoing.notioncal.core.data.quickinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** WI-07: 테스트 문장 50개 이상(PRD 완료 기준). 오늘은 2026-10-14(수)로 고정한다. */
class QuickInputParserTest {
    private val today = LocalDate.of(2026, 10, 14)

    private data class Case(
        val input: String,
        val title: String,
        val date: String? = null,
        val end: String? = null,
        val start: String? = null,
        val until: String? = null,
    )

    private val cases = listOf(
        // PRD 예시
        Case("내일 3시 회의", "회의", "2026-10-15", start = "15:00"),
        Case("10/20~22 출장", "출장", "2026-10-20", "2026-10-22"),
        Case("다음 주 월요일", "", "2026-10-19"),
        // 상대 날짜
        Case("오늘 저녁 7시 반 동창회", "동창회", "2026-10-14", start = "19:30"),
        Case("모레 오전 10시 치과", "치과", "2026-10-16", start = "10:00"),
        Case("글피 점심", "점심", "2026-10-17"),
        Case("3일 후 보고서 마감", "보고서 마감", "2026-10-17"),
        Case("10일 뒤 여행", "여행", "2026-10-24"),
        // 요일
        Case("이번 주 금요일 회식", "회식", "2026-10-16"),
        Case("이번 주 월요일 회고", "회고", "2026-10-12"),
        Case("다다음 주 수요일 발표", "발표", "2026-10-28"),
        Case("금요일 영화", "영화", "2026-10-16"),
        Case("수요일 스터디", "스터디", "2026-10-14"),
        Case("일요일 등산", "등산", "2026-10-18"),
        Case("토요일 오후 2시 결혼식", "결혼식", "2026-10-17", start = "14:00"),
        Case("다음 주 화요일 오전 10시 면접", "면접", "2026-10-20", start = "10:00"),
        // 절대 날짜
        Case("10월 20일 병원", "병원", "2026-10-20"),
        Case("10월 31일 할로윈", "할로윈", "2026-10-31"),
        Case("1월 2일 신년회", "신년회", "2027-01-02"),
        Case("10.25 결혼식", "결혼식", "2026-10-25"),
        Case("2026-12-24 크리스마스 이브", "크리스마스 이브", "2026-12-24"),
        Case("20일 월세", "월세", "2026-10-20"),
        Case("5일 카드값", "카드값", "2026-11-05"),
        Case("다음 달 5일 카드값", "카드값", "2026-11-05"),
        Case("이번 달 30일 정산", "정산", "2026-10-30"),
        Case("10/14 회의", "회의", "2026-10-14"),
        Case("10/13 회고", "회고", "2027-10-13"),
        Case("10/20 오후 6시 저녁 약속", "저녁 약속", "2026-10-20", start = "18:00"),
        // 기간
        Case("10/30~11/2 제주", "제주", "2026-10-30", "2026-11-02"),
        Case("10월 20일부터 22일까지 워크숍", "워크숍", "2026-10-20", "2026-10-22"),
        Case("20일~22일 출장", "출장", "2026-10-20", "2026-10-22"),
        Case("12/30~1/2 휴가", "휴가", "2026-12-30", "2027-01-02"),
        Case("10월 30일~11월 2일 학회", "학회", "2026-10-30", "2026-11-02"),
        // 시각 범위
        Case("내일 3시~5시 회의", "회의", "2026-10-15", start = "15:00", until = "17:00"),
        Case("내일 3시부터 5시까지 세미나", "세미나", "2026-10-15", start = "15:00", until = "17:00"),
        Case("오전 9시~11시 수업", "수업", start = "09:00", until = "11:00"),
        Case("오전 11시~1시 브런치", "브런치", start = "11:00", until = "13:00"),
        Case("오늘 10:00~11:30 회의", "회의", "2026-10-14", start = "10:00", until = "11:30"),
        Case("다음 주 월요일 3시~4시 반 상담", "상담", "2026-10-19", start = "15:00", until = "16:30"),
        // 시각 하나
        Case("15:30 미팅", "미팅", start = "15:30"),
        Case("3:30 미팅", "미팅", start = "15:30"),
        Case("8시 조깅", "조깅", start = "08:00"),
        Case("아침 7시 운동", "운동", start = "07:00"),
        Case("밤 11시 통화", "통화", start = "23:00"),
        Case("낮 2시 점심 약속", "점심 약속", start = "14:00"),
        Case("12시 점심", "점심", start = "12:00"),
        Case("3시 30분 상담", "상담", start = "15:30"),
        Case("새벽 5시 비행기", "비행기", start = "05:00"),
        Case("내일 오후 3시에 치과", "치과", "2026-10-15", start = "15:00"),
        Case("내일 10시 반 미용실", "미용실", "2026-10-15", start = "10:30"),
        Case("회의 내일 2시", "회의", "2026-10-15", start = "14:00"),
        // 해석하지 않아야 하는 문장
        Case("장보기", "장보기"),
        Case("3시간 운동", "3시간 운동"),
        Case("1시간 30분 산책", "1시간 30분 산책"),
        Case("7일차 리뷰", "7일차 리뷰"),
        Case("2월 30일 이상한 날", "2월 30일 이상한 날"),
    )

    @Test
    fun atLeastFiftySentences() {
        assertTrue("PRD 완료 기준: 테스트 문장 50개 이상(현재 ${cases.size})", cases.size >= 50)
    }

    @Test
    fun parsesAllSentences() {
        val failures = cases.mapNotNull { c ->
            val r = QuickInputParser.parse(c.input, today)
            val expected = QuickInput(
                title = c.title,
                date = c.date?.let(LocalDate::parse),
                endDate = c.end?.let(LocalDate::parse),
                start = c.start?.let(LocalTime::parse),
                end = c.until?.let(LocalTime::parse),
            )
            if (r == expected) null else "\"${c.input}\"\n  기대 $expected\n  실제 $r"
        }
        assertEquals("실패 ${failures.size}건\n" + failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun plainTitleHasNoSchedule() {
        assertEquals(false, QuickInputParser.parse("장보기", today).hasSchedule)
        assertEquals(true, QuickInputParser.parse("내일 장보기", today).hasSchedule)
    }
}
