package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 갤러리 카드의 표지를 어디서 가져오라고 노션 보기가 정했는가. [AUTO]는 정하지 않았거나 앱이 모르는 값이다. */
enum class CoverKind { AUTO, PROPERTY, PAGE_COVER, NONE }

enum class CoverSize { SMALL, MEDIUM, LARGE }

/**
 * Phase 12d VR-GAL: 노션 갤러리 보기 설정(`configuration`)에서 앱이 쓰는 부분. 표지 출처([cover]·[coverProp]), 카드 크기([size]),
 * 이미지를 잘라 채울지 전체가 보이게 맞출지([fit], `cover_aspect`)다. 읽는 모양은 공식 문서와 실측 픽스처(값이 가려져 있다)를 따른 것이라
 * **실제 값은 실측 전**이다: 모르는 값은 기본(표지 자동·보통 크기·잘라 채움)으로 읽어 표지가 안 나오는 일을 줄인다.
 */
data class GalleryConfig(
    val cover: CoverKind = CoverKind.AUTO,
    /** [cover]가 [CoverKind.PROPERTY]일 때 표지를 담은 파일 속성의 ID. */
    val coverProp: String = "",
    val size: CoverSize = CoverSize.MEDIUM,
    val fit: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 설정이 없거나 깨졌으면 기본이다. */
        fun parse(raw: JsonObject?): GalleryConfig {
            raw ?: return GalleryConfig()
            val coverObject = raw["cover"] as? JsonObject
            val type = coverObject?.text("type").orEmpty().lowercase()
            val property = coverObject?.text("property_id").orEmpty()
            val (kind, prop) = when {
                coverObject == null -> CoverKind.AUTO to ""
                property.isNotEmpty() -> CoverKind.PROPERTY to property
                type.contains("cover") -> CoverKind.PAGE_COVER to ""
                type == "none" -> CoverKind.NONE to ""
                else -> CoverKind.AUTO to "" // page_content 등 앱이 그릴 수 없는 출처는 페이지 표지·휴리스틱으로 대신한다
            }
            val size = when (raw.text("cover_size").lowercase()) {
                "small" -> CoverSize.SMALL
                "large" -> CoverSize.LARGE
                else -> CoverSize.MEDIUM
            }
            return GalleryConfig(kind, prop, size, fit = raw.text("cover_aspect").lowercase().let { it == "contain" || it == "fit" })
        }

        /** 저장된 `viewConfig` 글자에서(위젯). 빈 글자·깨진 글자·객체가 아니면 기본이다. */
        fun parse(text: String): GalleryConfig =
            parse(text.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() })

        private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
