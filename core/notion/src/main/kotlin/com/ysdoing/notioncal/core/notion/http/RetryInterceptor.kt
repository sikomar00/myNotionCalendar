package com.ysdoing.notioncal.core.notion.http

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/**
 * PRD §8.5-7 재시도 규칙.
 * - 429·529: Retry-After(초)만큼 대기. 없으면 min(2^n, 30)초 + 최대 250ms 지터. 최대 [maxRetries]회.
 * - 그 밖의 5xx·연결 오류: 읽기 요청([isReadOnly])만 재시도. 쓰기는 중복 생성 위험이 있어 상위(아웃박스)가 판단한다.
 * - 400 등 4xx: 재시도하지 않는다.
 */
class RetryInterceptor(
    private val sleeper: Sleeper = Sleeper.System,
    private val random: Random = Random.Default,
    private val maxRetries: Int = 6,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (!isReadOnly(request) || attempt >= maxRetries) throw e
                sleeper.sleep(backoffMillis(attempt++))
                continue
            }
            val code = response.code
            val rateLimited = code == 429 || code == 529
            val retryable = rateLimited || (code in 500..599 && isReadOnly(request))
            if (!retryable || attempt >= maxRetries) return response

            val wait = (if (rateLimited) retryAfterMillis(response) else null) ?: backoffMillis(attempt)
            response.close()
            sleeper.sleep(wait)
            attempt++
        }
    }

    private fun retryAfterMillis(response: Response): Long? =
        response.header("Retry-After")?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).toLong() }

    internal fun backoffMillis(attempt: Int): Long =
        min(1L shl attempt, 30L) * 1000 + random.nextLong(0, 251)

    companion object {
        /** GET과 data source 조회(POST .../query)는 서버 상태를 바꾸지 않아 다시 보내도 안전하다. */
        fun isReadOnly(request: Request): Boolean =
            request.method == "GET" || (request.method == "POST" && request.url.encodedPath.endsWith("/query"))
    }
}
