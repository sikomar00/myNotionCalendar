package com.ysdoing.notioncal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.Assert.assertEquals

class ViewProbeTest {
    @Test
    fun hidesStringValuesButKeepsShape() {
        val raw = Json.parseToJsonElement(
            """{"object":"view","id":"abc","name":"내 보기","type":"table","filter":{"and":[{"property":"p1","checkbox":{"equals":true}}]},"n":3}""",
        )
        val shaped = shapeOf(raw).jsonObject.toString()
        assertEquals(
            """{"object":"view","id":"S","name":"S","type":"table","filter":{"and":[{"property":"S","checkbox":{"equals":true}}]},"n":3}""",
            shaped,
        )
    }

    @Test
    fun mergesViewFilterAndQuickFiltersWithAnd() {
        fun view(json: String) = Json.parseToJsonElement(json).jsonObject
        assertEquals(null, viewFilter(view("""{"filter":null,"quick_filters":{}}""")))
        assertEquals(
            """{"property":"p1","checkbox":{"equals":false}}""",
            viewFilter(view("""{"filter":null,"quick_filters":{"p1":{"checkbox":{"equals":false}}}}""")).toString(),
        )
        assertEquals(
            """{"and":[{"select":{"is_not_empty":true},"property":"a"},{"property":"p1","checkbox":{"equals":true}}]}""",
            viewFilter(view("""{"filter":{"select":{"is_not_empty":true},"property":"a"},"quick_filters":{"p1":{"checkbox":{"equals":true}}}}""")).toString(),
        )
    }
}
