package com.ysdoing.notioncal.core.notion.http

import android.net.TrafficStats
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp는 애플리케이션 인터셉터와 같은 스레드에서 소켓을 연다. 그 동안 스레드 태그를 걸어 두면
 * 새 소켓에 태그가 붙어 StrictMode의 UntaggedSocketViolation이 나지 않는다(PF-03).
 */
class SocketTagInterceptor(private val tag: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val previous = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(tag)
        return try {
            chain.proceed(chain.request())
        } finally {
            TrafficStats.setThreadStatsTag(previous)
        }
    }
}
