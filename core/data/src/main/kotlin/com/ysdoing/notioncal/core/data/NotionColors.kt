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

    /** #191919의 상대 휘도. */
    private const val DARK_TEXT_LUMINANCE = 0.0097

    fun hexFor(colorKey: String?): String? = colorKey?.let { byName[it] }

    /**
     * EX-11 강조 칸: [hex] 색 배경 위에 읽히는 글자색(ARGB). 흰색과 먹색(#191919) 중 WCAG 대비가 큰 쪽을 고른다
     * (노랑·주황 같은 밝은 배경은 먹색, 파랑·보라·빨강은 흰색).
     */
    fun onColor(hex: String): Int {
        val c = argb(hex)
        fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        val luminance = 0.2126 * lin(c shr 16 and 0xFF) + 0.7152 * lin(c shr 8 and 0xFF) + 0.0722 * lin(c and 0xFF)
        val withWhite = 1.05 / (luminance + 0.05)
        val withDark = (luminance + 0.05) / (DARK_TEXT_LUMINANCE + 0.05)
        return if (withDark > withWhite) 0xFF191919.toInt() else 0xFFFFFFFF.toInt()
    }

    /** "#RRGGBB" → ARGB Int. 형식이 틀리면 기본 회색. */
    fun argb(hex: String): Int =
        hex.removePrefix("#").toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } ?: 0xFF787774.toInt()
}
