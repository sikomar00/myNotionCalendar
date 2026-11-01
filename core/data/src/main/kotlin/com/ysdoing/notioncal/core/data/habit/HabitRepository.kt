package com.ysdoing.notioncal.core.data.habit

import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.DateValue
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.db.HabitEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 습관 하나와 그 상태. */
data class HabitSummary(val habit: HabitEntity, val status: HabitStatus)

/** 습관 화면·위젯이 그리는 것. [doneDates]는 습관별 한 날(이번 달 잔디용). */
data class HabitBoard(
    val today: LocalDate,
    val habits: List<HabitSummary>,
    val doneDates: Map<String, Set<LocalDate>> = emptyMap(),
) {
    /** 진행 중 습관 중 오늘 한 것. */
    val doneToday: Int get() = habits.count { it.status.doneToday }
}

enum class ToggleResult {
    CHECKED,
    UNCHECKED,

    /** 오늘과 지난 6일만 고칠 수 있다(HB-02). */
    OUT_OF_RANGE,

    /** 기록 DB가 없거나 '습관' 연결 속성이 relation이 아니다(NS-09). 연결 없는 기록을 만들지 않는다. */
    BLOCKED,
}

/**
 * HB-02·HB-04 습관 화면·위젯의 데이터 쪽. Room만 읽고, 쓰기는 [EventWriter](아웃박스)로만 한다.
 * 체크 = ✅ 습관 기록 생성(제목 '명상 2026-10-19', 날짜, 습관 relation), 해제 = 그 습관·날짜 기록을 모두 휴지통으로.
 */
@Singleton
class HabitRepository @Inject constructor(
    private val db: NotionCalDatabase,
    private val writer: EventWriter,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    /** 검증 메모 7: 읽기·판단·쓰기를 묶는다. 위젯을 빠르게 두 번 눌러도 둘 다 '기록 없음'으로 보고 두 번 만들지 않는다. */
    private val mutex = Mutex()

    fun today(): LocalDate = clock.instant().atZone(APP_ZONE).toLocalDate()

    /** [today] 기준 보드. 연속·연속 달성 주를 세려고 기록은 [LOOKBACK_DAYS]일 전부터 읽는다. */
    fun observeBoard(today: LocalDate): Flow<HabitBoard> = combine(
        db.habitDao().observeActive(),
        db.eventDao().observeHabitLogs(today.minusDays(LOOKBACK_DAYS), today),
        settings.settings.map { it.weekStart }.distinctUntilChanged(),
    ) { habits, logs, weekStart ->
        val byHabit = logs.groupBy({ it.habitId }, { it.date }).mapValues { (_, dates) -> dates.toSet() }
        HabitBoard(
            today = today,
            habits = habits.map { habit ->
                HabitSummary(habit, HabitStats.status(byHabit[habit.pageId].orEmpty(), today, weekStart, habit.weeklyTarget, habit.startDate))
            },
            doneDates = byHabit,
        )
    }

    /** HB-02: [date]의 체크를 뒤집는다. */
    suspend fun toggle(habitId: String, date: LocalDate): ToggleResult = mutex.withLock {
        val today = today()
        if (date.isAfter(today) || date.isBefore(today.minusDays(EDITABLE_DAYS))) return ToggleResult.OUT_OF_RANGE
        val existing = db.eventDao().habitLogIds(habitId, date)
        if (existing.isNotEmpty()) {
            // 노션에서 중복으로 생긴 기록까지 모두 지운다. 아직 보내지 않은 생성이면 생성 작업까지 없어진다.
            existing.forEach { writer.trash(it) }
            return ToggleResult.UNCHECKED
        }
        val habit = db.habitDao().get(habitId) ?: return ToggleResult.BLOCKED
        val logSource = db.dataSourceDao().linkedTo(habit.dataSourceId).firstOrNull { it.enabled } ?: return ToggleResult.BLOCKED
        val linkBroken = SchemaIssue.parse(logSource.schemaIssues).any { it.role == SchemaIssue.LINK && it.kind != SchemaIssue.RENAMED }
        if (logSource.linkPropId == null || linkBroken) return ToggleResult.BLOCKED
        writer.create(logSource.dataSourceId, "${habit.name} $date", DateValue.allDay(date), link = habit.pageId)
        ToggleResult.CHECKED
    }

    companion object {
        /** 오늘 말고 고칠 수 있는 지난 날 수(HB-02: 오늘과 지난 6일). */
        const val EDITABLE_DAYS = 6L
        const val LOOKBACK_DAYS = 400L
    }
}
