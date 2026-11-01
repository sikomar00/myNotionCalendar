package com.ysdoing.notioncal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/** SEC-03·SEC-05: 토큰 파일과 Room 캐시는 자동 백업·기기 이전 어느 쪽에도 들어가지 않는다. */
@RunWith(RobolectricTestRunner::class)
class BackupRulesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val database = listOf("notioncal.db", "notioncal.db-wal", "notioncal.db-shm", "notioncal.db-journal").map { "database" to it }
    private val secrets = listOf("file" to "datastore/secrets.preferences_pb", "sharedpref" to "notioncal_keyset_prefs.xml")

    /** (부모 태그, domain, path) 목록. */
    private fun excludes(xml: Int): List<Triple<String?, String, String>> {
        val parser = context.resources.getXml(xml)
        val result = mutableListOf<Triple<String?, String, String>>()
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "exclude" -> result += Triple(section, parser.getAttributeValue(null, "domain"), parser.getAttributeValue(null, "path"))
            }
        }
        return result
    }

    @Test
    fun extractionRulesExcludeTokenAndDatabaseInBothSections() {
        val rules = excludes(R.xml.data_extraction_rules)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val paths = rules.filter { it.first == section }.map { it.second to it.third }.toSet()
            assertEquals(section, (secrets + database).toSet(), paths)
        }
    }

    @Test
    fun legacyBackupRulesExcludeTokenAndDatabase() {
        val paths = excludes(R.xml.backup_rules).map { it.second to it.third }.toSet()
        assertEquals((secrets + database).toSet(), paths)
    }
}
