package com.ysdoing.notioncal.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.layout.size
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.view.chart.ChartPoint
import kotlin.math.min

// Phase 12b 차트 도넛·꺾은선. 위젯의 그림 금지(PF-07)에 **이 파일 하나만** 두 번째 예외를 둔다(PRD PF-07 개정, 사용자 승인 2026-11-05: 차트는 허용).
// WidgetNoBitmapTest가 다른 모든 위젯 소스(책 표지 파일 제외)에서 그림 API를 계속 막고, 크기 예산은 ViewChartGeometry의 ChartBudget이 정해 ViewChartGeometryTest가 지킨다.
// 글자는 그림에 그리지 않는다: 값·이름·범례는 Glance 글자로 그림 위나 옆에 둔다. 위젯 구성은 메인 스레드가 아니고 여기엔 입출력이 없다(그리기만).

private const val RING_RATIO = 0.2f
private const val GAP_DEGREES = 1.5f
private const val EMPTY_RING = 0x33808080
private const val FILL_ALPHA = 0x33
private const val AXIS_ALPHA = 0x40
private const val AXIS_THIN = 0.4f
private const val MAX_DOTS = 24

/** 도넛 그림: [points]의 값에 비례한 고리. 값이 없으면 연한 빈 고리. 한 변이 [side]인 정사각형이다. */
@Composable
internal fun DonutPicture(points: List<ChartPoint>, side: Dp) {
    val (w, h) = pictureSize(LocalContext.current, side, side)
    val picture = remember(points, w, h) { drawDonut(points, w, h) }
    Image(provider = ImageProvider(picture), contentDescription = null, modifier = GlanceModifier.size(side).semantics { testTag = "chartDonut" })
}

/** 꺾은선 그림: [values]를 [colorHex] 선과 연한 영역으로. [width]×[height]를 꽉 채운다. */
@Composable
internal fun LinePicture(values: List<Double>, colorHex: String, width: Dp, height: Dp) {
    val (w, h) = pictureSize(LocalContext.current, width, height)
    val picture = remember(values, colorHex, w, h) { drawLine(values, colorHex, w, h) }
    Image(provider = ImageProvider(picture), contentDescription = null, modifier = GlanceModifier.size(width, height).semantics { testTag = "chartLine" })
}

/** 그릴 크기(dp)를 화면 밀도로 키우되 한 장의 바이트 예산 안으로 줄인 픽셀 크기. */
private fun pictureSize(context: Context, width: Dp, height: Dp): Pair<Int, Int> {
    val metrics = context.resources.displayMetrics
    return ChartBudget.fit(width.value, height.value, metrics.density, ChartBudget.pictureBytes(metrics.widthPixels, metrics.heightPixels))
}

internal fun drawDonut(points: List<ChartPoint>, width: Int, height: Int): Bitmap {
    val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(picture)
    val stroke = min(width, height) * RING_RATIO
    val half = stroke / 2
    val bounds = RectF(half, half, width - half, height - half)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    val arcs = donutArcs(points)
    if (arcs.isEmpty()) {
        paint.color = EMPTY_RING
        canvas.drawArc(bounds, 0f, 360f, false, paint)
        return picture
    }
    val gap = if (arcs.size > 1) GAP_DEGREES else 0f
    arcs.forEach { arc ->
        paint.color = NotionColors.argb(arc.colorHex)
        canvas.drawArc(bounds, arc.start + gap / 2, (arc.sweep - gap).coerceAtLeast(0.5f), false, paint)
    }
    return picture
}

internal fun drawLine(values: List<Double>, colorHex: String, width: Int, height: Int): Bitmap {
    val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    if (values.isEmpty()) return picture
    val canvas = Canvas(picture)
    val stroke = (height * 0.03f).coerceIn(2f, 8f)
    val geometry = lineGeometry(values, width.toFloat(), height.toFloat(), inset = stroke * 2.5f)
    val color = NotionColors.argb(colorHex)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    // 값 0의 선(축): 데이터 선보다 훨씬 가늘고 연하게
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = (stroke * AXIS_THIN).coerceAtLeast(1f)
    paint.color = (AXIS_ALPHA shl 24) or (color and 0x00FFFFFF)
    canvas.drawLine(0f, geometry.baselineY, width.toFloat(), geometry.baselineY, paint)
    paint.strokeWidth = stroke
    val line = Path().apply { geometry.points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) } }
    if (geometry.points.size > 1) {
        val area = Path(line).apply {
            lineTo(geometry.points.last().first, geometry.baselineY)
            lineTo(geometry.points.first().first, geometry.baselineY)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = (FILL_ALPHA shl 24) or (color and 0x00FFFFFF)
        canvas.drawPath(area, paint)
    }
    paint.style = Paint.Style.STROKE
    paint.color = color
    canvas.drawPath(line, paint)
    if (geometry.points.size <= MAX_DOTS) {
        paint.style = Paint.Style.FILL
        geometry.points.forEach { (x, y) -> canvas.drawCircle(x, y, stroke * 1.3f, paint) }
    }
    return picture
}
