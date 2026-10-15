package com.ysdoing.notioncal.core.data

/**
 * 앱·퀵 시트·위젯·위젯 설정 화면이 같이 쓰는 화면 틀 색(ARGB). "노션 문서처럼 조용한 바탕 위에 일정 색만" 원칙으로,
 * 바탕·글자·선은 노션 문서 색, 강조는 앱 아이콘의 파랑 하나만 쓴다. 일정 색은 [NotionColors].
 *
 * 대비(WCAG 2.1 AA): 먹 12.3/14.1, 흑연 5.1/6.3(시트 배경 위 4.8/5.8), 강조 글자 5.1(대화상자 배경 위 4.5)/6.6,
 * 강조 위 글자 5.1/6.6, 테두리 3.5/3.4(입력칸 테두리 3:1 이상). 앞이 라이트, 뒤가 다크.
 * 이 모듈은 Compose를 쓰지 않아 숫자로 둔다. 각 화면 모듈이 Color(…)로 바꿔 쓴다.
 */
object DesignTokens {
    object Light {
        /** 종이(노션 페이지). */
        const val PAPER = 0xFFFFFFFF
        /** 먹(본문 글자, 노션 글자색). */
        const val INK = 0xFF37352F
        /** 흑연(보조 글자). */
        const val GRAPHITE = 0xFF6F6E69
        /** 입력칸·버튼 테두리. */
        const val OUTLINE = 0xFF8A8984
        /** 나눔선(노션 구분선). */
        const val RULE = 0xFFE9E9E7
        /** 강조 파랑(아이콘 #2383E2를 대비 4.5 이상으로). */
        const val ACCENT = 0xFF1A6DCC
        const val ON_ACCENT = 0xFFFFFFFF
        /** 고른 칩·버튼 바탕(강조 파랑을 옅게). */
        const val SELECTED = 0xFFE1EDFA
        /** 시트·대화상자 등 한 단계 위 면(노션 사이드바·팝업 회색). */
        const val SURFACE_LOW = 0xFFFBFBFA
        const val SURFACE = 0xFFF7F7F5
        const val SURFACE_HIGH = 0xFFF1F1EF
        const val SURFACE_HIGHEST = 0xFFEBEBEA
        const val ERROR = 0xFFC62828
    }

    object Dark {
        /** 밤(노션 다크 페이지). */
        const val PAPER = 0xFF191919
        const val INK = 0xFFE6E6E4
        const val GRAPHITE = 0xFF9B9A97
        const val OUTLINE = 0xFF6F6E69
        const val RULE = 0xFF2F2F2F
        const val ACCENT = 0xFF5AA2F0
        /** 밝은 강조 위에는 밤색 글자(흰 글자는 2.7:1). */
        const val ON_ACCENT = 0xFF191919
        const val SELECTED = 0xFF233447
        const val SURFACE_LOW = 0xFF1E1E1E
        const val SURFACE = 0xFF202020
        const val SURFACE_HIGH = 0xFF252525
        const val SURFACE_HIGHEST = 0xFF2F2F2F
        const val ERROR = 0xFFFF6B6B
    }
}
