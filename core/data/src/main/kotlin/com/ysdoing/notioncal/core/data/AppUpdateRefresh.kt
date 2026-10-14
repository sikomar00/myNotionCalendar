package com.ysdoing.notioncal.core.data

import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 앱이 설치·업데이트되면 다음 동기화가 모든 DB의 전체 범위를 다시 받게 한다.
 * 업데이트로 노션 페이지 해석이 바뀌어도(예: DB v7 언급 제목) 노션 쪽 페이지는 수정 시각이 그대로라 증분 조회에 안 잡히고,
 * 이번 달 밖 일정은 하루 1회 전체 대조까지 옛 값으로 남는다(§12 대조 도구로 발견).
 */
class AppUpdateRefresh @Inject constructor(
    private val db: NotionCalDatabase,
    private val settings: SettingsRepository,
) {
    /** [installedAt]: 지금 설치본의 PackageInfo.lastUpdateTime. 처음 보는 값이면 true(전체 동기화 예약). */
    suspend fun onStart(installedAt: Long): Boolean {
        if (settings.settings.first().seenAppUpdateAt == installedAt) return false
        db.dataSourceDao().clearLastFullSync()
        settings.setSeenAppUpdateAt(installedAt)
        return true
    }
}
