package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.view.CoverKind
import com.ysdoing.notioncal.core.data.view.GalleryConfig
import com.ysdoing.notioncal.core.notion.dto.PageDto
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

    // --- Phase 12d 갤러리: 보기가 정한 표지 출처 → 페이지 표지 → 휴리스틱

    private fun page(properties: String, cover: String? = null) = PageDto(
        id = "p1", lastEditedTime = "2026-11-06T00:00:00.000Z",
        properties = Json.parseToJsonElement(properties).jsonObject,
        cover = cover?.let { Json.parseToJsonElement(it).jsonObject },
    )

    private val files = """{"사진":{"id":"cv","type":"files","files":[{"type":"external","external":{"url":"https://x.example/prop.jpg"}}]},
        "표지":{"id":"other","type":"files","files":[{"type":"external","external":{"url":"https://x.example/heuristic.jpg"}}]}}"""
    private val pageCover = """{"type":"external","external":{"url":"https://x.example/page.jpg"}}"""

    @Test
    fun aGalleryPropertyCoverBeatsThePageCoverAndTheHeuristicAndIsFoundByPropertyId() {
        val gallery = GalleryConfig(CoverKind.PROPERTY, "cv")
        assertEquals("https://x.example/prop.jpg", coverUrlOf(page(files, pageCover), gallery))
    }

    @Test
    fun aPropertyThatIsMissingFromThePageFallsBackToThePageCoverThenTheHeuristic() {
        val gallery = GalleryConfig(CoverKind.PROPERTY, "cv")
        val noProp = """{"표지":{"id":"other","type":"files","files":[{"type":"external","external":{"url":"https://x.example/heuristic.jpg"}}]}}"""
        assertEquals("https://x.example/page.jpg", coverUrlOf(page(noProp, pageCover), gallery)) // 속성이 없다(지워짐) → 페이지 표지
        assertEquals("https://x.example/heuristic.jpg", coverUrlOf(page(noProp), gallery)) // 페이지 표지도 없음 → 휴리스틱
        assertEquals("", coverUrlOf(page("""{"Name":{"type":"title","title":[]}}"""), gallery))
    }

    @Test
    fun aConfiguredPropertyThatIsEmptyOnARowGivesNoCoverInsteadOfAnUnrelatedFile() {
        // 노션도 그 카드에는 표지를 보이지 않으니 다른 파일 속성(첨부 등)·페이지 표지로 대신하지 않는다.
        val emptyPoster = """{"사진":{"id":"cv","type":"files","files":[]},"첨부":{"id":"att","type":"files","files":[{"type":"external","external":{"url":"https://x.example/doc.pdf"}}]}}"""
        assertEquals("", coverUrlOf(page(emptyPoster, pageCover), GalleryConfig(CoverKind.PROPERTY, "cv")))
    }

    @Test
    fun thePageCoverSourceUsesThePageCoverFirstAndNoneMeansNoCover() {
        assertEquals("https://x.example/page.jpg", coverUrlOf(page(files, pageCover), GalleryConfig(CoverKind.PAGE_COVER)))
        assertEquals("https://x.example/heuristic.jpg", coverUrlOf(page(files), GalleryConfig(CoverKind.PAGE_COVER))) // 페이지 표지가 없으면 휴리스틱
        assertEquals("", coverUrlOf(page(files, pageCover), GalleryConfig(CoverKind.NONE))) // 노션에서 표지를 껐다
    }

    @Test
    fun anUnspecifiedSourceKeepsTheHeuristicFirstSoTheBookWidgetIsNotChanged() {
        // 같은 coverUrl을 책 위젯도 읽는다: 설정이 없거나 모르는 값이면 기존처럼 이름이 표지 같은 파일 속성이 먼저다.
        assertEquals("https://x.example/heuristic.jpg", coverUrlOf(page(files, pageCover), GalleryConfig(CoverKind.AUTO)))
        assertEquals("https://x.example/page.jpg", coverUrlOf(page("{}", pageCover), GalleryConfig(CoverKind.AUTO))) // 파일이 없을 때만 페이지 표지
    }

    @Test
    fun aNotionHostedPageCoverIsReadAndAnInsecureOneIsNot() {
        val hosted = """{"type":"file","file":{"url":"https://prod-files.example/c.png?X-Amz-Expires=3600","expiry_time":"2026-11-06T06:00:00Z"}}"""
        assertEquals("https://prod-files.example/c.png?X-Amz-Expires=3600", coverUrlOf(page("{}", hosted), GalleryConfig()))
        assertEquals("", coverUrlOf(page("{}", """{"type":"external","external":{"url":"http://x.example/c.jpg"}}"""), GalleryConfig()))
        assertEquals("", coverUrlOf(page("{}", """{"type":"emoji"}"""), GalleryConfig()))
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
    fun progressPairMatchingIgnoresCaseAndAcceptsCommonNames() {
        assertEquals(0.5f, progressOf(props("""{"Current Page":{"type":"number","number":100},"Total Pages":{"type":"number","number":200}}""")))
        assertEquals(0.25f, progressOf(props("""{"ING":{"type":"number","number":50},"Pages":{"type":"number","number":200}}""")))
        assertEquals(0.1f, progressOf(props("""{"읽은 쪽":{"type":"number","number":20},"전체 쪽":{"type":"number","number":200}}""")))
        // 같은 속성을 현재와 전체로 둘 다 쓰지 않는다
        assertNull(progressOf(props("""{"Total Pages":{"type":"number","number":200}}""")))
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
