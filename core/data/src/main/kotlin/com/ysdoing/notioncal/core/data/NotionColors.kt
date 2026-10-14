package com.ysdoing.notioncal.core.data

/** 노션 기본 색 10개. DB 색 선택지와 select 옵션 색 표시에 같이 쓴다(위젯도 사용). */
object NotionColors {
    val palette: List<Pair<String, String>> = listOf(
        "default" to "#787774",
        "gray" to "#9B9A97",
        "brown" to "#A27763",
        "orange" to "#D9730D",
        "yellow" to "#DFAB01",
        "green" to "#0F7B6C",
        "blue" to "#0B6E99",
        "purple" to "#6940A5",
        "pink" to "#AD1A72",
        "red" to "#E03E3E",
    )

    /** EX-03 DB 색 격자: 노션 10색 + 밝은·진한 변형 10색. */
    val pickerPalette: List<String> = palette.map { it.second } + listOf(
        "#2D9CDB", "#56CCF2", "#27AE60", "#6FCF97", "#F2C94C",
        "#F2994A", "#EB5757", "#BB6BD9", "#9B51E0", "#4F4F4F",
    )

    private val byName = palette.toMap()

    fun hexFor(colorKey: String?): String? = colorKey?.let { byName[it] }

    /** "#RRGGBB" → ARGB Int. 형식이 틀리면 기본 회색. */
    fun argb(hex: String): Int =
        hex.removePrefix("#").toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } ?: 0xFF787774.toInt()
}
