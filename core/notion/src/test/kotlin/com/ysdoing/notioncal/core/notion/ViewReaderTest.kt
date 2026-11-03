package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.http.RetryInterceptor
import com.ysdoing.notioncal.core.notion.http.TokenBucket
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** VW-01 보기 읽기 계약: 호출 경로, 보기 설정 파싱, 값을 받는 쿼리의 필터·정렬·행 수. */
class ViewReaderTest {
    private val server = MockWebServer()
    private val time = FakeTime()
    private lateinit var reader: ViewReader

    @Before
    fun setUp() {
        server.start()
        val api = createNotionApi(
            baseUrl = server.url("/"),
            tokenProvider = { "test-token-not-real" },
            retry = RetryInterceptor(sleeper = time, random = Random(0)),
            bucket = TokenBucket(clock = time, sleeper = time),
            socketTag = null,
        )
        reader = NotionClient(api, NotionJson)
    }

    @After
    fun tearDown() = server.close()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private val viewJson = """
        {"object":"view","id":"v1","name":"할 일","type":"table",
         "filter":{"property":"p1","checkbox":{"equals":false}},
         "sorts":[{"property":"Name","direction":"ascending"}],
         "quick_filters":{"p2":{"select":{"is_not_empty":true}}},
         "configuration":{"type":"table","properties":[
           {"property_id":"title","property_name":"Name","visible":true,"width":200},
           {"property_id":"p2","property_name":"Ctgr","visible":false}]}}
    """.trimIndent()

    @Test
    fun listsEveryPageOfViewIdsNotOnlyTheFirst() = runTest {
        // CN-03: 보기가 100개를 넘어 목록이 나뉘어 와도 끝까지 받는다(예전에는 첫 쪽만 읽었다).
        server.enqueue(ok("""{"object":"list","results":[{"object":"view","id":"v1"},{"object":"view","id":"v2"}],"has_more":true,"next_cursor":"c2"}"""))
        server.enqueue(ok("""{"object":"list","results":[{"object":"view","id":"v3"}],"has_more":false}"""))
        assertEquals(listOf("v1", "v2", "v3"), reader.viewIds("ds1"))
        assertNull(server.takeRequest().url.queryParameter("start_cursor"))
        assertEquals("c2", server.takeRequest().url.queryParameter("start_cursor"))
    }

    @Test
    fun listsViewIdsForADataSource() = runTest {
        server.enqueue(ok("""{"object":"list","results":[{"object":"view","id":"v1"},{"object":"view","id":"v2"}],"has_more":false}"""))
        assertEquals(listOf("v1", "v2"), reader.viewIds("ds1"))
        val url = server.takeRequest().url
        assertEquals("/v1/views", url.encodedPath)
        assertEquals("ds1", url.queryParameter("data_source_id"))
    }

    @Test
    fun parsesViewSettings() = runTest {
        server.enqueue(ok(viewJson))
        val view = reader.view("v1")
        assertEquals("/v1/views/v1", server.takeRequest().url.encodedPath)
        assertEquals("table", view.type)
        assertEquals(listOf("Name" to true, "Ctgr" to false), view.configuration!!.properties.map { it.propertyName to it.visible })
    }

    @Test
    fun rowsQueryCarriesViewFilterQuickFilterAndSorts() = runTest {
        server.enqueue(ok(viewJson))
        server.enqueue(ok("""{"object":"list","results":[],"has_more":true,"next_cursor":"c"}"""))
        val view = reader.view("v1")
        server.takeRequest()
        reader.rows("ds1", view)
        val request = server.takeRequest()
        assertEquals("/v1/data_sources/ds1/query", request.url.encodedPath)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(2, body["filter"]!!.jsonObject["and"]!!.jsonArray.size)
        assertEquals("Name", body["sorts"]!!.jsonArray[0].jsonObject["property"]!!.jsonPrimitive.content)
        assertEquals(VIEW_ROW_LIMIT, body["page_size"]!!.jsonPrimitive.content.toInt())
        assertNull(body["start_cursor"])
    }

    @Test
    fun viewFilterIsNullWithoutAnyFilter() {
        assertNull(viewFilter(com.ysdoing.notioncal.core.notion.dto.ViewDto(id = "x", quickFilters = kotlinx.serialization.json.JsonObject(emptyMap()))))
    }
}
