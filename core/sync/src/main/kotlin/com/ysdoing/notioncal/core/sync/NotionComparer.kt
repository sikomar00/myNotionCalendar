package com.ysdoing.notioncal.core.sync

import android.util.Log
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.notion.NotionReader
import com.ysdoing.notioncal.core.notion.mapping.DateCodec
import com.ysdoing.notioncal.core.notion.mapping.NotionPageMapper
import com.ysdoing.notioncal.core.notion.mapping.QueryFilters
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** 불일치 한 건. [fields]는 '내용 다름'일 때 값이 다른 필드 이름, 노션에만·앱에만이면 비어 있다. */
data class Mismatch(val kind: Kind, val date: LocalDate, val title: String, val fields: List<String> = emptyList()) {
    enum class Kind { REMOTE_ONLY, LOCAL_ONLY, DIFFERENT }
}

/** '노션과 비교' 결과. 아웃박스 대기 중인 일정과 날짜 없는 페이지는 세지 않는다. */
data class CompareReport(
    val from: LocalDate,
    val to: LocalDate,
    val remoteCount: Int,
    val localCount: Int,
    val mismatches: List<Mismatch>,
    /** 스키마 문제(제목·날짜 속성 없음)로 비교하지 못한 DB 이름. */
    val skippedSources: List<String> = emptyList(),
) {
    val matches: Boolean get() = mismatches.isEmpty() && skippedSources.isEmpty()

    fun count(kind: Mismatch.Kind) = mismatches.count { it.kind == kind }
}

/**
 * PRD §12 대조: 동기화 범위([NotionSyncEngine.fullRange])를 노션에 조회한 결과와 로컬 DB를 비교한다(디버그 메뉴).
 * 읽기만 한다. 노션에 쓰지 않고 로컬 DB도 고치지 않는다(맞추기는 진단 화면의 '캐시 초기화').
 */
class NotionComparer @Inject constructor(
    private val reader: NotionReader,
    private val db: NotionCalDatabase,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun compare(): CompareReport = withContext(io) {
        val today = clock.instant().atZone(DateCodec.SEOUL).toLocalDate()
        val (from, to) = NotionSyncEngine.fullRange(today)
        val pending = db.outboxDao().pageIds().toHashSet()
        var remoteCount = 0
        var localCount = 0
        val mismatches = mutableListOf<Mismatch>()
        val skipped = mutableListOf<String>()
        for (source in db.dataSourceDao().enabled()) {
            val schema = reader.dataSource(source.dataSourceId)
            val report = SchemaCheck.check(source, schema, SchemaIssue.parseNames(source.mappedPropNames))
            if (report.blocksSync) {
                skipped += source.name
                continue
            }
            // 날짜 없는 DB(🌱 습관)는 달력 범위 대조 대상이 아니다.
            val mapping = source.toMapping(report.droppedRoles) ?: continue
            val mapper = NotionPageMapper(mapping, schema)
            val filter = QueryFilters.dateRange(mapping.datePropId, from.minusDays(NotionSyncEngine.LOOKBACK_DAYS), to)
            val remote = reader.queryAll(source.dataSourceId, filter).pages
                .mapNotNull { mapper.map(it).event?.toEntity(source.dataSourceId) }
                .filter { it.overlaps(from, to) && it.pageId !in pending }
                .associateBy { it.pageId }
            val local = db.eventDao().eventsInRange(source.dataSourceId, from, to)
                .filter { it.pageId !in pending }
                .associateBy { it.pageId }
            remoteCount += remote.size
            localCount += local.size
            for ((id, r) in remote) {
                val l = local[id]
                if (l == null) {
                    mismatches += Mismatch(Mismatch.Kind.REMOTE_ONLY, r.startDate, r.title)
                } else {
                    val fields = differingFields(l, r)
                    if (fields.isNotEmpty()) mismatches += Mismatch(Mismatch.Kind.DIFFERENT, r.startDate, r.title, fields)
                }
            }
            for ((id, l) in local) {
                if (id !in remote) mismatches += Mismatch(Mismatch.Kind.LOCAL_ONLY, l.startDate, l.title)
            }
        }
        // 일정 제목은 로그에 남기지 않는다(SEC-02). 건수만.
        Log.d(TAG, "노션과 비교: 노션 $remoteCount, 앱 $localCount, 불일치 ${mismatches.size}, 건너뜀 ${skipped.size}")
        CompareReport(from, to, remoteCount, localCount, mismatches.sortedBy { it.date }, skipped)
    }

    private fun EventEntity.overlaps(from: LocalDate, to: LocalDate) = startDate <= to && (endDate ?: startDate) >= from

    companion object {
        private const val TAG = "NotionCalSync"

        /** 화면에 보이는 값만 비교한다(url·수정 시각·옵션 ID는 표시와 무관). */
        internal fun differingFields(local: EventEntity, remote: EventEntity): List<String> = buildList {
            if (local.title != remote.title) add("title")
            if (local.startDate != remote.startDate) add("startDate")
            if (local.endDate != remote.endDate) add("endDate")
            if (local.startTime != remote.startTime) add("startTime")
            if (local.endTime != remote.endTime) add("endTime")
            if (local.isAllDay != remote.isAllDay) add("isAllDay")
            if (local.done != remote.done) add("done")
            if (local.colorKey != remote.colorKey) add("colorKey")
            if (local.sortKey != remote.sortKey) add("sortKey")
            if (local.extraValues != remote.extraValues) add("extraValues")
            if (local.titleHasMention != remote.titleHasMention) add("titleHasMention")
        }
    }
}
