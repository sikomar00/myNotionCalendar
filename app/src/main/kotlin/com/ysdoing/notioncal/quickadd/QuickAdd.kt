package com.ysdoing.notioncal.quickadd

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.AppIntents

/** EX-06 '빠른 추가' 진입점(빠른 설정 타일·앱 아이콘 길게 누르기)이 함께 쓰는 인텐트. 오늘 날짜로 추가 시트가 열린다. */
object QuickAdd {
    const val SHORTCUT_ID = "quick_add"

    /** 날짜를 넣지 않으면 퀵 시트가 여는 순간의 오늘을 쓴다(단축키가 만든 날짜에 묶이지 않음). */
    fun intent(context: Context): Intent = Intent(AppIntents.ACTION_QUICK_SHEET)
        .setPackage(context.packageName)
        .setData("notioncal://add/today".toUri())
        .putExtra(AppIntents.EXTRA_MODE, AppIntents.MODE_ADD)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /**
     * 앱 아이콘 길게 누르기 메뉴의 '빠른 추가'. 정적 단축키(XML)는 대상 패키지를 글자로 적어야 해서
     * 디버그 빌드(applicationId 접미사)와 맞지 않으므로 앱 시작 때 동적 단축키로 등록한다(같은 ID면 덮어씀).
     */
    fun publishShortcut(context: Context) {
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.quick_add_short))
            .setLongLabel(context.getString(R.string.quick_add_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_add))
            .setIntent(intent(context))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }
}

/** EX-06 빠른 설정 타일 '노션 일정 추가'. 누르면 패널을 접고 퀵 시트를 연다. 잠금 화면에서는 먼저 잠금을 푼다. */
class QuickAddTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.quick_add_tile)
            icon = Icon.createWithResource(this@QuickAddTileService, R.drawable.ic_stat_event)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val open = Runnable {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, QuickAdd.intent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                openBeforeU()
            }
        }
        if (isLocked) unlockAndRun(open) else open.run()
    }

    /**
     * Android 12~13(minSdk 31)에는 PendingIntent 판이 없다. Intent 판은 Android 14 이상에서만 예외를 던지므로,
     * SDK 확인으로 14 미만에서만 부른다.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openBeforeU() = startActivityAndCollapse(QuickAdd.intent(this))
}
