package com.ysdoing.notioncal.core.notion.http

import com.ysdoing.notioncal.core.notion.FakeTime
import com.ysdoing.notioncal.core.notion.NotionClient
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.createNotionApi
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** PRD §8.5-7, NS-08 재시도·속도 제한. 실제로 기다리지 않고 가짜 시계로 검증한다. */
class RetryAndRateLimitTest {
    private val server = MockWebServer()
    private val time = FakeTime()
    private val retry = RetryInterceptor(sleeper = time, random = Random(0))
    private lateinit var client: NotionClient

    @Before
    fun setUp() {
        server.start()
        client = NotionClient(
            createNotionApi(server.url("/"), { "t" }, retry = retry, bucket = TokenBucket(clock = time, sleeper = time), socketTag = null),
            NotionJson,
        )
    }

    @After
    fun tearDown() = server.close()

    private fun response(code: Int, vararg headers: Pair<String, String>) = MockResponse.Builder().code(code).apply {
        headers.forEach { (k, v) -> addHeader(k, v) }
        body(if (code == 200) """{"id":"u1"}""" else """{"object":"error","status":$code,"code":"x"}""")
    }.build()

    @Test
    fun retryAfterHeaderIsHonoured() = runTest {
        // §12 필수 시나리오: 429(Retry-After: 2) 응답 후 2초 뒤 재시도해 성공한다.
        server.enqueue(response(429, "Retry-After" to "2"))
        server.enqueue(response(200))
        client.me()
        assertEquals(2, server.requestCount)
        assertEquals(listOf(2000L), time.sleeps.filter { it >= 1000 })
    }

    @Test
    fun status529WithoutRetryAfterUsesBackoffWithJitter() = runTest {
        server.enqueue(response(529))
        server.enqueue(response(529))
        server.enqueue(response(200))
        client.me()
        val waits = time.sleeps.filter { it >= 1000 }
        assertEquals(2, waits.size)
        assertTrue(waits[0] in 1000..1250)
        assertTrue(waits[1] in 2000..2250)
    }

    @Test
    fun givesUpAfterSixRetries() = runTest {
        repeat(7) { server.enqueue(response(429, "Retry-After" to "1")) }
        val e = runCatching { client.me() }.exceptionOrNull() as NotionException
        assertEquals(NotionError.RateLimited, e.error)
        assertEquals(7, server.requestCount)
    }

    @Test
    fun backoffIsCappedAt30Seconds() {
        assertTrue(retry.backoffMillis(10) in 30_000..30_250)
    }

    @Test
    fun getIsRetriedOn5xx() = runTest {
        server.enqueue(response(502))
        server.enqueue(response(200))
        client.me()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun validationErrorIsNotRetried() = runTest {
        server.enqueue(response(400))
        runCatching { client.me() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun writePostIsNotRetriedOn5xxButQueryIs() {
        val http = OkHttpClient.Builder().addInterceptor(retry).build()
        val json = "{}".toRequestBody("application/json".toMediaType())

        server.enqueue(response(500))
        http.newCall(Request.Builder().url(server.url("/v1/pages")).post(json).build()).execute().close()
        assertEquals(1, server.requestCount)

        server.enqueue(response(500))
        server.enqueue(response(200))
        http.newCall(Request.Builder().url(server.url("/v1/data_sources/ds/query")).post(json).build()).execute().close()
        assertEquals(3, server.requestCount)
    }

    @Test
    fun tokenBucketAllowsAtMost180PerMinute() {
        val clock = FakeTime()
        val bucket = TokenBucket(clock = clock, sleeper = clock)
        var withinFirstMinute = 0
        repeat(400) {
            bucket.acquire()
            if (clock.nanos < 60_000_000_000L) withinFirstMinute++
        }
        assertTrue("분당 $withinFirstMinute 회", withinFirstMinute <= 180)
        assertTrue("너무 느림: $withinFirstMinute", withinFirstMinute >= 175)
    }
}
