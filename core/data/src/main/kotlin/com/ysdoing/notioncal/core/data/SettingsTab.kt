package com.ysdoing.notioncal.core.data

/**
 * Phase 11f ST-01 설정 카테고리. [id]는 바로가기 인자(`settings?tab=<id>`)와 마지막으로 본 카테고리 저장에 쓴다.
 * 월 화면 같은 기능 모듈이 :app의 설정 화면을 몰라도 어느 카테고리로 열지 말할 수 있게 여기 둔다.
 */
enum class SettingsTab(val id: String) {
    CONNECTION("connection"),
    DATABASE("database"),
    DISPLAY("display"),
    NOTIFICATIONS("notifications"),
    SYNC("sync"),
    ABOUT("about"),
    WIDGETS("widgets"),
    ;

    companion object {
        fun fromId(id: String?): SettingsTab? = entries.firstOrNull { it.id == id }
    }
}
