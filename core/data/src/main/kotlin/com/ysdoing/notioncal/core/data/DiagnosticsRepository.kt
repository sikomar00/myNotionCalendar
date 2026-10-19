package com.ysdoing.notioncal.core.data

import androidx.room.withTransaction
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.SyncLogEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** S25-04 동기화 진단 화면에 보이는 값. */
data class SyncDiagnostics(
    val lastSuccess: SyncLogEntity?,
    val lastFailure: SyncLogEntity?,
    /** 다음 주기 동기화 예정 시각(epoch ms). */
    val nextPeriodicAt: Long?,
    val pendingWrites: Int,
    val unseenConflicts: Int,
    /** EX-02 공휴일 출처("GOOGLE"·"SAMSUNG", 없으면 null). */
    val holidaySource: String? = null,
)

/** 로그 내보내기에 덧붙일 화면 쪽 정보(절전 상태, 종료 사유 등). 줄마다 마스킹을 다시 거친다. */
data class ReportSection(val title: String, val lines: List<String>)

/**
 * 진단 화면(S25-04)·로그 내보내기(PF-04)·캐시 초기화(PF-10)의 데이터 쪽.
 * 내보내기 글은 SEC-02 규칙대로 제목을 가리고 토큰 형식을 지운 뒤에만 밖으로 낸다.
 */
@Singleton
class DiagnosticsRepository @Inject constructor(
    private val db: NotionCalDatabase,
    private val scheduleInfo: SyncScheduleInfo,
    private val syncRequester: SyncRequester,
    private val widgetRefresher: WidgetRefresher,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val sync: Flow<SyncDiagnostics> = combine(
        db.syncLogDao().observeLatestSuccess(),
        db.syncLogDao().observeLatestFailure(),
        scheduleInfo.nextPeriodicAt,
        db.outboxDao().observeCount(),
        db.conflictDao().observeUnseen().map { it.size },
    ) { success, failure, next, pending, conflicts ->
        SyncDiagnostics(success, failure, next, pending, conflicts)
    }.combine(db.holidayDao().observeSource()) { diagnostics, holidays -> diagnostics.copy(holidaySource = holidays) }

    /**
     * 노션에서 다시 받을 수 있는 것(일정·습관 기록 캐시, 🌱 습관 캐시, 동기화 기록, 충돌 기록)만 지우고 전체 재동기화를 요청한다.
     * 토큰·설정(DataStore), DB 등록, 위젯 설정, 아직 보내지 않은 쓰기와 그 일정은 남긴다.
     */
    suspend fun clearCache() {
        withContext(io) {
            db.withTransaction {
                db.eventDao().deleteSynced()
                db.habitDao().deleteAll()
                db.syncLogDao().deleteAll()
                db.conflictDao().deleteAll()
                db.dataSourceDao().resetSyncCursors()
            }
        }
        widgetRefresher.requestUpdate()
        syncRequester.request(SyncTrigger.CACHE_RESET)
    }

    /** 공유용 진단 글. 앱 정보·화면 정보는 [header]·[sections]로 받는다. */
    suspend fun report(header: List<String>, sections: List<ReportSection>): String = withContext(io) {
        val logs = db.syncLogDao().recent(REPORT_LOG_LIMIT)
        val outbox = db.outboxDao().all()
        val conflicts = db.conflictDao().all().take(REPORT_CONFLICT_LIMIT)
        buildString {
            appendLine("# NotionCal 진단 기록")
            header.forEach { appendLine(it) }
            appendLine("작성: ${time(System.currentTimeMillis())}")

            section("동기화 기록 (최근 ${logs.size}건)")
            logs.forEach { l ->
                appendLine("${time(l.at)} ${l.trigger} ${l.result} ${l.durationMs}ms 받음 ${l.pulled} 보냄 ${l.pushed}${l.error?.let { " 오류 $it" } ?: ""}")
            }

            section("전송 대기 (${outbox.size}건)")
            outbox.groupBy { it.op }.forEach { (op, rows) -> appendLine("$op ${rows.size}건") }
            outbox.filter { it.lastError != null }.forEach { r ->
                appendLine("#${r.id} ${r.op} 시도 ${r.attempts}회 마지막 오류 ${r.lastError} 생성 ${time(r.createdAt)}")
            }

            section("충돌·반영 실패 (최근 ${conflicts.size}건)")
            conflicts.forEach { c ->
                val local = c.localValue?.let { if (c.field == "TITLE") Redactor.maskTitle(it) else it }
                val remote = c.remoteValue?.let { if (c.field == "TITLE") Redactor.maskTitle(it) else it }
                appendLine("${time(c.at)} ${c.kind} ${Redactor.maskTitle(c.title)} ${c.field} 내 값 $local → 노션 $remote${if (c.seen) "" else " (미확인)"}")
            }

            sections.forEach { s ->
                section(s.title)
                s.lines.forEach { appendLine(it) }
            }
        }.let(Redactor::scrubTokens)
    }

    private fun StringBuilder.section(title: String) {
        appendLine()
        appendLine("## $title")
    }

    private fun time(epochMs: Long): String = FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(APP_ZONE))

    private companion object {
        const val REPORT_LOG_LIMIT = 50
        const val REPORT_CONFLICT_LIMIT = 20
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
