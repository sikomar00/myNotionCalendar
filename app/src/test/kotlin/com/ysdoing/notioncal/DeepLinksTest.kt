package com.ysdoing.notioncal

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.AppIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** EX-12 바로 여는 링크: 다섯 모양만 화면을 열고, 그 밖의 링크·앱 안 인텐트 data URI는 무시한다. */
@RunWith(RobolectricTestRunner::class)
class DeepLinksTest {
    @Test
    fun parsesFiveLinks() {
        assertEquals(DeepLink.Today, DeepLinks.parse("notioncal://today"))
        assertEquals(DeepLink.Habit, DeepLinks.parse("notioncal://habit"))
        assertEquals(DeepLink.Ledger, DeepLinks.parse("notioncal://ledger"))
        assertEquals(DeepLink.Dday, DeepLinks.parse("notioncal://dday"))
        assertEquals(DeepLink.Date(LocalDate.of(2026, 10, 20)), DeepLinks.parse("notioncal://date/2026-10-20"))
        assertEquals(DeepLink.Habit, DeepLinks.parse("NotionCal://HABIT/"))
    }

    @Test
    fun ignoresOtherShapes() {
        listOf(
            null, "", "https://today", "notioncal://unknown", "notioncal://date/2026-13-01", "notioncal://date",
            "notioncal://today?x=1", "notioncal://habit#a",
            // 앱 안 인텐트가 쓰는 data URI(다른 action)도 링크로 해석하지 않는다.
            "notioncal://tab/ledger", "notioncal://ledger/add/2026-10-19", "notioncal://add/today", "notioncal://done/p1",
            "notioncal://edit/2026-10-19/p1", "notioncal://day/2026-10-19",
        ).forEach { assertNull(it, DeepLinks.parse(it)) }
    }

    /** 매니페스트: VIEW + notioncal 링크는 MainActivity가 받고, 퀵 시트 action은 그대로 퀵 시트로 간다. */
    @Test
    fun manifestRoutesViewLinksToMainActivity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("notioncal://dday")).addCategory(Intent.CATEGORY_DEFAULT)
        val viewTargets = pm.queryIntentActivities(view, 0).map { it.activityInfo.name }
        assertEquals(listOf(MainActivity::class.java.name), viewTargets)

        val sheet = Intent(AppIntents.ACTION_QUICK_SHEET, Uri.parse("notioncal://day/2026-10-19")).setPackage(context.packageName)
        val sheetTargets = pm.queryIntentActivities(sheet, 0).map { it.activityInfo.name }
        assertTrue(sheetTargets.toString(), MainActivity::class.java.name !in sheetTargets)
    }
}
