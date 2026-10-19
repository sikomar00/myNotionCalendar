package com.ysdoing.notioncal.core.data

/** DD-01: 노션에서 휴지통으로 가 고정을 푼 디데이 제목을 남긴다(디데이 화면 안내). 동기화 모듈이 부른다. */
fun interface DdayRemovedNotice {
    suspend fun add(titles: List<String>)

    companion object {
        val None = DdayRemovedNotice { }
    }
}
