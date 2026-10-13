package com.ysdoing.notioncal.core.notion.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotionLinkParserTest {
    private val expected = "30965413-2dbd-80bb-b759-cf1f4240d184"

    @Test
    fun appNotionComLink() {
        assertEquals(expected, NotionLinkParser.databaseId("https://app.notion.com/p/309654132dbd80bbb759cf1f4240d184"))
    }

    @Test
    fun notionSoLinkWithTitleAndViewQuery() {
        assertEquals(
            expected,
            NotionLinkParser.databaseId("https://www.notion.so/myspace/Calendar-309654132dbd80bbb759cf1f4240d184?v=30965413a2dbd8021b184000c24b15e6&pvs=4"),
        )
    }

    @Test
    fun hyphenatedUuidAndUppercase() {
        assertEquals(expected, NotionLinkParser.databaseId("30965413-2DBD-80BB-B759-CF1F4240D184"))
    }

    @Test
    fun bareCompactIdWithWhitespace() {
        assertEquals(expected, NotionLinkParser.databaseId("  309654132dbd80bbb759cf1f4240d184\n"))
    }

    @Test
    fun invalidInput() {
        assertNull(NotionLinkParser.databaseId("https://app.notion.com/p/not-an-id"))
        assertNull(NotionLinkParser.databaseId(""))
    }
}
