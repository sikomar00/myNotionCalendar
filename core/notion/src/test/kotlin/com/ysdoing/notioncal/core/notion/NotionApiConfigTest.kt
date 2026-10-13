package com.ysdoing.notioncal.core.notion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotionApiConfigTest {
    @Test
    fun notionVersionMatchesPrd() {
        assertEquals("2026-03-11", NotionApiConfig.NOTION_VERSION)
    }

    @Test
    fun baseUrlIsHttps() {
        assertTrue(NotionApiConfig.BASE_URL.startsWith("https://"))
    }
}
