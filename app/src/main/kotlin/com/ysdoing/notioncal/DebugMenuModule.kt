package com.ysdoing.notioncal

import android.os.Handler
import android.os.Looper
import com.ysdoing.notioncal.core.data.AppHealth
import com.ysdoing.notioncal.core.data.DebugFlags
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncTrigger
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
    fun provideDebugMenu(flags: DebugFlags, health: AppHealth, sync: SyncRequester): DebugMenu = DebugMenu {
        if (!BuildConfig.DEBUG) return@DebugMenu emptyList()
        listOf(
            DebugAction("테스트 크래시") {
                // 화면 코드 바깥(메인 루프)에서 던져 실제 크래시처럼 수집되게 한다.
                Handler(Looper.getMainLooper()).post { throw TestCrashException() }
            },
            DebugAction("인증 실패 흉내 켜기·끄기") {
                flags.setFakeAuthFailure(!flags.fakeAuthFailure.first())
                sync.request(SyncTrigger.PULL_TO_REFRESH)
            },
            DebugAction("다음 실행 시 초기화 예외") { health.armStartupFault() },
        )
    }
}

/** 수집 대시보드에서 구분하기 쉽게 따로 둔 예외. */
class TestCrashException : RuntimeException("NotionCal 테스트 크래시(디버그 메뉴)")
