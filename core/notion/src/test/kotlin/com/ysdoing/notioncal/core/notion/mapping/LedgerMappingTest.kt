package com.ysdoing.notioncal.core.notion.mapping

import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.QueryResponseDto
import com.ysdoing.notioncal.core.notion.fixture
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** LD-01·02 거래내역 읽기·쓰기, LD-05 예산·카테고리 마스터 읽기. */
class LedgerMappingTest {
    private val ledger = LedgerMapping(
        amountPropId = "aMt", flowPropId = "fLw", categoryPropId = "cAt", timePropId = "tIme",
        paymentPropId = "pAy", plannedPropId = "pLn", manualPropId = "mAn",
    )
    private val mapping = PropertyMapping("title", "nAlJ", null, null, null, null, ledger = ledger)
    private val emptySchema = NotionJson.decodeFromString(DataSourceDto.serializer(), """{"id":"x","properties":{}}""")

    @Test
    fun readsLedgerFields() {
        val pages = NotionJson.decodeFromString(QueryResponseDto.serializer(), fixture("query_ledger.json")).results
        val (kimbap, planned) = pages.map { NotionPageMapper(mapping, emptySchema).map(it).event!! }

        val tx = kimbap.ledger!!
        assertEquals(-9_800L, tx.amount)
        assertEquals("지출", tx.flow)
        assertEquals("식비", tx.category)
        assertEquals("12:30", tx.time)
        assertEquals("토스뱅크 통장", tx.payment)
        assertEquals(false, tx.planned)
        assertEquals(Instant.parse("2026-10-19T01:00:00Z"), kimbap.createdTime)
        assertEquals(LocalDate.of(2026, 10, 16), kimbap.startDate)

        val p = planned.ledger!!
        assertEquals(-50_000L, p.amount) // 원 단위로 반올림
        assertNull(p.category)
        assertNull(p.time)
        assertEquals(true, p.planned)
        assertEquals("", planned.title)

        // 가계부 매핑이 없는 DB(📅 Calendar)는 거래 값을 읽지 않는다.
        assertNull(NotionPageMapper(mapping.copy(ledger = null), emptySchema).map(pages.first()).event!!.ledger)
    }

    /** LD-07 쓰기: 금액 number, 타입·대분류 select(이름), 시간 rich_text, 예정·앱입력 checkbox. 매핑 없는 속성은 보내지 않는다. */
    @Test
    fun writesLedgerFields() {
        val json = PropertyEncoder(mapping, null, null).encode(
            PageWrite(
                title = "점심",
                ledger = LedgerWrite(amount = -9_000, flow = "지출", category = "식비", time = "12:10", planned = false, manual = true),
            ),
        )
        assertEquals("-9000", json["aMt"]!!.jsonObject["number"]!!.jsonPrimitive.content)
        assertEquals("지출", json["fLw"]!!.jsonObject["select"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("식비", json["cAt"]!!.jsonObject["select"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("12:10", json["tIme"]!!.jsonObject["rich_text"]!!.jsonArray[0].jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("false", json["pLn"]!!.jsonObject["checkbox"]!!.jsonPrimitive.content)
        assertEquals("true", json["mAn"]!!.jsonObject["checkbox"]!!.jsonPrimitive.content)
        assertEquals(setOf("title", "aMt", "fLw", "cAt", "tIme", "pLn", "mAn"), json.keys)

        // LD-09 '실제로 전환': 예정·앱입력만 바꾼다. 대분류를 비우면 select null.
        val convert = PropertyEncoder(mapping, null, null).encode(PageWrite(ledger = LedgerWrite(planned = false, manual = true)))
        assertEquals(setOf("pLn", "mAn"), convert.keys)
        val cleared = PropertyEncoder(mapping, null, null).encode(PageWrite(ledger = LedgerWrite(category = "")))
        assertEquals(JsonNull, cleared["cAt"]!!.jsonObject["select"])
    }

    private fun page(id: String, props: String, inTrash: Boolean = false) = NotionJson.decodeFromString(
        PageDto.serializer(),
        """{"id":"$id","last_edited_time":"2026-10-19T00:00:00.000Z","in_trash":$inTrash,"properties":{$props}}""",
    )

    @Test
    fun readsBudgetRows() {
        val mapper = BudgetPageMapper(BudgetMapping("title", periodTypePropId = "tYp", periodValuePropId = "vAl", groupPropId = "gRp", amountPropId = "aMt"))
        val month = mapper.map(
            page(
                "b1",
                """"예산명":{"id":"title","type":"title","title":[{"plain_text":"2026-10 전체"}]},
                   "기간유형":{"id":"tYp","type":"select","select":{"name":"월"}},
                   "기간값":{"id":"vAl","type":"rich_text","rich_text":[{"plain_text":" 2026-10 "}]},
                   "그룹":{"id":"gRp","type":"select","select":{"name":"전체"}},
                   "예산금액":{"id":"aMt","type":"number","number":1200000}""",
            ),
        )
        assertEquals(RemoteBudget("b1", "월", "2026-10", "전체", 1_200_000), month)
        val empty = mapper.map(
            page("b2", """"기간유형":{"id":"tYp","type":"select","select":{"name":"주"}},"기간값":{"id":"vAl","type":"rich_text","rich_text":[{"plain_text":"2026-W43"}]},"예산금액":{"id":"aMt","type":"number","number":null}"""),
        )
        assertEquals(RemoteBudget("b2", "주", "2026-W43", null, null), empty)
        // 기간유형·기간값이 없는 행은 쓸 수 없어 건너뛴다. 휴지통 행도.
        assertNull(mapper.map(page("b3", """"예산금액":{"id":"aMt","type":"number","number":5}""")))
        assertNull(mapper.map(page("b4", """"기간유형":{"id":"tYp","type":"select","select":{"name":"월"}},"기간값":{"id":"vAl","type":"rich_text","rich_text":[{"plain_text":"2026-10"}]}""", inTrash = true)))
    }

    @Test
    fun readsCategoryRows() {
        val mapper = CategoryPageMapper(CategoryMapping("title", groupPropId = "gRp"))
        assertEquals(
            RemoteCategory("c1", "식비", "생활비"),
            mapper.map(page("c1", """"대분류":{"id":"title","type":"title","title":[{"plain_text":"식비"}]},"그룹":{"id":"gRp","type":"select","select":{"name":"생활비"}}""")),
        )
        assertNull(mapper.map(page("c2", """"대분류":{"id":"title","type":"title","title":[]}"""))) // 이름 없는 행
    }
}
