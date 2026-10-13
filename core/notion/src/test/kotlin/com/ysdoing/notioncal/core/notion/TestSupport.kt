package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.http.NanoClock
import com.ysdoing.notioncal.core.notion.http.Sleeper

fun fixture(name: String): String =
    requireNotNull(TestSupport::class.java.classLoader?.getResource("fixtures/$name")) { "fixture 없음: $name" }.readText()

private object TestSupport

/** sleep을 기록만 하고 가짜 시계를 그만큼 앞당긴다. */
class FakeTime : Sleeper, NanoClock {
    var nanos = 0L
    val sleeps = mutableListOf<Long>()

    override fun sleep(millis: Long) {
        sleeps += millis
        nanos += millis * 1_000_000
    }

    override fun nanoTime(): Long = nanos
}
