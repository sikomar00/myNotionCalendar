package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 11g AD-02 입력 양식: 보기마다 보일 속성·순서·기본값·날짜 칩 여부를 위젯에 저장하고, 깨진 글자·사라진 속성에도 시트가 무너지지 않는다. */
class InputFormTest {
    private val title = ViewColumn("t", "이름", "title")
    private val amount = ViewColumn("n", "금액", "number")
    private val kind = ViewColumn("k", "분류", "select", listOf(ViewChoice("o1", "식비"), ViewChoice("o2", "교통")))
    private val tags = ViewColumn("m", "태그", "multi_select", listOf(ViewChoice("g1", "a"), ViewChoice("g2", "b")))
    private val done = ViewColumn("c", "완료", "checkbox")
    private val day = ViewColumn("d", "날짜", "date")
    private val formula = ViewColumn("f", "합계", "formula")
    private val columns = listOf(title, amount, kind, tags, done, day, formula)

    private fun form(vararg ids: String, showDate: Boolean = true) = InputForm(ids.map { FormField(it) }, showDate)

    @Test
    fun onlyWritableNonTitleNonDateColumnsCanBeFormFields() {
        assertEquals(listOf("n", "k", "m", "c"), InputForm.editable(columns).map { it.id })
    }

    @Test
    fun visibleColumnsFollowTheFormOrderAndDropMissingOrUnusableOnes() {
        val f = form("c", "gone", "n", "t", "d", "f", "k")
        assertEquals(listOf("c", "n", "k"), f.visibleColumns(columns).map { it.id })
    }

    @Test
    fun defaultsOfVisibleColumnsAreReturnedByPropertyId() {
        val f = InputForm(listOf(FormField("n", JsonPrimitive(5000.0)), FormField("k", JsonPrimitive("o2")), FormField("c")))
        assertEquals(mapOf("n" to JsonPrimitive(5000.0), "k" to JsonPrimitive("o2")), f.defaults(columns))
    }

    @Test
    fun aDefaultThatNoLongerFitsItsColumnIsDropped() {
        val f = InputForm(
            listOf(
                FormField("k", JsonPrimitive("removed")), // 노션에서 지워진 옵션
                FormField("m", JsonArray(listOf(JsonPrimitive("g2"), JsonPrimitive("gone")))), // 남은 옵션만
                FormField("n", JsonPrimitive(1.5)),
            ),
        )
        assertEquals(mapOf("m" to JsonArray(listOf(JsonPrimitive("g2"))), "n" to JsonPrimitive(1.5)), f.defaults(columns))
        assertEquals(emptyMap<String, Any>(), InputForm(listOf(FormField("m", JsonArray(listOf(JsonPrimitive("gone")))))).defaults(columns))
    }

    @Test
    fun prunedKeepsOnlyFieldsThatStillHaveAUsableColumnInOrder() {
        val f = InputForm(listOf(FormField("c"), FormField("gone", JsonPrimitive("x")), FormField("t"), FormField("n", JsonPrimitive(2.0))), showDate = false)
        assertEquals(InputForm(listOf(FormField("c"), FormField("n", JsonPrimitive(2.0))), showDate = false), f.pruned(columns))
    }

    @Test
    fun toggleAppendsOnAndRemovesOffAndIgnoresRepeats() {
        val f = InputForm().toggle("n", true).toggle("k", true).toggle("n", true)
        assertEquals(listOf("n", "k"), f.fields.map { it.propId })
        assertEquals(listOf("k"), f.toggle("n", false).fields.map { it.propId })
        assertEquals(f, f.toggle("zzz", false))
    }

