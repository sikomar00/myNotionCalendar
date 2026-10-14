package com.ysdoing.notioncal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PF-07·S25-02: Android 17(타깃 37)은 위젯 비트맵이 '화면 폭×높이×4바이트×1.5'를 넘으면 앱을 죽인다.
 * 위젯은 비트맵 없이 레이아웃으로만 그린다. 이 규칙이 깨지지 않게 위젯 소스에서 비트맵 API를 찾는다.
 */
class WidgetNoBitmapTest {
    private val forbidden = listOf("Bitmap", "ImageProvider(", "setImageViewBitmap", "Canvas(", "toBitmap")

    @Test
    fun widgetSourcesUseNoBitmaps() {
        val sources = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("위젯 소스를 찾지 못했습니다(작업 디렉터리 확인)", sources.isNotEmpty())
        val hits = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                .filter { (_, line) -> forbidden.any { it in line } }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }
        assertTrue("위젯에서 비트맵 API 사용: $hits", hits.isEmpty())
    }
}
