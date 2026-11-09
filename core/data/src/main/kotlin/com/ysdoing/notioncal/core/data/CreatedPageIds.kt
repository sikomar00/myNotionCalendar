package com.ysdoing.notioncal.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 새 일정의 임시 id([EventWriter.TEMP_PREFIX]) → 노션이 만든 진짜 id. 생성을 보내면 로컬 일정의 id가 바뀌는데, 그 전에 연 편집 시트·끌어 옮기기·
 * 알림의 '완료'는 임시 id를 들고 있어 바뀐 뒤 쓰면 조용히 사라졌다. 쓰는 쪽([EventWriter])과 편집 시트가 이 별칭으로 풀어 쓴다.
 * shortcut: 메모리에만 둔다(앱이 다시 시작되면 잊는다). 다시 시작 뒤에는 화면이 이미 새로 읽은 진짜 id를 쓰고, 남는 것은 그 전에 뜬 알림의 '완료'뿐이다.
 */
object CreatedPageIds {
    private val aliases = MutableStateFlow<Map<String, String>>(emptyMap())

    fun record(tempId: String, realId: String) = aliases.update { it + (tempId to realId) }

    fun forget(tempId: String) = aliases.update { it - tempId }

    fun resolve(pageId: String): String = aliases.value[pageId] ?: pageId

    fun observe(pageId: String): Flow<String> = aliases.map { it[pageId] ?: pageId }.distinctUntilChanged()
}
