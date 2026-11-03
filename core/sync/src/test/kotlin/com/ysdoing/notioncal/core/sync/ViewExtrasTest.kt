package com.ysdoing.notioncal.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M5 책 위젯이 쓰는 표지 주소와 읽은 비율. 보기에 보이지 않는 속성에서도 읽는다. */
class ViewExtrasTest {
    private fun props(json: String): Map<String, JsonElement> = Json.parseToJsonElement(json).jsonObject

    @Test
    fun coverUrlReadsAnExternalFile() {
        val p = props("""{"Title":{"type":"title","title":[]},"cover":{"type":"files","files":[{"type":"external","name":"a","external":{"url":"https://img.example/a.jpg"}}]}}""")
        assertEquals("https://img.example/a.jpg", coverUrlOf(p))
    }

    @Test
    fun coverUrlReadsANotionHostedFileToo() {
        val p = props("""{"cover":{"type":"files","files":[{"type":"file","name":"a.png","file":{"url":"https://prod-files.example/a.png?X-Amz-Expires=3600","expiry_time":"2026-11-03T06:00:00Z"}}]}}""")
        assertEquals("https://prod-files.example/a.png?X-Amz-Expires=3600", coverUrlOf(p))
    }

    @Test
    fun coverUrlPrefersAPropertyNamedLikeACoverOverOtherFileProperties() {
        val p = props(
            """{"첨부":{"type":"files","files":[{"type":"external","external":{"url":"https://x.example/doc.pdf"}}]},
                "표지":{"type":"files","files":[{"type":"external","external":{"url":"https://x.example/cover.jpg"}}]}}""",
        )
        assertEquals("https://x.example/cover.jpg", coverUrlOf(p))
        // 이름이 안 맞으면 파일이 든 첫 파일 속성
        val other = props("""{"첨부":{"type":"files","files":[{"type":"external","external":{"url":"https://x.example/doc.pdf"}}]}}""")
        assertEquals("https://x.example/doc.pdf", coverUrlOf(other))
    }

    @Test
    fun coverUrlIsEmptyWithoutFilesOrWithAnInsecureAddress() {
        assertEquals("", coverUrlOf(props("""{"cover":{"type":"files","files":[]}}""")))
        assertEquals("", coverUrlOf(props("""{"Title":{"type":"title","title":[]}}""")))
        // http 주소는 받지 않는다(https만)
        assertEquals("", coverUrlOf(props("""{"cover":{"type":"files","files":[{"type":"external","external":{"url":"http://x.example/a.jpg"}}]}}""")))
    }

    @Test
    fun progressUsesANumericFormulaNamedProgress() {
        assertEquals(0.12f, progressOf(props("""{"진행률":{"type":"formula","formula":{"type":"number","number":0.12}}}""")))
        // 1보다 크면 퍼센트
        assertEquals(0.3f, progressOf(props("""{"progress":{"type":"number","number":30}}"""))!!, 0.0001f)
        // 1은 100%
        assertEquals(1f, progressOf(props("""{"진행률":{"type":"number","number":1}}""")))
    }

    @Test
    fun progressReadsAPercentTextFormula() {
        assertEquals(0.4f, progressOf(props("""{"진행률":{"type":"formula","formula":{"type":"string","string":"40%"}}}""")))
        // %가 있으면 1%도 1%다(0.01)
        assertEquals(0.01f, progressOf(props("""{"진행률":{"type":"formula","formula":{"type":"string","string":"1%"}}}""")))
    }

    @Test
    fun progressFallsBackToCurrentPageOverTotalPages() {
        val p = props("""{"ing":{"type":"number","number":30},"page":{"type":"number","number":248}}""")
        assertEquals(30f / 248f, progressOf(p)!!, 0.0001f)
        // 읽은 쪽이 전체보다 크면 100%로 자른다
        assertEquals(1f, progressOf(props("""{"ing":{"type":"number","number":300},"page":{"type":"number","number":248}}""")))
    }

    @Test
    fun namedProgressBeatsThePagePair() {
        val p = props("""{"진행률":{"type":"formula","formula":{"type":"number","number":0.5}},"ing":{"type":"number","number":30},"page":{"type":"number","number":248}}""")
        assertEquals(0.5f, progressOf(p))
    }

    @Test
    fun progressIsNullWhenItCannotBeComputed() {
        assertNull(progressOf(props("""{"Title":{"type":"title","title":[]}}""")))
        assertNull(progressOf(props("""{"ing":{"type":"number","number":30},"page":{"type":"number","number":0}}"""))) // 전체 0쪽
        assertNull(progressOf(props("""{"ing":{"type":"number","number":30}}""")))
        assertNull(progressOf(props("""{"진행률":{"type":"formula","formula":{"type":"string","string":"없음"}}}""")))
    }
}
