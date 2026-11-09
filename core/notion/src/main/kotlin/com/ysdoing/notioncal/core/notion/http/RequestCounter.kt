package com.ysdoing.notioncal.core.notion.http

import okhttp3.Interceptor
import java.util.concurrent.atomic.AtomicLong

/**
 * PF-11 노션으로 나간 요청 수(재시도 포함, 시도마다 하나). 속도 제한(분당 180회)도 시도마다 소비되므로 같은 단위다.
 * 한 주기의 요청 수는 시작과 끝의 [total] 차이로 잰다(동시에 도는 다른 작업의 요청도 섞일 수 있다).
 */
class RequestCounter {
    private val count = AtomicLong()

    val total: Long get() = count.get()

    /** 요청 하나를 센다. 인터셉터가 부르고, HTTP를 거치지 않는 가짜 노션을 쓰는 시험도 직접 부른다. */
    fun hit() {
        count.incrementAndGet()
    }

    val interceptor: Interceptor = Interceptor { chain ->
        hit()
        chain.proceed(chain.request())
    }
}
