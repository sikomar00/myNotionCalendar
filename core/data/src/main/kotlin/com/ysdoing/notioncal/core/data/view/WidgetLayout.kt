package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

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
 * [tiles]는 Phase 11e 집계 타일 줄(섹션 배치에서만 그려지는 맨 위 섹션 하나, 타일 최대 [TILE_LIMIT]개)이고 섹션 상한에 한 자리를 쓴다.
 */
@Serializable
data class WidgetLayout(val mode: LayoutMode = LayoutMode.SINGLE, val items: List<LayoutItem> = emptyList(), val tiles: List<AggTile> = emptyList()) {
    /** 타일 줄이 차지하는 섹션 자리(섹션 배치에 타일이 있으면 1). */
    private val stripSlots: Int get() = if (mode == LayoutMode.SECTIONS && tiles.isNotEmpty()) 1 else 0

    fun add(viewId: String): WidgetLayout? {
        val id = viewId.trim()
        if (id.isEmpty() || items.any { it.viewId == id } || items.size + stripSlots >= mode.limit) return null
        return copy(items = items + LayoutItem(id))
    }

    /** 타일을 담는다(번호는 비어 있는 첫 t1·t2…). 섹션 배치에서만, 타일 줄을 처음 만들 때는 섹션 자리가 남아야 하고, 타일은 [TILE_LIMIT]개까지다. */
    fun addTile(tile: AggTile): WidgetLayout? {
        if (mode != LayoutMode.SECTIONS || tiles.size >= TILE_LIMIT || (tiles.isEmpty() && items.size >= mode.limit)) return null
        val id = generateSequence(1) { it + 1 }.map { "t$it" }.first { candidate -> tiles.none { it.id == candidate } }
        return copy(tiles = tiles + tile.copy(id = id))
    }

    /** 이 배치를 그리려고 읽어야 할 보기 ID(겹침 없이): 보기 항목과, 섹션 배치일 때 타일의 보기. */
    fun boardIds(): List<String> = (items.map { it.viewId } + if (mode == LayoutMode.SECTIONS) tiles.map { it.viewId } else emptyList()).distinct()

    fun removeTile(id: String): WidgetLayout = copy(tiles = tiles.filterNot { it.id == id })

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

    /**
     * 이 배치([this])에서, 이전 배치 [old]의 [oldIndex]번 탭이 보여 주던 보기가 있는 번호. 설정에서 탭을 빼거나 옮겨도 마지막으로 본 탭이
     * 다른 보기로 바뀌지 않게 한다. 그 보기가 없어졌으면 첫 탭(0)이다.
     */
    fun tabIndexAfter(old: WidgetLayout, oldIndex: Int): Int {
        val shown = old.items.getOrNull(oldIndex.coerceIn(0, old.items.lastIndex.coerceAtLeast(0)))?.viewId
        return items.indexOfFirst { it.viewId == shown }.coerceAtLeast(0)
    }

    fun withLabel(viewId: String, label: String): WidgetLayout = update(viewId) { it.copy(label = label.trim()) }

    fun withWeight(viewId: String, weight: Int): WidgetLayout = update(viewId) { it.copy(weight = weight.coerceIn(WEIGHT_RANGE)) }

    /** 종류를 바꾼다. 새 종류의 상한보다 많으면 앞의 항목만 남긴다(다시 늘려도 잘린 항목은 돌아오지 않는다). */
    fun withMode(newMode: LayoutMode): WidgetLayout = copy(mode = newMode).let { it.copy(items = items.take(newMode.limit - it.stripSlots)) }

    private fun update(viewId: String, change: (LayoutItem) -> LayoutItem): WidgetLayout = copy(items = items.map { if (it.viewId == viewId) change(it) else it })

    companion object {
        val WEIGHT_RANGE = 1..3
        const val TILE_LIMIT = 6 // PRD AG: 전체 크기에서 타일 6개
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * 빈 글자·깨진 글자·객체가 아니면 빈 배치다. 같은 보기·빈 ID는 버리고, 비율은 1~3으로, 상한을 넘는 항목은 자른다.
         * 타일은 하나씩 읽어 깨진 것(모르는 함수·빈 보기·필요한 속성 없음·번호 겹침)만 버리고, 타일 줄 자리만큼 보기 상한이 줄어든다.
         */
        fun parse(text: String): WidgetLayout {
            val root = text.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return WidgetLayout()
            val raw = runCatching { json.decodeFromJsonElement(serializer(), JsonObject(root - "tiles")) }.getOrNull() ?: return WidgetLayout()
            val seen = HashSet<String>()
            val items = raw.items
                .map { it.copy(viewId = it.viewId.trim(), label = it.label.trim(), weight = it.weight.coerceIn(WEIGHT_RANGE)) }
                .filter { it.viewId.isNotEmpty() && seen.add(it.viewId) }
            val tiles = (root["tiles"] as? JsonArray).orEmpty()
                .mapNotNull { runCatching { json.decodeFromJsonElement(AggTile.serializer(), it) }.getOrNull()?.normalized() }
                .distinctBy { it.id }
                .take(TILE_LIMIT)
            val layout = WidgetLayout(raw.mode, emptyList(), tiles)
            return layout.copy(items = items.take(raw.mode.limit - layout.stripSlots))
        }

        /** 저장용 글자. 배치가 없으면(보기가 하나도 없는 단일) 열을 비워 둔다. */
        fun encode(layout: WidgetLayout): String = if (layout == WidgetLayout()) "" else json.encodeToString(serializer(), layout)
    }
}
