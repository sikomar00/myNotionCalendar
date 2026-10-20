package com.ysdoing.notioncal.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncScheduleInfo
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.notion.NotionError
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: NotionSyncEngine,
    private val outbox: OutboxProcessor,
    private val mirror: PhoneCalendarMirror,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: SyncTrigger.APP_OPEN
        // 보내지 않은 쓰기를 먼저 보낸 뒤 읽는다. 실패해도 읽기는 진행한다(작업은 남아 다음에 다시 보낸다).
        outbox.process()
        val outcome = engine.sync(trigger, inputData.getString(KEY_DATA_SOURCE))
        // EX-08 시험: 폰 캘린더 미러가 켜져 있으면(디버그 메뉴) 새 일정으로 다시 맞춘다.
        mirror.refreshIfEnabled()
        // 연결·서버 문제는 WorkManager 백오프로 다시 시도한다. 토큰·공유 문제는 사용자가 고쳐야 하므로 끝낸다.
        return when (outcome.error) {
            NotionError.Network, NotionError.RateLimited, is NotionError.Server -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        const val KEY_DATA_SOURCE = "dataSourceId"
    }
}

/** NS-04: 아웃박스를 비운다. 네트워크가 없으면 CONNECTED 조건으로 기다렸다가 연결되면 실행된다(NS-06 네트워크 복구). */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: OutboxProcessor,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = processor.process()
        return when (outcome.retryError) {
            null -> Result.success()
            // 토큰·공유 문제는 사용자가 고칠 때까지 기다린다(작업은 남아 있고 다음 요청·주기 실행 때 다시 시도).
            NotionError.Unauthorized, NotionError.NotShared -> Result.success()
            else -> Result.retry()
        }
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

    /** 아웃박스 전송 요청(EventWriter가 쓰기 직후 부른다). 실행 중이면 뒤에 이어 붙여 새로 쌓인 작업도 보낸다. */
    fun requestOutbox() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(OUTBOX_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
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
        const val PERIODIC_NAME = "notion-sync-periodic"
        const val TAG = "notion-sync"
    }
}
