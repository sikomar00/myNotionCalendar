package com.ysdoing.notioncal.feature.month

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import java.util.concurrent.atomic.AtomicInteger

/**
 * S25-05: 월 스와이프 동안의 프레임을 세어, 스와이프가 끝날 때마다 느린 프레임 비율을 logcat에 남긴다.
 * `adb logcat -s NotionCalJank`로 모아 docs/metrics.md에 적는다. 목표는 5% 미만(§3).
 */
@Composable
internal fun MonthSwipeJankTracker(swiping: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity() ?: return
    val counter = remember { SwipeFrameCounter() }

    DisposableEffect(activity) {
        val stats = JankStats.createAndTrack(activity.window) { frame ->
            if (frame.states.any { it.key == STATE_KEY }) counter.add(frame.isJank)
        }
        onDispose { stats.isTrackingEnabled = false }
    }

    LaunchedEffect(swiping) {
        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
        if (swiping) {
            holder.state?.putState(STATE_KEY, STATE_VALUE)
        } else {
            holder.state?.removeState(STATE_KEY)
            counter.drain()?.let { (frames, janky) ->
                Log.d(TAG, "월 스와이프: 프레임 $frames, 느린 프레임 $janky (${"%.1f".format(janky * 100.0 / frames)}%)")
            }
        }
    }
}

private class SwipeFrameCounter {
    private val frames = AtomicInteger()
    private val janky = AtomicInteger()

    fun add(isJank: Boolean) {
        frames.incrementAndGet()
        if (isJank) janky.incrementAndGet()
    }

    /** 이번 스와이프 집계를 꺼내고 비운다. 센 프레임이 없으면 null. */
    fun drain(): Pair<Int, Int>? {
        val f = frames.getAndSet(0)
        val j = janky.getAndSet(0)
        return if (f == 0) null else f to j
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val TAG = "NotionCalJank"
private const val STATE_KEY = "MonthSwipe"
private const val STATE_VALUE = "swiping"
