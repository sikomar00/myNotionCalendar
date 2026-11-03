package com.ysdoing.notioncal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PF-07·S25-02: Android 17(타깃 37)은 위젯 비트맵이 '화면 폭×높이×4바이트×1.5'를 넘으면 앱을 죽인다.
 * 위젯은 비트맵 없이 레이아웃으로만 그린다. 이 규칙이 깨지지 않게 위젯 소스에서 비트맵 API를 찾는다.
 * 예외 1: 벡터 리소스 아이콘 `ImageProvider(icon)`(@DrawableRes Int, [WidgetIcon])은 런처가 리소스로 그려 비트맵이 오가지 않는다.
 * 예외 2(M5a, 사용자 승인 2026-11-03): 책 표지는 `WidgetCover.kt` **한 파일**에서만 비트맵을 쓴다. 크기 예산은 WidgetCoverTest가 지킨다.
 */
class WidgetNoBitmapTest {
    private val forbidden = listOf("Bitmap", "ImageProvider(", "setImageViewBitmap", "Canvas(", "toBitmap", "Icon.create")

    /** 허용하는 한 줄: 리소스 ID로 만드는 아이콘(WidgetIcon). */
    private val allowed = Regex("""provider = ImageProvider\(icon\),""")

    private companion object {
        /** 비트맵을 써도 되는 유일한 위젯 소스(책 표지). */
        const val COVER_FILE = "WidgetCover.kt"
    }

    @Test
    fun theCoverFileIsTheOnlyExceptionAndStillExists() {
        val all = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.name }.toList()
        assertTrue("표지 예외 파일이 없다: 시험이 아무 파일도 걸러 내지 않는지 확인", COVER_FILE in all)
        // 예외 파일 이름이 늘어나지 않았는지: 비트맵 API를 쓰는 파일은 하나뿐이어야 한다.
        val users = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("android.graphics.Bitmap") }.map { it.name }.toList()
        assertTrue("비트맵을 가져오는 파일: $users", users == listOf(COVER_FILE))
    }

    @Test
    fun widgetSourcesUseNoBitmaps() {
        val sources = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != COVER_FILE }.toList()
        assertTrue("위젯 소스를 찾지 못했습니다(작업 디렉터리 확인)", sources.isNotEmpty())
        val hits = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                .filter { (_, line) -> forbidden.any { it in line } && !allowed.containsMatchIn(line.trim()) }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }
        assertTrue("위젯에서 비트맵 API 사용: $hits", hits.isEmpty())
    }
}
