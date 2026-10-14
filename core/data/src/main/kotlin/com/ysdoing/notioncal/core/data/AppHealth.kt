package com.ysdoing.notioncal.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 앱이 정상 화면을 그릴 수 없는 상태(PF-10). 진단 화면이 이 값을 보여준다. 문구에는 오류 종류만 담는다(SEC-02). */
data class AppFailure(
    /** 예외 클래스 이름 또는 [REPEATED_STARTUP_FAILURE]. */
    val kind: String,
    /** 앞선 실행이 연달아 첫 화면 전에 끝났다(안전 모드). */
    val safeMode: Boolean = false,
) {
    companion object {
        const val REPEATED_STARTUP_FAILURE = "RepeatedStartupFailure"
    }
}

/**
 * PF-10: 초기화 실패를 모아 진단 화면으로 보낸다.
 *
 * - 실행 표지: 앱 화면을 시작할 때 `startup_pending`에 연속 미완료 횟수를 적고, 첫 프레임 뒤에 지운다.
 *   2번 연속으로 남아 있으면 이번 실행은 월 화면 대신 진단 화면(안전 모드)으로 시작한다.
 * - DB를 열지 못하는 오류는 [report]로 받는다.
 * - 진단 도구: 다음 실행 때 DB를 새로 만들기([requestDatabaseReset]), 디버그 고장 주입([armStartupFault]).
 *
 * 파일은 모두 noBackupFilesDir에 두고 IO 스레드에서만 읽고 쓴다(PF-03).
 */
@Singleton
class AppHealth @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val _failure = MutableStateFlow<AppFailure?>(null)
    val failure: StateFlow<AppFailure?> = _failure.asStateFlow()

    private val dir: File get() = context.noBackupFilesDir
    private val pending get() = File(dir, "startup_pending")
    private val resetRequest get() = File(dir, "reset_database")
    private val fault get() = File(dir, "debug_fail_startup")

    /**
     * 화면 실행 시작(프로세스 복원이 아닌 새 실행)마다 부른다. 안전 모드로 시작해야 하면 true.
     * 위젯·작업만으로 뜬 프로세스는 부르지 않으므로 횟수에 들어가지 않는다.
     */
    suspend fun onLaunchStarted(): Boolean = withContext(io) {
        val unfinished = pending.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        pending.writeText((unfinished + 1).toString())
        val safe = unfinished >= SAFE_MODE_AFTER
        if (safe) _failure.value = AppFailure(AppFailure.REPEATED_STARTUP_FAILURE, safeMode = true)
        safe
    }

    /** 첫 화면을 그렸다. */
    suspend fun onFirstFrame() {
        withContext(io) { pending.delete() }
    }

    fun report(error: Throwable) {
        if (_failure.value == null) _failure.value = AppFailure(error.javaClass.simpleName)
    }

    /** 진단 화면에서 확인을 누르면 지운다. 실행 표지도 함께 지워 다음 실행은 정상으로 시작한다. */
    suspend fun clear() {
        _failure.value = null
        onFirstFrame()
    }

    /** 캐시 초기화를 DB 자체가 열리지 않아 할 수 없을 때: 다음 실행에서 DB를 새로 만든다(남길 것은 살린다). */
    suspend fun requestDatabaseReset() {
        withContext(io) { resetRequest.writeText("1") }
    }

    /** DB를 열 때(IO 스레드) 부른다. 요청이 있었으면 지우고 true. 프로세스당 한 번만 파일을 본다. */
    fun consumeDatabaseReset(): Boolean = resetRequested.getAndSet(false)

    private val resetRequested: AtomicBoolean by lazy { AtomicBoolean(resetRequest.exists() && resetRequest.delete()) }

    /** 디버그 메뉴: 다음 실행의 DB 열기에서 예외를 던진다(1회). */
    suspend fun armStartupFault() {
        withContext(io) { fault.writeText("1") }
    }

    /** DB를 열 때(IO 스레드) 부른다. 주입이 걸려 있으면 이 프로세스에서 1회만 던진다. */
    fun checkFault() {
        if (faultArmed.getAndSet(false)) throw IllegalStateException(INJECTED_FAULT)
    }

    private val faultArmed: AtomicBoolean by lazy { AtomicBoolean(fault.exists() && fault.delete()) }

    companion object {
        const val SAFE_MODE_AFTER = 2
        const val INJECTED_FAULT = "디버그: 주입한 초기화 실패"
    }
}