    @Test
    fun moveSwapsWithTheNeighbourAndStopsAtTheEnds() {
        val f = form("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), f.move("a", +1).fields.map { it.propId })
        assertEquals(listOf("a", "c", "b"), f.move("c", -1).fields.map { it.propId })
        assertEquals(f, f.move("a", -1))
        assertEquals(f, f.move("c", +1))
        assertEquals(f, f.move("zzz", +1))
    }

    @Test
    fun withDefaultsChangesOnlyTheFieldsInTheValuesAndANullClearsTheDefault() {
        val f = InputForm(listOf(FormField("n", JsonPrimitive(1.0)), FormField("k", JsonPrimitive("o1")), FormField("c", JsonPrimitive(true))))
        val saved = f.withDefaults(mapOf("k" to JsonPrimitive("o2"), "n" to null, "other" to JsonPrimitive("x")))
        // 값 묶음에 없는 c(그때 시트에 안 보이던 속성)는 기본값을 지키고, other는 양식에 없으니 만들지 않는다
        assertEquals(listOf(FormField("n", null), FormField("k", JsonPrimitive("o2")), FormField("c", JsonPrimitive(true))), saved.fields)
    }

    @Test
    fun usableDropsHelperColumnsThatAreNotShownInTheView() {
        val pages = ViewColumn("p", "읽은 쪽", "number", role = ViewColumn.ROLE_PAGES_READ)
        val filter = ViewColumn("s", "상태", "select", role = ViewColumn.ROLE_FILTER)
        val group = ViewColumn("g", "분류", "select", role = ViewColumn.ROLE_GROUP)
        val all = listOf(title, amount, pages, filter, group)
        // 보기에 안 보이는 도우미 열은 빠지고, 보이는 열(역할이 붙었어도)은 남는다
        assertEquals(listOf("t", "n", "g"), InputForm.usable(all, listOf("이름", "금액", "분류")).map { it.id })
        assertEquals(listOf("t", "n"), InputForm.usable(all, listOf("이름", "금액")).map { it.id })
    }

    @Test
    fun recipeFlagsAreEditableAndMakeTheFormNonDefault() {
        assertTrue(InputForm().isDefault)
        val f = InputForm().withDateToday(true).withTitleDate(true).withOncePerDay(true)
        assertEquals(InputForm(dateToday = true, titleDate = true, oncePerDay = true), f)
        assertEquals(false, f.isDefault)
        assertEquals(true, f.withDateToday(false).titleDate) // 하나를 꺼도 나머지는 그대로
        assertEquals(f.copy(dateToday = false, titleDate = false, oncePerDay = false), InputForm())
    }

    @Test
    fun recipeFlagsSurviveStorageEditsAndPruningAndOldFormsReadAsOff() {
        val flagged = InputForm(listOf(FormField("n"), FormField("gone")), showDate = true, dateToday = true, titleDate = true, oncePerDay = true)
        val text = InputForms.put("", "v1", flagged)
        assertEquals(flagged, InputForms.get(text, "v1"))
        assertEquals(true, InputForms.get(text, "v1")!!.pruned(columns).let { it.dateToday && it.titleDate && it.oncePerDay }) // 속성만 가려내고 플래그는 남긴다
        assertEquals(true, flagged.toggle("k", true).oncePerDay)
        assertEquals(true, flagged.withDefaults(mapOf("n" to JsonPrimitive(1.0))).dateToday)
        // 플래그가 없던 옛 글자는 모두 꺼진 양식으로 읽는다
        assertEquals(InputForm(listOf(FormField("n"))), InputForms.get("""{"v1":{"fields":[{"propId":"n"}]}}""", "v1"))
        // 켠 플래그만으로도 양식은 저장된다(기본 양식이 아니다)
        assertEquals(InputForm(dateToday = true), InputForms.get(InputForms.put("", "v2", InputForm(dateToday = true)), "v2"))
    }

    @Test
    fun withShowDateOnlyChangesTheDateChip() {
        val f = form("n").withShowDate(false)
        assertEquals(InputForm(listOf(FormField("n")), showDate = false), f)
    }

    @Test
    fun parseReadsBlankGarbageAndNonObjectsAsNoForms() {
        listOf("", "  ", "not json", "[]", "\"x\"", "{\"v\":3}").forEach { assertEquals(it, emptyMap<String, InputForm>(), InputForms.parse(it)) }
    }

    @Test
    fun parseDropsBlankAndRepeatedFieldsAndUnknownKeysButKeepsTheRest() {
        val text = """{"v1":{"fields":[{"propId":" n "},{"propId":""},{"propId":"n"},{"propId":"k","default":"o1","extra":1}],"showDate":false,"zzz":1},"":{"fields":[]}}"""
        val parsed = InputForms.parse(text)
        assertEquals(setOf("v1"), parsed.keys)
        assertEquals(InputForm(listOf(FormField("n"), FormField("k", JsonPrimitive("o1"))), showDate = false), parsed["v1"])
    }

    @Test
    fun aBrokenFormDoesNotTakeTheOtherFormsDown() {
        val text = """{"bad":{"fields":"oops"},"good":{"fields":[{"propId":"n"}]}}"""
        assertNull(InputForms.get(text, "bad"))
        assertEquals(form("n"), InputForms.get(text, "good"))
    }

    @Test
    fun putRoundTripsAndKeepsOtherViews() {
        val a = InputForms.put("", "va", form("n"))
        val both = InputForms.put(a, "vb", InputForm(listOf(FormField("k", JsonPrimitive("o1"))), showDate = false))
        assertEquals(form("n"), InputForms.get(both, "va"))
        assertEquals(InputForm(listOf(FormField("k", JsonPrimitive("o1"))), showDate = false), InputForms.get(both, "vb"))
        assertNull(InputForms.get(both, "vc"))
    }

    @Test
    fun puttingTheUntouchedFormRemovesItAndAnEmptyMapIsAnEmptyColumn() {
        val a = InputForms.put("", "va", form("n"))
        assertEquals("", InputForms.put(a, "va", InputForm()))
        val two = InputForms.put(a, "vb", form("k"))
        assertEquals(setOf("vb"), InputForms.parse(InputForms.put(two, "va", InputForm())).keys)
        assertTrue(InputForm().isDefault)
    }

    @Test
    fun withDefaultsOnTheStoredFormUpdatesOnlyThatViewAndNeedsAnExistingForm() {
        val text = InputForms.put(InputForms.put("", "va", form("n", "k")), "vb", form("c"))
        val updated = InputForms.withDefaults(text, "va", mapOf("n" to JsonPrimitive(3.0)))!!
        assertEquals(InputForm(listOf(FormField("n", JsonPrimitive(3.0)), FormField("k"))), InputForms.get(updated, "va"))
        assertEquals(form("c"), InputForms.get(updated, "vb"))
        assertNull(InputForms.withDefaults(text, "vz", mapOf("n" to JsonPrimitive(3.0))))
    }
}
