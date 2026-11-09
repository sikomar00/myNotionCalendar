package com.ysdoing.notioncal.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ysdoing.notioncal.core.data.SYNC_LOG_OUTBOX
import com.ysdoing.notioncal.core.data.SYNC_LOG_VIEWS
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncScheduleInfo
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.db.SyncLogDao
import com.ysdoing.notioncal.core.data.db.SyncLogEntity
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.http.RequestCounter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: NotionSyncEngine,
    private val outbox: OutboxProcessor,
    private val viewWrites: ViewWriteProcessor,
    private val mirror: PhoneCalendarMirror,
    private val views: ViewSync,
    private val syncLog: SyncLogDao,
    private val clock: Clock,
    private val requests: RequestCounter,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: SyncTrigger.APP_OPEN
        // 보내지 않은 쓰기를 먼저 보낸 뒤 읽는다. 실패해도 읽기는 진행한다(작업은 남아 다음에 다시 보낸다).
        continueOnFailure(syncLog, clock) { outbox.process() }
        // M3: 위젯에서 바꾼 보기 행도 읽기 전에 보낸다(읽기가 노션의 이전 값으로 덮지 않게).
        val writeOutcome = continueOnFailure(syncLog, clock) { viewWrites.process() }
        val outcome = engine.sync(trigger, inputData.getString(KEY_DATA_SOURCE))
        // EX-08 시험: 폰 캘린더 미러가 켜져 있으면(디버그 메뉴) 새 일정으로 다시 맞춘다.
        mirror.refreshIfEnabled()
        // VW-01: 등록한 노션 보기 스냅샷도 같은 주기에 맞춘다(실패해도 위 결과에 영향 없음).
        val viewsStartedAt = clock.millis()
        val viewsBefore = requests.total
        views.syncAll()
        // PF-11: 보기 주기의 요청 수를 남긴다(보기 수 × 쪽 수만큼 늘어난다). 실패는 보기마다 view_snapshots에 남아 여기서는 OK로 적는다.
        syncLog.record(
            SyncLogEntity(
                at = clock.millis(), trigger = SYNC_LOG_VIEWS, result = "OK", durationMs = clock.millis() - viewsStartedAt,
                pulled = 0, pushed = 0, error = null, requests = (requests.total - viewsBefore).toInt(),
            ),
        )
        // 연결·서버 문제는 WorkManager 백오프로 다시 시도한다. 토큰·공유 문제는 사용자가 고쳐야 하므로 끝낸다.
        return if (outcome.error.isRetryable() || writeOutcome?.retryError.isRetryable()) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        const val KEY_DATA_SOURCE = "dataSourceId"
    }
}

private fun NotionError?.isRetryable() = this == NotionError.Network || this == NotionError.RateLimited || this is NotionError.Server

/**
 * 쓰기 처리 하나가 예상하지 못한 예외로 끝나도 다음 단계(읽기 동기화·다른 큐)가 돌게 한다. 실패한 작업은 큐에 남아 다음 주기에 다시 보낸다.
 * 삼키지 않고 sync_log에 오류로 남겨 진단 화면에서 보이게 한다. 취소는 그대로 던진다(작업 중지를 삼키지 않는다).
 */
internal suspend fun <T> continueOnFailure(syncLog: SyncLogDao, clock: Clock, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // 예외 종류만 남긴다: SQLite·직렬화 메시지에 일정 제목 같은 JSON 조각이 섞여 릴리스 로그로 나갈 수 있다.
    Log.w("SyncWorker", "쓰기 처리 실패(${e.javaClass.name}), 다음 단계는 계속한다")
    syncLog.record(
        SyncLogEntity(
            at = clock.millis(), trigger = SYNC_LOG_OUTBOX, result = "ERROR", durationMs = 0, pulled = 0, pushed = 0,
            error = LOCAL_WRITE_ERROR, requests = 0,
        ),
    )
    null
}

/** sync_log에 남기는 '앱 안에서 쓰기 처리가 실패함'(노션 오류가 아님). */
internal const val LOCAL_WRITE_ERROR = "LocalWriteError"

