package com.ysdoing.notioncal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * VW-07 노션 보기 기능은 읽기 전용이다. 보기 위젯·저장소·동기화·등록·API 읽기 코드가 노션에 쓰는 길(아웃박스·쓰기 API)을
 * 건드리지 않는지 소스에서 찾고, 한계 안내 문구가 설정 화면 문자열에 있는지 확인한다(VW-08 쓰기를 넣을 때 이 시험을 고친다).
 */
class ViewReadOnlyTest {
    private val forbidden = listOf("Outbox", "EventWriter", "createPage", "updatePage", "trashPage")

    private val viewSources = listOf(
        "src/main/kotlin/com/ysdoing/notioncal/widget/ViewWidget.kt",
        "../core/data/src/main/kotlin/com/ysdoing/notioncal/core/data/view/ViewRepository.kt",
        "../core/sync/src/main/kotlin/com/ysdoing/notioncal/core/sync/ViewSync.kt",
        "../core/sync/src/main/kotlin/com/ysdoing/notioncal/core/sync/ViewRegistration.kt",
        "../core/notion/src/main/kotlin/com/ysdoing/notioncal/core/notion/ViewReader.kt",
    ).map(::File)

    @Test
    fun viewCodeNeverWritesToNotion() {
        viewSources.forEach { assertTrue("보기 소스를 찾지 못했습니다(작업 디렉터리 확인): $it", it.isFile) }
        val hits = viewSources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                .filter { (_, line) -> forbidden.any { it in line } }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }
        assertTrue("노션 보기 코드에서 쓰기 경로 사용: $hits", hits.isEmpty())
    }

    @Test
    fun limitsAreSpelledOutOnTheAddViewScreen() {
        val strings = File("../app/src/main/res/values/strings.xml")
        assertTrue("앱 문자열을 찾지 못했습니다: $strings", strings.isFile)
        val limits = Regex("""<string name="add_view_limits">([^<]*)</string>""").find(strings.readText())?.groupValues?.get(1).orEmpty()
        listOf("읽기 전용", "50개", "갤러리 커버", "보드 열 배치").forEach {
            assertTrue("한계 안내에 '$it'이(가) 없습니다: $limits", it in limits)
        }
    }
}
