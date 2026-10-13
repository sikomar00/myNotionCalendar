package com.ysdoing.notioncal.core.notion.http

import okhttp3.Interceptor
import okhttp3.Response
import kotlin.math.ceil
import kotlin.math.min

/**
 * NS-08 토큰 버킷. 초당 3회로 채운다. 기본 [capacity] 1이면 요청이 0.33초 간격으로 고르게 나가
 * 어떤 60초 구간에서도 180회를 넘지 않는다(버스트를 허용하면 그만큼 초과한다).
 */
class TokenBucket(
    private val capacity: Int = 1,
    private val refillPerSecond: Double = 3.0,
    private val clock: NanoClock = NanoClock.System,
    private val sleeper: Sleeper = Sleeper.System,
) {
    private var tokens = capacity.toDouble()
    private var lastNanos = clock.nanoTime()

    /** 토큰이 생길 때까지 기다린다. 여러 스레드가 부르면 순서대로 통과한다. */
    @Synchronized
    fun acquire() {
        while (true) {
            val now = clock.nanoTime()
            tokens = min(capacity.toDouble(), tokens + (now - lastNanos) / 1e9 * refillPerSecond)
            lastNanos = now
            if (tokens >= 1.0) {
                tokens -= 1.0
                return
            }
            sleeper.sleep(ceil((1.0 - tokens) / refillPerSecond * 1000).toLong())
        }
    }
}

class RateLimitInterceptor(private val bucket: TokenBucket) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        bucket.acquire()
        return chain.proceed(chain.request())
    }
}
