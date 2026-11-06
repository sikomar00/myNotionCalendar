package com.ysdoing.notioncal.core.data.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 11g AD-03 한 줄 입력 해석: 날짜·금액(숫자)·옵션 이름을 찾고 나머지가 제목이다. PRD 완료 기준 '테스트 문장 80개'.
 * 오늘은 2026-10-14(수)로 고정한다(날짜 규칙은 [com.ysdoing.notioncal.core.data.quickinput.QuickInputParserTest]가 따로 본다).
 */
class ViewQuickInputTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val title = ViewColumn("t", "이름", "title")
    private val amount = ViewColumn("n1", "금액", "number")
    private val qty = ViewColumn("n2", "수량", "number")
    private val category = ViewColumn("c1", "분류", "select", listOf(ViewChoice("o-food", "식비"), ViewChoice("o-move", "교통"), ViewChoice("o-shop", "쇼핑"), ViewChoice("o-cafe", "Cafe")))
    private val status = ViewColumn("s1", "상태", "status", listOf(ViewChoice("s-todo", "할일"), ViewChoice("s-doing", "진행중"), ViewChoice("s-done", "완료")))
    private val tags = ViewColumn("m1", "태그", "multi_select", listOf(ViewChoice("m-work", "업무"), ViewChoice("m-home", "집"), ViewChoice("m-lunch", "점심")))
    private val date = ViewColumn("d1", "날짜", "date")
    private val note = ViewColumn("r1", "메모", "rich_text")

    private val ledger = listOf(title, amount, category, date)
    private val noDate = listOf(title, amount, category)
    private val noNumber = listOf(title, category, date)
    private val todo = listOf(title, status, tags, date, note)
    private val twoNumbers = listOf(title, amount, qty, category, date)

    private data class Case(val columns: List<ViewColumn>, val input: String, val title: String, val date: String? = null, val props: List<String> = emptyList(), val chips: List<String>? = null)

    private fun summary(result: ViewQuickResult, columns: List<ViewColumn>) =
        result.props.map { (id, value) -> "$id=${columns.first { it.id == id }.display(value)}" }.sorted()

    private fun check(case: Case) {
        val result = ViewQuickInput.parse(case.input, case.columns, today)
        val where = "'${case.input}'"
        assertEquals("$where 제목", case.title, result.title)
        assertEquals("$where 날짜", case.date?.let(LocalDate::parse), result.date)
        assertEquals("$where 값", case.props.sorted(), summary(result, case.columns))
        case.chips?.let { assertEquals("$where 칩", it, result.chips.map { chip -> chip.label }) }
    }

    private fun cases() = listOf(
        // 장부: 날짜 + 금액 + 분류
        Case(ledger, "점심 12000 식비", "점심", props = listOf("n1=12000", "c1=식비"), chips = listOf("금액 12000", "분류 식비")),
        Case(ledger, "점심 12,000 식비", "점심", props = listOf("n1=12000", "c1=식비")),
        Case(ledger, "점심 12000원 식비", "점심", props = listOf("n1=12000", "c1=식비")),
        Case(ledger, "택시 8500 교통", "택시", props = listOf("n1=8500", "c1=교통")),
        Case(ledger, "내일 점심 12000 식비", "점심", "2026-10-15", listOf("n1=12000", "c1=식비"), chips = listOf("10/15", "금액 12000", "분류 식비")),
        Case(ledger, "오늘 커피 4500 cafe", "커피", "2026-10-14", listOf("n1=4500", "c1=Cafe")),
        Case(ledger, "커피 4500 CAFE", "커피", props = listOf("n1=4500", "c1=Cafe")),
        Case(ledger, "식비 점심 12000", "점심", props = listOf("n1=12000", "c1=식비")), // 순서는 상관없다
        Case(ledger, "12000 점심", "점심", props = listOf("n1=12000")),
        Case(ledger, "점심 식비", "점심", props = listOf("c1=식비")),
        Case(ledger, "점심 12000", "점심", props = listOf("n1=12000")),
        Case(ledger, "점심", "점심"),
        Case(ledger, "12000", "12000"), // 제목이 비게 되면 해석하지 않는다
        Case(ledger, "식비", "식비"),
        Case(ledger, "내일", "내일"),
        Case(ledger, "12000 식비", "12000 식비"),
        Case(ledger, "내일 12000", "내일 12000"),
        Case(ledger, "마트 1.5 쇼핑", "마트", "2027-01-05", listOf("c1=쇼핑")), // 점이 든 숫자는 날짜 열이 있으면 날짜(1월 5일)로 먼저 읽힌다
        Case(noDate, "마트 1.5 쇼핑", "마트", props = listOf("n1=1.5", "c1=쇼핑")), // 날짜 열이 없으면 소수 금액이다
        Case(ledger, "마트 1,234,567 쇼핑", "마트", props = listOf("n1=1234567", "c1=쇼핑")),
        Case(ledger, "마트 12,00 쇼핑", "마트 12,00", props = listOf("c1=쇼핑")), // 쉼표 자리가 틀리면 숫자가 아니다
        Case(ledger, "마트 3개 쇼핑", "마트 3개", props = listOf("c1=쇼핑")),
        Case(ledger, "마트 쇼핑 교통", "마트 교통", props = listOf("c1=쇼핑")), // 한 속성에는 옵션 하나
        Case(ledger, "마트 12000 5000", "마트 5000", props = listOf("n1=12000")), // 숫자는 첫 번째만
        Case(ledger, "택시 8500 교통 내일", "택시", "2026-10-15", listOf("n1=8500", "c1=교통")),
        Case(ledger, "10/20 점심 12000 식비", "점심", "2026-10-20", listOf("n1=12000", "c1=식비")),
        Case(ledger, "다음 주 월요일 회의", "회의", "2026-10-19"),
        Case(ledger, "3일 후 12000 점심 식비", "점심", "2026-10-17", listOf("n1=12000", "c1=식비")),
        Case(ledger, "점심식비 12000", "점심식비", props = listOf("n1=12000")), // 낱말이 옵션 이름과 같아야 한다
        Case(ledger, "점심 식비!", "점심 식비!"),
        Case(ledger, "10월 20일 병원 30000 기타", "병원 기타", "2026-10-20", listOf("n1=30000")),
        Case(ledger, "금요일 영화 15000", "영화", "2026-10-16", listOf("n1=15000")),
        Case(ledger, "2026-12-24 선물 50000 쇼핑", "선물", "2026-12-24", listOf("n1=50000", "c1=쇼핑")),
        Case(ledger, "1월 2일 신년회 100000", "신년회", "2027-01-02", listOf("n1=100000")),
        Case(ledger, "5일 카드값 450000", "카드값", "2026-11-05", listOf("n1=450000")),
        Case(ledger, "내일 3시 회의 식비", "3시 회의", "2026-10-15", listOf("c1=식비")), // 시각은 아직 못 쓰니 제목에 남는다
        Case(ledger, "오늘 오후 2시 커피 4500 cafe", "오후 2시 커피", "2026-10-14", listOf("n1=4500", "c1=Cafe")),
        Case(ledger, "10/20~22 출장 식비", "10/20~22 출장", props = listOf("c1=식비")), // 기간은 해석하지 않는다
        Case(ledger, "10/20~22 출장 12000", "10/20~22 출장", props = listOf("n1=12000")),
        Case(ledger, "점심 12000 식비 교통", "점심 교통", props = listOf("n1=12000", "c1=식비")),
        Case(ledger, "  점심   12000  식비  ", "점심", props = listOf("n1=12000", "c1=식비")),
        Case(ledger, "점심 0 식비", "점심", props = listOf("n1=0", "c1=식비")),
        Case(ledger, "점심 007 식비", "점심", props = listOf("n1=7", "c1=식비")),
        Case(ledger, "점심 -500 식비", "점심 -500", props = listOf("c1=식비")),
        Case(ledger, "점심 12000won 식비", "점심 12000won", props = listOf("c1=식비")),
        Case(ledger, "점심 1e3 식비", "점심 1e3", props = listOf("c1=식비")),
        Case(ledger, "점심 ₩12000 식비", "점심 ₩12000", props = listOf("c1=식비")),
        Case(ledger, "점심 12000 식비 내일", "점심", "2026-10-15", listOf("n1=12000", "c1=식비")),
        Case(ledger, "내일 모레 점심", "모레 점심", "2026-10-15"),
        Case(ledger, "수요일 점심 12000 식비", "점심", "2026-10-14", listOf("n1=12000", "c1=식비")),
        Case(ledger, "점심 12000 식비 10/20", "점심", "2026-10-20", listOf("n1=12000", "c1=식비")),
        Case(ledger, "점심 12000 식비 오늘", "점심", "2026-10-14", listOf("n1=12000", "c1=식비")),
        // 날짜 열이 없는 보기: 날짜 낱말은 제목에 남는다
        Case(noDate, "내일 점심 12000 식비", "내일 점심", props = listOf("n1=12000", "c1=식비")),
        Case(noDate, "오늘 커피 4500 cafe", "오늘 커피", props = listOf("n1=4500", "c1=Cafe")),
        Case(noDate, "10/20 점심 12000", "10/20 점심", props = listOf("n1=12000")),
        Case(noDate, "다음 주 월요일 회의", "다음 주 월요일 회의"),
        Case(noDate, "점심 12000 식비", "점심", props = listOf("n1=12000", "c1=식비")),
        // 숫자 열이 없는 보기: 숫자는 제목에 남는다
        Case(noNumber, "점심 12000 식비", "점심 12000", props = listOf("c1=식비")),
        Case(noNumber, "내일 점심 12000 식비", "점심 12000", "2026-10-15", listOf("c1=식비")),
        Case(noNumber, "12000 식비", "12000", props = listOf("c1=식비")),
        Case(noNumber, "택시 교통", "택시", props = listOf("c1=교통")),
        Case(noNumber, "커피 cafe", "커피", props = listOf("c1=Cafe")),
        // 할 일: 상태·다중 선택·메모
        Case(todo, "보고서 완료", "보고서", props = listOf("s1=완료")),
        Case(todo, "내일 보고서 진행중 업무", "보고서", "2026-10-15", listOf("s1=진행중", "m1=업무")),
        Case(todo, "집 청소", "청소", props = listOf("m1=집")),
        Case(todo, "업무 집 정리", "집 정리", props = listOf("m1=업무")),
        Case(todo, "점심 약속 12000", "약속 12000", props = listOf("m1=점심")),
        Case(todo, "할일 정리", "정리", props = listOf("s1=할일")),
        Case(todo, "진행 중 작업", "진행 중 작업"), // 띄어 쓴 옵션 이름은 낱말 하나가 아니라 맞지 않는다
        Case(todo, "완료 완료 확인", "완료 확인", props = listOf("s1=완료")),
        Case(todo, "메모 작성 업무", "메모 작성", props = listOf("m1=업무")),
        Case(todo, "수요일 회의 업무 집", "회의 집", "2026-10-14", listOf("m1=업무")),
        Case(todo, "모레 병원 집", "병원", "2026-10-16", listOf("m1=집")),
        // 숫자 열이 둘: 첫 번째 열에만
        Case(twoNumbers, "점심 12000 3 식비", "점심 3", props = listOf("n1=12000", "c1=식비")),
        Case(twoNumbers, "사과 3 12000", "사과 12000", props = listOf("n1=3")),
        Case(twoNumbers, "사과 5", "사과", props = listOf("n1=5")),
        // 열 정보가 제목뿐
        Case(listOf(title), "오늘 점심 12000", "오늘 점심 12000"),
        Case(emptyList(), "오늘 점심 12000 식비", "오늘 점심 12000 식비"),
        // 글 열(메모)은 낱말을 옮기지 않고, 옵션이 아닌 낱말은 제목에 남는다
        Case(todo, "메모 점심", "메모", props = listOf("m1=점심")),
        Case(todo, "집 집 집", "집 집", props = listOf("m1=집")),
    )

    @Test
    fun theSentenceTableCoversEightyOrMoreSentences() {
        assertTrue("문장 ${cases().size}개", cases().size >= 80)
    }

    @Test
    fun everySentenceIsReadAsExpected() = cases().forEach(::check)

    @Test
    fun aTurnedOffChipLeavesItsWordsInTheTitleAndTheValueEmpty() {
        val input = "내일 점심 12000 식비"
        val noDateChip = ViewQuickInput.parse(input, ledger, today, ignored = setOf("date"))
        assertEquals(listOf("내일 점심", null, listOf("c1=식비", "n1=12000")), listOf(noDateChip.title, noDateChip.date, summary(noDateChip, ledger).sorted()))
        val noNumberChip = ViewQuickInput.parse(input, ledger, today, ignored = setOf("num"))
        assertEquals(listOf("점심 12000", "c1=식비"), listOf(noNumberChip.title) + summary(noNumberChip, ledger))
        val noOptionChip = ViewQuickInput.parse(input, ledger, today, ignored = setOf("opt:c1"))
        assertEquals(listOf("점심 식비", "n1=12000"), listOf(noOptionChip.title) + summary(noOptionChip, ledger))
        val none = ViewQuickInput.parse(input, ledger, today, ignored = setOf("date", "num", "opt:c1"))
        assertEquals("내일 점심 12000 식비", none.title)
        assertTrue(none.chips.isEmpty() && none.props.isEmpty() && none.date == null)
    }

    @Test
    fun chipsAreKeyedSoTheSheetCanTurnThemOff() {
        val keys = ViewQuickInput.parse("내일 점심 12000 식비", ledger, today).chips.map { it.key }
        assertEquals(listOf("date", "num", "opt:c1"), keys)
    }

    @Test
    fun anUnknownIgnoredKeyChangesNothing() {
        assertEquals(ViewQuickInput.parse("점심 12000 식비", ledger, today), ViewQuickInput.parse("점심 12000 식비", ledger, today, ignored = setOf("zzz")))
    }

    @Test
    fun multiSelectValuesAreIdListsAndSingleSelectsAreIds() {
        val result = ViewQuickInput.parse("보고서 완료 업무", todo, today)
        assertEquals("""["m-work"]""", result.props.getValue("m1").toString())
        assertEquals("\"s-done\"", result.props.getValue("s1").toString())
    }
}
