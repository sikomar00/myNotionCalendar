package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Phase 11c AD-05 프리셋: 이름(새 항목의 제목이 된다)과 값 묶음(속성 ID → 값, [ViewColumn.display]가 적은 모양). */
@Serializable
data class ViewPreset(val name: String, val props: Map<String, JsonElement> = emptyMap())

/** 위젯당 프리셋 목록을 `widget_configs.presetsJson`의 JSON 글자 하나로 다룬다. 깨진 글자는 프리셋 없음으로 읽는다. */
object ViewPresets {
    /** 위젯당 최대 개수(PRD AD-05 '프리셋 5개 위젯'). */
    const val MAX = 5

    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(ViewPreset.serializer())

    fun parse(text: String): List<ViewPreset> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(list, text) }.getOrDefault(emptyList())

    fun encode(presets: List<ViewPreset>): String = if (presets.isEmpty()) "" else json.encodeToString(list, presets)

    /**
     * [preset]을 더한 글자. 같은 이름이 있으면 그 자리를 바꾼다(꽉 차도 된다). 이름이 비었거나 새 이름인데 이미 [MAX]개면 null이다.
     */
    fun add(text: String, preset: ViewPreset): String? {
        val named = preset.copy(name = preset.name.trim())
        if (named.name.isEmpty()) return null
        val current = parse(text)
        val at = current.indexOfFirst { it.name == named.name }
        return when {
            at >= 0 -> encode(current.toMutableList().also { it[at] = named })
            current.size >= MAX -> null
            else -> encode(current + named)
        }
    }

    fun remove(text: String, name: String): String = encode(parse(text).filterNot { it.name == name })
}