/** NS-04: 아웃박스를 비운다. 네트워크가 없으면 CONNECTED 조건으로 기다렸다가 연결되면 실행된다(NS-06 네트워크 복구). */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: OutboxProcessor,
    private val viewWrites: ViewWriteProcessor,
    private val views: ViewSync,
    private val syncLog: SyncLogDao,
    private val clock: Clock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // 일정 큐가 예외로 끝나도 위젯의 보기 쓰기는 보낸다(SyncWorker와 같은 보호).
        val outcome = continueOnFailure(syncLog, clock) { processor.process() }
        val viewOutcome = continueOnFailure(syncLog, clock) { viewWrites.process() }
        // SY-06: 보낸 일정이 든 보기(와 새로 만든 페이지가 들어갈 보기)를 바로 다시 받는다. 위젯에서 바꾼 보기 행을 먼저 보낸 뒤라
        // 그 보기도 받는다. 연결이 안 되면 다음 동기화가 한다.
        if (outcome != null && outcome.retryError == null) views.syncContaining(outcome.sentPageIds, outcome.createdInSources)
        val errors = listOfNotNull(outcome?.retryError, viewOutcome?.retryError)
        // 토큰·공유 문제는 사용자가 고칠 때까지 기다린다(작업은 남아 있고 다음 요청·주기 실행 때 다시 시도).
        // 둘 중 하나라도 일시 오류면 다시 시도한다(한쪽 오류가 다른 쪽 재시도를 가리지 않게).
        // 쓰기 처리가 예외로 끝났으면(위에서 기록함) 남은 작업을 백오프 뒤 다시 보낸다.
        if (outcome == null || viewOutcome == null) return Result.retry()
        return if (errors.all { it == NotionError.Unauthorized || it == NotionError.NotShared }) Result.success() else Result.retry()
    }
}

/** 화면에서 동기화를 요청하는 유일한 통로. 화면은 노션 API를 직접 부르지 않는다. */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : SyncRequester, SyncScheduleInfo {
    private val workManager get() = WorkManager.getInstance(context)

    /** NS-06 중 Phase 1 트리거(앱 열기, 당겨서 새로고침). 이미 도는 중이면 새로 쌓지 않는다. */
    override fun request(trigger: SyncTrigger, dataSourceId: String?) {
        // 지금 오프라인이면 연결될 때 실행되므로 '네트워크 복구'로 기록한다(NS-06).
        val recorded = if (isOnline()) trigger else SyncTrigger.NETWORK_RESTORED
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to recorded.name, SyncWorker.KEY_DATA_SOURCE to dataSourceId))
            .addTag(TAG)
            .build()
        val name = dataSourceId?.let { "$UNIQUE_NAME-$it" } ?: UNIQUE_NAME
        val policy = if (trigger == SyncTrigger.PULL_TO_REFRESH) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(name, policy, request)
    }

    /**
     * NS-06 주기 동기화(15·30·60분). WorkManager 최소 간격이 15분이다. 같은 이름으로 다시 부르면 간격만 바꾼다.
     * 삼성 절전 상태에 따라 실제 실행은 늦어질 수 있다(PRD §13).
     */
    fun schedulePeriodic(minutes: Int) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to SyncTrigger.PERIODIC.name))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * 아웃박스 전송 요청(EventWriter가 쓰기 직후 부른다). 실행 중이면 뒤에 이어 붙여 새로 쌓인 작업도 보낸다.
     * [delayMs]가 있으면(Phase 11c 지연 쓰기의 전송 시각) 별도 이름의 작업을 그만큼 미뤄 건다. 같은 이름이라 연타하면 마지막 시각 하나만 남고,
     * 즉시 요청이 지연 뒤에 줄 서지 않는다. 지연이 늦어져도 쓰기는 큐에 남아 다음 실행(앱 열기·주기 동기화) 때 나간다.
     */
    fun requestOutbox(delayMs: Long = 0) {
        val builder = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG)
        if (delayMs > 0) {
            workManager.enqueueUniqueWork(OUTBOX_DELAYED_NAME, ExistingWorkPolicy.REPLACE, builder.setInitialDelay(delayMs + DELAY_SLACK_MS, TimeUnit.MILLISECONDS).build())
        } else {
            workManager.enqueueUniqueWork(OUTBOX_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
        }
    }

    /** S25-04: 예약된 주기 작업의 다음 실행 시각. 삼성 절전 상태에 따라 실제 실행은 늦어질 수 있다. */
    override val nextPeriodicAt: Flow<Long?> = workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_NAME)
        .map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE }
        }
        .distinctUntilChanged()

    override val isSyncing: Flow<Boolean> = workManager.getWorkInfosByTagFlow(TAG)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .distinctUntilChanged()

    private companion object {
        const val UNIQUE_NAME = "notion-sync"
        const val OUTBOX_NAME = "notion-outbox"
        const val OUTBOX_DELAYED_NAME = "notion-outbox-delayed"

        /** 지연 쓰기의 전송 시각에 딱 맞추면 시계 오차로 시각보다 조금 일찍 돌아 그 쓰기를 건너뛴다. 그만큼 늦춰 건다. */
        const val DELAY_SLACK_MS = 500L
        const val PERIODIC_NAME = "notion-sync-periodic"
        const val TAG = "notion-sync"
    }
}
