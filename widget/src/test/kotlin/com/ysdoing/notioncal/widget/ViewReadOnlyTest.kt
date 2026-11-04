package com.ysdoing.notioncal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * VW-07·08 보기 위젯은 노션 API를 직접 부르지 않는다. 쓰기는 ViewWriter가 보기 전용 큐(view_writes)에 쌓을 뿐이고 보내기는
 * 동기화 쪽 처리기가 한다. 보기 위젯·저장소·동기화·등록·API 읽기 코드가 일정 아웃박스와 쓰기 API를 건드리지 않는지 소스에서 찾고,
 * 한계 안내 문구가 설정 화면 문자열에 있는지 확인한다.
 */
class ViewReadOnlyTest {
    private val forbidden = listOf("Outbox", "EventWriter", "createPage", "updatePage", "trashPage")

    private val viewSources = listOf(
        "src/main/kotlin/com/ysdoing/notioncal/widget/ViewWidget.kt",
        "src/main/kotlin/com/ysdoing/notioncal/widget/ViewCellAction.kt",
        "src/main/kotlin/com/ysdoing/notioncal/widget/LayoutWidget.kt", // Phase 11d 노션 위젯도 같은 보기 행을 그린다
        "src/main/kotlin/com/ysdoing/notioncal/widget/LayoutTabStrip.kt",
        "src/main/kotlin/com/ysdoing/notioncal/widget/LayoutSections.kt",
        "src/main/kotlin/com/ysdoing/notioncal/widget/WidgetLayoutSettings.kt",
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
        listOf("체크박스", "상태", "새 항목", "100개", "더 보기", "갤러리 커버", "책 갤러리").forEach {
            assertTrue("한계 안내에 '$it'이(가) 없습니다: $limits", it in limits)
        }
    }
}
