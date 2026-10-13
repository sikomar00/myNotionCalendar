package com.ysdoing.notioncal.core.notion.http

/** 테스트에서 실제로 기다리지 않도록 대기·시계를 주입한다. */
fun interface Sleeper {
    fun sleep(millis: Long)

    companion object {
        val System = Sleeper { if (it > 0) Thread.sleep(it) }
    }
}

fun interface NanoClock {
    fun nanoTime(): Long

    companion object {
        val System = NanoClock { java.lang.System.nanoTime() }
    }
}
