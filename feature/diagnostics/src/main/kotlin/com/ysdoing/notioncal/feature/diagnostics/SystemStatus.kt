package com.ysdoing.notioncal.feature.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import com.ysdoing.notioncal.core.data.Redactor

/** S25-03: 절전 상태. 삼성 '절전·깊은 절전 목록'은 앱이 읽을 수 없어 안내 문구로만 다룬다. */
data class PowerStatus(
    /** 배터리 최적화 예외(제한 없음)인가. */
    val ignoringOptimizations: Boolean,
    /** UsageStatsManager.STANDBY_BUCKET_* */
    val standbyBucket: Int,
) {
    /** 백그라운드 동기화가 크게 늦어질 수 있는 상태. */
    val restricted: Boolean get() = !ignoringOptimizations || standbyBucket >= UsageStatsManager.STANDBY_BUCKET_RARE
}

/** S25-09: 최근 프로세스 종료 1건. [description]은 마스킹을 거친 짧은 글이다. */
data class ExitRecord(
    val at: Long,
    val reason: Int,
    val description: String?,
) {
    /** Android 17 메모리 한도 종료는 설명에 MemoryLimiter가 남는다(PRD §9 S25-09). */
    val memoryLimit: Boolean get() = description?.contains("MemoryLimiter") == true
}

/** 시스템 서비스에서 읽는다. 디스크·바인더 호출이 있으므로 IO 디스패처에서 부른다(PF-03). */
class SystemStatusReader(private val context: Context) {
    fun power(): PowerStatus {
        val pm = context.getSystemService(PowerManager::class.java)
        val usage = context.getSystemService(UsageStatsManager::class.java)
        return PowerStatus(
            ignoringOptimizations = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false,
            standbyBucket = usage?.appStandbyBucket ?: UsageStatsManager.STANDBY_BUCKET_ACTIVE,
        )
    }

    fun recentExits(limit: Int = 5): List<ExitRecord> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return am.getHistoricalProcessExitReasons(context.packageName, 0, limit).map { it.toRecord() }
    }

    fun versionName(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun ApplicationExitInfo.toRecord() = ExitRecord(
        at = timestamp,
        reason = reason,
        description = description?.let { Redactor.scrubReport(it).take(MAX_DESCRIPTION) },
    )

    private companion object {
        const val MAX_DESCRIPTION = 160
    }
}

/** 종료 사유 코드 → 문자열 리소스. */
internal fun exitReasonLabel(reason: Int): Int = when (reason) {
    ApplicationExitInfo.REASON_ANR -> R.string.diag_exit_anr
    ApplicationExitInfo.REASON_CRASH -> R.string.diag_exit_crash
    ApplicationExitInfo.REASON_CRASH_NATIVE -> R.string.diag_exit_crash_native
    ApplicationExitInfo.REASON_LOW_MEMORY -> R.string.diag_exit_low_memory
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> R.string.diag_exit_resource
    ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> R.string.diag_exit_user
    ApplicationExitInfo.REASON_EXIT_SELF -> R.string.diag_exit_self
    ApplicationExitInfo.REASON_SIGNALED -> R.string.diag_exit_signaled
    ApplicationExitInfo.REASON_FREEZER -> R.string.diag_exit_freezer
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> R.string.diag_exit_init
    ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> R.string.diag_exit_package
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> R.string.diag_exit_permission
    else -> R.string.diag_exit_other
}

/** 대기 버킷 → 문자열 리소스. */
internal fun standbyBucketLabel(bucket: Int): Int = when {
    bucket <= UsageStatsManager.STANDBY_BUCKET_ACTIVE -> R.string.diag_bucket_active
    bucket <= UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> R.string.diag_bucket_working
    bucket <= UsageStatsManager.STANDBY_BUCKET_FREQUENT -> R.string.diag_bucket_frequent
    bucket <= UsageStatsManager.STANDBY_BUCKET_RARE -> R.string.diag_bucket_rare
    else -> R.string.diag_bucket_restricted
}
