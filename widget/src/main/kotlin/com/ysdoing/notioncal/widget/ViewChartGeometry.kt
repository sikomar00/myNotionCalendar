package com.ysdoing.notioncal.widget

import com.ysdoing.notioncal.core.data.view.chart.ChartPoint
import kotlin.math.sqrt

// Phase 12b VR-CHT: 도넛·꺾은선 그림의 기하(각도·좌표)와 그림 한 장의 크기 예산을 계산하는 순수 함수. 그리기는 WidgetChartBitmap.kt가 맡는다.
// (이 파일은 그림 API를 쓰지 않는다: WidgetNoBitmapTest가 그림 예외 파일 밖의 위젯 소스에서 그런 이름을 막는다.)

/** 도넛 조각 하나: 위쪽(12시)에서 시계 방향으로 [start]도부터 [sweep]도, 색은 [colorHex]. */
internal data class DonutArc(val start: Float, val sweep: Float, val colorHex: String)

/**
 * 점들의 값에 비례한 도넛 조각. 12시에서 시작해 시계 방향이고, 음수·NaN은 0으로 본다. 값이 0인 조각은 그리지 않는다.
 * 조각 각도의 합은 정확히 360도다(마지막 조각이 반올림 오차를 받는다). 그릴 값이 하나도 없으면 빈 목록이다.
 */
internal fun donutArcs(points: List<ChartPoint>): List<DonutArc> {
    val values = points.map { it.value.takeIf { v -> v.isFinite() && v > 0 } ?: 0.0 }
    val sum = values.sum()
    if (sum <= 0) return emptyList()
    val drawn = points.indices.filter { values[it] > 0 }
    var start = START_ANGLE
    return drawn.mapIndexed { n, i ->
        val sweep = if (n == drawn.lastIndex) START_ANGLE + FULL_TURN - start else (values[i] / sum * FULL_TURN).toFloat()
        DonutArc(start, sweep, points[i].colorHex).also { start += sweep }
    }
}

private const val START_ANGLE = -90f
private const val FULL_TURN = 360f

/** 꺾은선의 점 좌표(그림 안 픽셀)와 값 0의 높이 [baselineY]. */
internal data class LineGeometry(val points: List<Pair<Float, Float>>, val baselineY: Float)

/**
 * [values]를 [width]×[height] 안의 꺾은선 좌표로: x는 [inset]부터 [width] - [inset]까지 고르게(점이 하나면 가운데), y는 0(음수가 있으면 최솟값)이 아래,
 * 최댓값(모두 음수면 0)이 위다. 값이 모두 0이면 가운데 높이다(축은 아래). NaN·무한대 값은 점을 찍지 않고 건너뛴다(선은 이웃 점을 잇는다, 자리는 그대로).
 */
internal fun lineGeometry(values: List<Double>, width: Float, height: Float, inset: Float): LineGeometry {
    val finite = values.filter { it.isFinite() }
    val low = minOf(0.0, finite.minOrNull() ?: 0.0)
    val high = maxOf(0.0, finite.maxOrNull() ?: 0.0)
    val top = inset
    val bottom = height - inset
    fun y(v: Double) = if (high == low) (top + bottom) / 2 else (bottom - (v - low) / (high - low) * (bottom - top)).toFloat()
    val span = width - inset * 2
    val points = values.mapIndexedNotNull { i, v ->
        if (!v.isFinite()) null else (if (values.size == 1) width / 2 else inset + span * i / (values.size - 1)) to y(v)
    }
    return LineGeometry(points, if (high == low) bottom else y(0.0))
}

/**
 * PF-07 위젯 그림 예산. 위젯 하나가 쓰는 그림 바이트의 합은 '화면 폭×높이×4바이트×1.5'(Android 17 한도)의 25% 이하여야 하고,
 * 차트 그림은 한 위젯에 [MAX_PICTURES]장까지 두니 한 장이 쓸 수 있는 바이트는 그 몫(그리고 [MAX_PICTURE_BYTES])이다.
 */
internal object ChartBudget {
    const val MAX_PICTURES = 4
    const val MAX_PICTURE_BYTES = 1_000_000L
    private const val BYTES_PER_PIXEL = 4L

    /** 위젯 하나가 그림에 쓸 수 있는 바이트(한도의 25%). */
    fun widgetBytes(screenWidth: Int, screenHeight: Int): Long = screenWidth.toLong() * screenHeight * BYTES_PER_PIXEL * 3 / 2 / 4

    /** 그림 한 장이 쓸 수 있는 바이트. */
    fun pictureBytes(screenWidth: Int, screenHeight: Int): Long = minOf(MAX_PICTURE_BYTES, widgetBytes(screenWidth, screenHeight) / MAX_PICTURES)

    /** [widthDp]×[heightDp]를 [density]로 키운 픽셀 크기를, 가로세로 비율을 지켜 [maxBytes] 안으로 줄인다(최소 1×1). */
    fun fit(widthDp: Float, heightDp: Float, density: Float, maxBytes: Long): Pair<Int, Int> {
        val w = (widthDp * density).coerceAtLeast(1f)
        val h = (heightDp * density).coerceAtLeast(1f)
        val bytes = w * h * BYTES_PER_PIXEL
        val scale = if (bytes > maxBytes) sqrt(maxBytes / bytes.toDouble()).toFloat() else 1f
        return maxOf(1, (w * scale).toInt()) to maxOf(1, (h * scale).toInt())
    }
}
