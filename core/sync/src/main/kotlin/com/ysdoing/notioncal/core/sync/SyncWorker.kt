package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ysdoing.notioncal.core.data.SyncRequester
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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: SyncTrigger.APP_OPEN
        val outcome = engine.sync(trigger, inputData.getString(KEY_DATA_SOURCE))
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

/** 화면에서 동기화를 요청하는 유일한 통로. 화면은 노션 API를 직접 부르지 않는다. */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : SyncRequester {
    private val workManager get() = WorkManager.getInstance(context)

    /** NS-06 중 Phase 1 트리거(앱 열기, 당겨서 새로고침). 이미 도는 중이면 새로 쌓지 않는다. */
    override fun request(trigger: SyncTrigger, dataSourceId: String?) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to trigger.name, SyncWorker.KEY_DATA_SOURCE to dataSourceId))
            .addTag(TAG)
            .build()
        val name = dataSourceId?.let { "$UNIQUE_NAME-$it" } ?: UNIQUE_NAME
        val policy = if (trigger == SyncTrigger.PULL_TO_REFRESH) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(name, policy, request)
    }

    override val isSyncing: Flow<Boolean> = workManager.getWorkInfosByTagFlow(TAG)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .distinctUntilChanged()

    private companion object {
        const val UNIQUE_NAME = "notion-sync"
        const val TAG = "notion-sync"
    }
}
