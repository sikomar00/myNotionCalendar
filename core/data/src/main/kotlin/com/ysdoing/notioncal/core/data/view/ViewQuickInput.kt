package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.quickinput.QuickInputParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 해석한 것 하나를 시트에 보이는 칩. [key]로 끄고 켠다(`date`·`num`·`opt:<속성 ID>`). */
data class QuickChip(val key: String, val label: String)

/** 한 줄 입력의 해석 결과. [props]는 속성 ID → 값(보기 시트가 그대로 쓰는 모양), 해석할 게 없으면 [title]이 입력 그대로다. */
data class ViewQuickResult(val title: String, val date: LocalDate?, val props: Map<String, JsonElement>, val chips: List<QuickChip>)

/**
 * Phase 11g AD-03 보기 새 항목의 한 줄 빠른 입력 해석(규칙 기반). '내일 점심 12000 식비' → 제목 점심, 날짜 내일, 금액 12000, 분류 식비.
 *
 * - 날짜: 보기에 날짜 열이 있을 때만, [QuickInputParser]의 날짜 규칙으로(시각 낱말은 제목에 남는다. 기간은 해석하지 않는다).
 *   점이 든 숫자('1.5')는 날짜 열이 있으면 날짜(1월 5일)로 먼저 읽힌다.
 * - 금액: 쓸 수 있는 숫자 열 중 첫 번째에, 낱말 중 첫 숫자만('12000'·'12,000'·'1.5'·'12000원').
 * - 옵션: 선택·상태·다중 선택 열마다, 옵션 이름과 같은(대소문자 무시) 낱말 하나. 띄어 쓴 옵션 이름은 낱말 하나가 아니라 맞지 않는다.
 * - 나머지가 제목이다. 제목이 비게 되면 해석하지 않고 입력 그대로 제목으로 둔다.
 * [ignored]는 사용자가 끈 칩의 키라, 그 해석은 하지 않고 글자는 제목에 남긴다.
 */
object ViewQuickInput {
    private val number = Regex("""^(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?원?$""")
    private val optionTypes = setOf("select", "status", "multi_select")
    private val dateLabel = DateTimeFormatter.ofPattern("M/d")

    fun parse(text: String, columns: List<ViewColumn>, today: LocalDate, ignored: Set<String> = emptySet()): ViewQuickResult {
        val original = text.trim().replace(Regex("\\s+"), " ")
        var rest = original
        var date: LocalDate? = null
        if (columns.any { it.type == "date" } && "date" !in ignored) {
            val found = QuickInputParser.parse(rest, today, withTime = false)
            if (found.date != null && found.endDate == null) {
                date = found.date
                rest = found.title
            }
        }
        val tokens = rest.split(' ').filter { it.isNotEmpty() }.toMutableList()
        val props = LinkedHashMap<String, JsonElement>()
        val chips = mutableListOf<QuickChip>()
        date?.let { chips += QuickChip("date", dateLabel.format(it)) }
        takeNumber(tokens, columns, ignored)?.let { (column, value) ->
            props[column.id] = value
            chips += QuickChip("num", "${column.name} ${column.display(value)}")
        }
        columns.filter { it.type in optionTypes && it.writable && "opt:${it.id}" !in ignored }.forEach { column ->
            val at = tokens.indexOfFirst { token -> column.options.any { it.name.equals(token, ignoreCase = true) } }
            if (at < 0) return@forEach
            val option = column.options.first { it.name.equals(tokens[at], ignoreCase = true) }
            props[column.id] = if (column.type == "multi_select") JsonArray(listOf(JsonPrimitive(option.id))) else JsonPrimitive(option.id)
            chips += QuickChip("opt:${column.id}", "${column.name} ${option.name}")
            tokens.removeAt(at)
        }
        val title = tokens.joinToString(" ")
        if (title.isEmpty()) return ViewQuickResult(original, null, emptyMap(), emptyList())
        return ViewQuickResult(title, date, props, chips)
    }

    /** 숫자 열이 있고 끄지 않았으면 낱말 중 첫 숫자를 꺼내(낱말 목록에서 지운다) (열, 값)으로 돌려준다. */
    private fun takeNumber(tokens: MutableList<String>, columns: List<ViewColumn>, ignored: Set<String>): Pair<ViewColumn, JsonElement>? {
        val column = columns.firstOrNull { it.type == "number" && it.writable }?.takeIf { "num" !in ignored } ?: return null
        val at = tokens.indexOfFirst { number.matches(it) }
        if (at < 0) return null
        val value = tokens.removeAt(at).removeSuffix("원").replace(",", "").toDouble()
        return column to JsonPrimitive(value)
    }
}
