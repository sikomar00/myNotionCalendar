package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Phase 11d WL-01 위젯 배치: 보기 하나 / 탭(보기 여러 개를 탭으로) / 섹션(위아래로 쌓기). 항목 수 상한이 다르다. */
enum class LayoutMode(val limit: Int) {
    SINGLE(1),
    TABS(5), // 2026-11-03 사용자 결정: 탭은 최대 5개
    SECTIONS(4), // WL-03: 섹션은 위젯당 최대 4개
}

/** 배치의 항목 하나: 보기와, 탭 이름([label], 비면 보기 이름), 섹션 높이 비율([weight] 1~3). */
@Serializable
data class LayoutItem(val viewId: String, val label: String = "", val weight: Int = 1) {
    /** 탭·섹션 머리에 쓸 이름. 이름 없는 보기도 노션에서 허용되니 마지막은 '보기'다. */
    fun title(viewName: String): String = label.ifBlank { viewName }.ifBlank { DEFAULT_TITLE }

    private companion object {
        const val DEFAULT_TITLE = "보기"
    }
}

/**
 * 위젯 하나의 배치(`widget_configs.layoutJson`). 편집 함수는 새 배치를 돌려주고, 상한을 넘거나 같은 보기를 또 넣는 등 못 하는 일은 null이다.
 * 읽을 때는 손으로 고치거나 옛 글자가 어긋난 것을 바로잡는다([parse]).
 */
@Serializable
data class WidgetLayout(val mode: LayoutMode = LayoutMode.SINGLE, val items: List<LayoutItem> = emptyList()) {
    fun add(viewId: String, label: String = ""): WidgetLayout? {
        val id = viewId.trim()
        if (id.isEmpty() || items.any { it.viewId == id } || items.size >= mode.limit) return null
        return copy(items = items + LayoutItem(id, label.trim()))
    }

    /** 설정 화면의 체크: 해제하면 빼고, 체크하면 담는다. 단일은 고른 보기로 바꾸고, 탭·섹션은 상한을 넘거나 이미 담았으면 null이다. */
    fun toggle(viewId: String, on: Boolean): WidgetLayout? = when {
        !on -> remove(viewId)
        mode == LayoutMode.SINGLE -> copy(items = emptyList()).add(viewId)
        else -> add(viewId)
    }

    fun remove(viewId: String): WidgetLayout = copy(items = items.filterNot { it.viewId == viewId })

    /** [viewId]를 [delta]칸(−1 앞, +1 뒤) 옮긴다. 끝이거나 없는 보기면 그대로다. */
    fun move(viewId: String, delta: Int): WidgetLayout {
        val from = items.indexOfFirst { it.viewId == viewId }
        val to = from + delta
        if (from < 0 || to !in items.indices) return this
        return copy(items = items.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    fun withLabel(viewId: String, label: String): WidgetLayout = update(viewId) { it.copy(label = label.trim()) }

    fun withWeight(viewId: String, weight: Int): WidgetLayout = update(viewId) { it.copy(weight = weight.coerceIn(WEIGHT_RANGE)) }

    /** 종류를 바꾼다. 새 종류의 상한보다 많으면 앞의 항목만 남긴다(다시 늘려도 잘린 항목은 돌아오지 않는다). */
    fun withMode(newMode: LayoutMode): WidgetLayout = copy(mode = newMode, items = items.take(newMode.limit))

    private fun update(viewId: String, change: (LayoutItem) -> LayoutItem): WidgetLayout = copy(items = items.map { if (it.viewId == viewId) change(it) else it })

    companion object {
        val WEIGHT_RANGE = 1..3
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** 빈 글자·깨진 글자·객체가 아니면 빈 배치다. 같은 보기·빈 ID는 버리고, 비율은 1~3으로, 상한을 넘는 항목은 자른다. */
        fun parse(text: String): WidgetLayout {
            val raw = text.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: return WidgetLayout()
            val seen = HashSet<String>()
            val items = raw.items
                .map { it.copy(viewId = it.viewId.trim(), label = it.label.trim(), weight = it.weight.coerceIn(WEIGHT_RANGE)) }
                .filter { it.viewId.isNotEmpty() && seen.add(it.viewId) }
                .take(raw.mode.limit)
            return WidgetLayout(raw.mode, items)
        }

        /** 저장용 글자. 배치가 없으면(보기가 하나도 없는 단일) 열을 비워 둔다. */
        fun encode(layout: WidgetLayout): String = if (layout == WidgetLayout()) "" else json.encodeToString(serializer(), layout)
    }
}
