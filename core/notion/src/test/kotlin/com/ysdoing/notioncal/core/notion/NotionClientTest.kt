package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.http.RetryInterceptor
import com.ysdoing.notioncal.core.notion.http.TokenBucket
import com.ysdoing.notioncal.core.notion.mapping.QueryFilters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

/** PRD §12 API 계약 테스트: 요청 모양·헤더·페이지네이션·오류 분류. */
class NotionClientTest {
    private val server = MockWebServer()
    private val time = FakeTime()
    private var token: String? = "test-token-not-real"
    private lateinit var client: NotionClient

    @Before
    fun setUp() {
        server.start()
        val api = createNotionApi(
            baseUrl = server.url("/"),
            tokenProvider = { token },
            retry = RetryInterceptor(sleeper = time, random = Random(0)),
            bucket = TokenBucket(clock = time, sleeper = time),
            socketTag = null,
        )
        client = NotionClient(api, NotionJson)
    }

    @After
    fun tearDown() = server.close()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun error(code: Int, notionCode: String) =
        MockResponse.Builder().code(code).body("""{"object":"error","status":$code,"code":"$notionCode","message":"x"}""").build()

    @Test
    fun sendsRequiredHeaders() = runTest {
        server.enqueue(ok("""{"object":"user","id":"u1","name":"bot","type":"bot"}"""))
        client.me()
        val request = server.takeRequest()
        assertEquals("/v1/users/me", request.url.encodedPath)
        assertEquals("Bearer test-token-not-real", request.headers["Authorization"])
        assertEquals("2026-03-11", request.headers["Notion-Version"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
    }

    @Test
    fun missingTokenOmitsAuthorizationAndMapsTo401() = runTest {
        token = null
        server.enqueue(error(401, "unauthorized"))
        val e = runCatching { client.me() }.exceptionOrNull() as NotionException
        assertEquals(NotionError.Unauthorized, e.error)
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun parsesDatabaseDataSources() = runTest {
        server.enqueue(ok(fixture("database_calendar.json")))
        val db = client.database("11111111-2222-4333-8444-555555555555")
        assertEquals("/v1/databases/11111111-2222-4333-8444-555555555555", server.takeRequest().url.encodedPath)
        assertEquals(listOf("aaaaaaaa-0000-4000-8000-000000000001"), db.dataSources.map { it.id })
    }

    @Test
    fun parsesStatusGroups() = runTest {
        server.enqueue(ok(fixture("data_source_tasks.json")))
        val ds = client.dataSource("bbbbbbbb-0000-4000-8000-000000000002")
        val status = ds.properties.getValue("상태").status!!
        assertEquals(listOf("opt-done"), status.groups.single { it.name == "Complete" }.optionIds)
    }

    @Test
    fun queryFollowsCursorAndSendsFilterWithSeoulOffset() = runTest {
        server.enqueue(ok(fixture("query_calendar_p1.json")))
        server.enqueue(ok(fixture("query_calendar_p2.json")))
        val filter = QueryFilters.dateRange("dAtE", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31))

        val result = client.queryAll("aaaaaaaa-0000-4000-8000-000000000001", filter)

        assertEquals(listOf("cal-page-1", "cal-page-2", "cal-page-3", "cal-page-4"), result.pages.map { it.id })
        assertTrue(result.complete)

        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/v1/data_sources/aaaaaaaa-0000-4000-8000-000000000001/query", first.url.encodedPath)
        val body1 = Json.parseToJsonElement(first.body!!.utf8()).jsonObject
        assertEquals(100, body1["page_size"]!!.jsonPrimitive.content.toInt())
        assertNull(body1["start_cursor"])
        val conditions = body1["filter"]!!.jsonObject["and"]!!.jsonArray.map { it.jsonObject }
        assertEquals("dAtE", conditions[0]["property"]!!.jsonPrimitive.content)
        assertEquals("2026-07-01T00:00:00+09:00", conditions[0]["date"]!!.jsonObject["on_or_after"]!!.jsonPrimitive.content)
        assertEquals("2026-12-31T23:59:59+09:00", conditions[1]["date"]!!.jsonObject["on_or_before"]!!.jsonPrimitive.content)

        val body2 = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("cursor-2", body2["start_cursor"]!!.jsonPrimitive.content)
    }

    @Test
    fun incrementalFilterUsesLastEditedTimestamp() {
        val filter: JsonObject = QueryFilters.editedSince(Instant.parse("2026-10-11T15:13:00Z"))
        assertEquals("last_edited_time", filter["timestamp"]!!.jsonPrimitive.content)
        assertEquals(
            "2026-10-12T00:13:00+09:00",
            filter["last_edited_time"]!!.jsonObject["on_or_after"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun incompleteRequestStatusIsReported() = runTest {
        server.enqueue(ok("""{"results":[],"has_more":false,"next_cursor":null,"request_status":{"type":"incomplete"}}"""))
        assertFalse(client.queryAll("ds", null).complete)
    }

    @Test
    fun errorsAreClassified() = runTest {
        suspend fun errorFor(response: MockResponse): NotionError {
            server.enqueue(response)
            return try {
                client.dataSource("ds")
                fail("예외가 나야 함")
                error("unreachable")
            } catch (e: NotionException) {
                e.error
            }
        }
        assertEquals(NotionError.NotShared, errorFor(error(404, "object_not_found")))
        assertEquals(NotionError.NotShared, errorFor(error(403, "restricted_resource")))
        assertEquals(NotionError.Validation("validation_error"), errorFor(error(400, "validation_error")))
    }

    @Test
    fun malformedBodyIsServerError() = runTest {
        server.enqueue(ok("""{"not":"a data source"}"""))
        val e = runCatching { client.dataSource("ds") }.exceptionOrNull() as NotionException
        assertEquals(NotionError.Server(null), e.error)
    }
}
