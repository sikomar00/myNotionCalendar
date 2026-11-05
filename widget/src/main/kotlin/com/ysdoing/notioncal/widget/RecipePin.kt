package com.ysdoing.notioncal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.ysdoing.notioncal.core.data.recipe.RecipeClaims
import com.ysdoing.notioncal.core.data.recipe.RecipeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Phase 11h RC-01 '홈 화면에 추가': 레시피로 만든 구성을 보관하고 노션 위젯([LayoutWidgetReceiver])을 홈 화면에 놓아 달라고(핀) 런처에 요청한다.
 * 위젯 번호는 놓인 뒤에 정해지니 구성은 대기 레시피로 두었다가, 핀 콜백([RecipePinReceiver])이나 그 위젯의 설정 화면이 열릴 때 위젯 설정이 된다.
 */
object RecipePin {
    enum class Result { REQUESTED, UNSUPPORTED }

    /** 구성을 대기 레시피로 보관하고 핀을 요청한다. 런처가 지원하지 않거나 요청을 받지 않으면 UNSUPPORTED다(보관한 대기는 5분 뒤 저절로 만료된다). */
    suspend fun request(context: Context, claims: RecipeClaims, config: RecipeConfig, now: Long = System.currentTimeMillis()): Result {
        val manager = AppWidgetManager.getInstance(context)
        // 지원하지 않는 런처에서도 구성은 보관한다: 사용자가 홈 화면을 길게 눌러 노션 위젯을 놓으면 5분 안에는 그 구성으로 시작한다.
        claims.request(config, now)
        if (!manager.isRequestPinAppWidgetSupported) return Result.UNSUPPORTED
        val provider = ComponentName(context, LayoutWidgetReceiver::class.java)
        return if (manager.requestPinAppWidget(provider, null, callback(context))) Result.REQUESTED else Result.UNSUPPORTED
    }

    /** 핀 성공 알림을 받을 PendingIntent. 시스템이 위젯 번호 extras를 덧붙이므로 MUTABLE이고, 같은 앱의 수신기를 명시한다(암묵적 인텐트는 막힌다). */
    internal fun callback(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, RecipePinReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private const val REQUEST_CODE = 0x11A
}

/** 핀 성공 콜백: 방금 놓인 위젯([AppWidgetManager.EXTRA_APPWIDGET_ID])에 대기 레시피 구성을 쓰고 다시 그린다. */
class RecipePinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val applied = WidgetDependencies.from(context).recipeClaims().applyToNew(appWidgetId, System.currentTimeMillis())
                if (applied != null) LayoutWidget().updateAll(context) // 설정 화면보다 먼저 그려진 빈 위젯을 레시피 구성으로 바꾼다
            } finally {
                pending.finish()
            }
        }
    }
}
