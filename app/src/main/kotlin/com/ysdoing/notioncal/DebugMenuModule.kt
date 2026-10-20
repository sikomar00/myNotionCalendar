package com.ysdoing.notioncal

import android.os.Handler
import android.os.Looper
import com.ysdoing.notioncal.core.data.AppHealth
import com.ysdoing.notioncal.core.data.DebugFlags
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.sync.CompareReport
import com.ysdoing.notioncal.core.sync.Mismatch
import com.ysdoing.notioncal.core.sync.NotionComparer
import com.ysdoing.notioncal.core.sync.NotionSyncEngine
import com.ysdoing.notioncal.core.sync.PhoneCalendarMirror
import com.ysdoing.notioncal.feature.diagnostics.DebugAction
import com.ysdoing.notioncal.feature.diagnostics.DebugMenu
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * 진단 화면의 디버그 도구(게이트 4: PF-04 테스트 크래시, PF-05 인증 실패, PF-10 초기화 실패).
 * debug 빌드에서만 항목을 준다. release·benchmark 빌드는 빈 목록이고 R8이 아래 코드를 지운다.
 */
@Module
@InstallIn(SingletonComponent::class)
object DebugMenuModule {
    @Provides
    fun provideDebugMenu(
        flags: DebugFlags,
        health: AppHealth,
        sync: SyncRequester,
        engine: NotionSyncEngine,
        comparer: NotionComparer,
        mirror: PhoneCalendarMirror,
    ): DebugMenu = DebugMenu {
        if (!BuildConfig.DEBUG) return@DebugMenu emptyList()
        listOf(
            DebugAction("노션과 비교") {
                // §12 대조: 먼저 평소처럼 동기화(증분 + 이번 달 대조)한 뒤, 동기화 범위 전체를 노션과 비교한다.
                val outcome = engine.sync(SyncTrigger.PULL_TO_REFRESH)
                try {
                    describeComparison(comparer.compare(), outcome.error)
                } catch (e: NotionException) {
                    "비교하지 못했습니다: ${e.error::class.simpleName}"
                }
            },
            // EX-08 시험: 노션 일정을 폰 캘린더(로컬 계정 '노션 캘린더')에 복사해 S25 나우 브리프에 뜨는지 본다.
            DebugAction("폰 캘린더 미러 켜기(시험)") {
                mirror.enable()?.let { "노션 캘린더에 앞으로 30일 일정 ${it}건을 복사했습니다. 삼성 캘린더와 나우 브리프를 확인하세요." }
                    ?: "캘린더 읽기·쓰기 권한이 필요합니다(설정 → 애플리케이션 → 노션 월간 → 권한 → 캘린더)."
            },
            DebugAction("폰 캘린더 미러 끄기(삭제)") {
                "노션 캘린더 ${mirror.disable()}개를 지웠습니다."
            },
            DebugAction("테스트 크래시") {
                // 화면 코드 바깥(메인 루프)에서 던져 실제 크래시처럼 수집되게 한다.
                Handler(Looper.getMainLooper()).post { throw TestCrashException() }
                null
            },
            DebugAction("인증 실패 흉내 켜기·끄기") {
                flags.setFakeAuthFailure(!flags.fakeAuthFailure.first())
                sync.request(SyncTrigger.PULL_TO_REFRESH)
                null
            },
            DebugAction("다음 실행 시 초기화 예외") {
                health.armStartupFault()
                null
            },
        )
    }
}

/** 결과 대화상자 문구. 불일치는 앞 [SHOWN]건만 날짜·제목·다른 필드로 보인다. */
internal fun describeComparison(report: CompareReport, syncError: NotionError?): String = buildString {
    appendLine("범위 ${report.from} ~ ${report.to}")
    appendLine("노션 ${report.remoteCount}건 · 앱 ${report.localCount}건")
    if (syncError != null) appendLine("먼저 한 동기화가 실패했습니다(${syncError::class.simpleName}).")
    if (report.skippedSources.isNotEmpty()) appendLine("비교 못 한 DB: ${report.skippedSources.joinToString()}")
    appendLine()
    if (report.matches) {
        append("불일치 0건 ✓")
        return@buildString
    }
    appendLine(
        "불일치 ${report.mismatches.size}건: 노션에만 ${report.count(Mismatch.Kind.REMOTE_ONLY)}, " +
            "앱에만 ${report.count(Mismatch.Kind.LOCAL_ONLY)}, 내용 다름 ${report.count(Mismatch.Kind.DIFFERENT)}",
    )
    report.mismatches.take(SHOWN).forEach { m ->
        val kind = when (m.kind) {
            Mismatch.Kind.REMOTE_ONLY -> "노션에만"
            Mismatch.Kind.LOCAL_ONLY -> "앱에만"
            Mismatch.Kind.DIFFERENT -> "다름(${m.fields.joinToString()})"
        }
        appendLine()
        append("${m.date} ${m.title} — $kind")
    }
    if (report.mismatches.size > SHOWN) {
        appendLine()
        append("… 외 ${report.mismatches.size - SHOWN}건")
    }
}

private const val SHOWN = 5

/** 수집 대시보드에서 구분하기 쉽게 따로 둔 예외. */
class TestCrashException : RuntimeException("NotionCal 테스트 크래시(디버그 메뉴)")
