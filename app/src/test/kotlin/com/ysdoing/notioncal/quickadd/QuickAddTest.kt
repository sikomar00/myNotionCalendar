package com.ysdoing.notioncal.quickadd

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.feature.quicksheet.QuickSheetActivity
import com.ysdoing.notioncal.feature.quicksheet.QuickSheetStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** EX-06: 타일·앱 단축키가 같은 '오늘 추가' 인텐트를 쓰고, 단축키가 등록된다. */
@RunWith(RobolectricTestRunner::class)
class QuickAddTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun intentOpensAddSheetForToday() {
        val intent = QuickAdd.intent(context)
        assertEquals(AppIntents.ACTION_QUICK_SHEET, intent.action)
        assertEquals(context.packageName, intent.`package`)
        val start = QuickSheetActivity.startFrom(intent.getStringExtra(AppIntents.EXTRA_MODE), intent.getStringExtra(AppIntents.EXTRA_DATE), null)
        // 날짜를 넣지 않으므로 여는 순간의 오늘로 추가 시트가 열린다.
        assertEquals(QuickSheetStart.Add(LocalDate.now(APP_ZONE)), start)
    }

    @Test
    fun shortcutIsPublished() {
        QuickAdd.publishShortcut(context)
        val shortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertTrue(shortcuts.any { it.id == QuickAdd.SHORTCUT_ID && it.intent.action == AppIntents.ACTION_QUICK_SHEET })
        // 다시 불러도 하나만 남는다.
        QuickAdd.publishShortcut(context)
        assertEquals(1, ShortcutManagerCompat.getDynamicShortcuts(context).count { it.id == QuickAdd.SHORTCUT_ID })
    }
}
