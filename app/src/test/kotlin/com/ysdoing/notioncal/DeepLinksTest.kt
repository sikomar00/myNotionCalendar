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
    fun parsesTheViewerLinkTheWidgetMoreRowOpens() {
        // Phase 11a VR-02: 위젯의 '+N 더 보기'가 연다. 보기 ID 하나만 받는다.
        assertEquals(DeepLink.View("1c2d3e4f-0000-4000-8000-0123456789ab"), DeepLinks.parse("notioncal://view/1c2d3e4f-0000-4000-8000-0123456789ab"))
        listOf("notioncal://view", "notioncal://view/", "notioncal://view/a/b", "notioncal://view/add/v1", "notioncal://view/v1?x=1")
            .forEach { assertNull(it, DeepLinks.parse(it)) } // 'view/add/<id>'는 새 항목 시트 인텐트가 쓰는 data URI다
        // 내보낸 액티비티라 아무 앱·웹이 보낼 수 있다. 경로 문자가 섞인 ID는 내비게이션 경로를 깨므로 거른다.
        listOf("notioncal://view/%3Fx", "notioncal://view/%23", "notioncal://view/a%20b", "notioncal://view/%7Bid%7D").forEach { assertNull(it, DeepLinks.parse(it)) }
    }

    @Test
    fun ignoresOtherShapes() {
        listOf(
            null, "", "https://today", "notioncal://unknown", "notioncal://date/2026-13-01", "notioncal://date",
            "notioncal://today?x=1", "notioncal://habit#a",
            // 앱 안 인텐트가 쓰는 data URI(다른 action)도 링크로 해석하지 않는다.
            "notioncal://tab/ledger", "notioncal://tab/ledger/2026-10-19", "notioncal://ledger/add/2026-10-19", "notioncal://add/today", "notioncal://done/p1",
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
