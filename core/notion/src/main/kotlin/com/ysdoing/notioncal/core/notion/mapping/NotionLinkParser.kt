package com.ysdoing.notioncal.core.notion.mapping

/** DB 링크·ID에서 database_id(하이픈 포함 UUID)를 뽑는다. 쿼리의 `?v=` 보기 ID는 무시한다. */
object NotionLinkParser {
    private val hyphenated = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val compact = Regex("[0-9a-fA-F]{32}")

    fun databaseId(input: String): String? {
        val path = input.trim().substringBefore('?').substringBefore('#')
        val raw = hyphenated.findAll(path).lastOrNull()?.value?.replace("-", "")
            ?: compact.findAll(path).lastOrNull()?.value
            ?: return null
        val id = raw.lowercase()
        return "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}"
    }
}
