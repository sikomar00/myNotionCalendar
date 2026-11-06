package com.ysdoing.notioncal.core.data.view

import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 12d VR-GAL: 노션 갤러리 보기 설정에서 표지 출처·카드 크기·맞춤을 느슨히 읽는다(실제 값은 실측 전이라 모르는 값은 기본). */
class GalleryConfigTest {
    private fun parse(json: String) = GalleryConfig.parse(json)

    @Test
    fun aPropertyCoverCarriesItsPropertyId() {
        val config = parse("""{"type":"gallery","cover":{"type":"property","property_id":"cv"},"cover_size":"large","cover_aspect":"contain"}""")
        assertEquals(GalleryConfig(CoverKind.PROPERTY, "cv", CoverSize.LARGE, fit = true), config)
    }

    @Test
    fun aPropertyIdAloneIsEnoughEvenWhenTheTypeNameIsUnknown() {
        assertEquals(CoverKind.PROPERTY, parse("""{"cover":{"type":"files_media","property_id":"cv"}}""").cover)
    }

    @Test
    fun pageCoverNoneAndUnknownSourcesAreTold() {
        assertEquals(CoverKind.PAGE_COVER, parse("""{"cover":{"type":"page_cover"}}""").cover)
        assertEquals(CoverKind.NONE, parse("""{"cover":{"type":"none"}}""").cover)
        assertEquals(CoverKind.AUTO, parse("""{"cover":{"type":"page_content"}}""").cover) // 앱이 그릴 수 없는 출처는 자동
        assertEquals(CoverKind.AUTO, parse("""{"cover":{"type":"page_icon"}}""").cover)
    }

    @Test
    fun sizesDefaultToMediumAndAspectToCrop() {
        assertEquals(CoverSize.SMALL, parse("""{"cover_size":"small"}""").size)
        assertEquals(CoverSize.MEDIUM, parse("""{"cover_size":"S"}""").size) // 가려진 실측 값도 기본으로 읽는다
        assertEquals(CoverSize.MEDIUM, parse("""{}""").size)
        assertEquals(false, parse("""{"cover_aspect":"cover"}""").fit)
        assertEquals(true, parse("""{"cover_aspect":"fit"}""").fit)
    }

    @Test
    fun missingBrokenOrNonObjectConfigIsTheDefault() {
        val default = GalleryConfig()
        listOf("", "  ", "not json", "[1]", "42", """{"cover":null}""", """{"cover":"x"}""").forEach { assertEquals(it, default, parse(it)) }
        assertEquals(default, GalleryConfig.parse(null as kotlinx.serialization.json.JsonObject?))
    }
}
