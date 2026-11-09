package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.dto.PageDto
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 12d: 노션 페이지 표지(`cover`)를 읽는다. 없으면 null이고, 모르는 필드가 늘어도 읽기가 깨지지 않는다. */
class PageCoverDtoTest {
    private fun page(cover: String) = NotionJson.decodeFromString(
        PageDto.serializer(),
        """{"id":"p1","last_edited_time":"2026-11-06T00:00:00.000Z","icon":{"type":"emoji","emoji":"📚"},"cover":$cover,"properties":{}}""",
    )

    @Test
    fun anExternalPageCoverIsKept() {
        val cover = page("""{"type":"external","external":{"url":"https://x.example/a.jpg"}}""").cover!!
        assertEquals("external", cover["type"]!!.jsonPrimitive.content)
        assertEquals("https://x.example/a.jpg", cover["external"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun aHostedPageCoverKeepsItsSignedUrlAndExpiry() {
        val cover = page("""{"type":"file","file":{"url":"https://prod-files.example/c.png?X-Amz-Expires=3600","expiry_time":"2026-11-06T06:00:00Z"}}""").cover!!
        assertEquals("2026-11-06T06:00:00Z", cover["file"]!!.jsonObject["expiry_time"]!!.jsonPrimitive.content)
    }

    @Test
    fun aNullCoverIsNull() {
        assertNull(page("null").cover)
    }
}
