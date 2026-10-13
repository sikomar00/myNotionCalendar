package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.LineSpacing
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.calendar.PlacedEvent
import com.ysdoing.notioncal.core.data.calendar.WeekLayoutResult
import kotlin.math.floor

/** WS-03 크기 단계: 작음(점만) / 중간(제목 1줄 + N) / 큼(제목 여러 줄). */
enum class WidgetMode { SMALL, MEDIUM, LARGE }

fun widgetMode(size: DpSize): WidgetMode = when {
    size.width < 200.dp || size.height < 200.dp -> WidgetMode.SMALL
    size.width >= 300.dp && size.height >= 300.dp -> WidgetMode.LARGE
    else -> WidgetMode.MEDIUM
}

/** 위젯 크기·설정에서 계산한 치수. Glance에는 임의 위치 지정이 없어 막대 폭을 칸 폭의 배수로 그린다. */
data class WidgetMetrics(
    val mode: WidgetMode,
    val headerHeight: Dp,
    val weekdayHeight: Dp,
    val cellWidth: Dp,
    val weekHeight: Dp,
    val dateLabelHeight: Dp,
    val laneHeight: Dp,
    /** 칸당 일정 줄 수(마지막 줄은 넘칠 때 +N 자리). 1이면 날짜마다 "첫 일정 + N"을 한 줄에 그린다. */
    val lanes: Int,
    val chipFontSp: Float,
    val dateFontSp: Float,
    /** 작음 단계에서 점을 그릴 공간이 있는지. */
    val showDots: Boolean,
) {
    companion object {
        val PADDING = 6.dp
        private val HEADER_HEIGHT = 34.dp
        private val WEEKDAY_HEIGHT = 16.dp
        private val SMALL_HEADER_HEIGHT = 24.dp
        private val SMALL_WEEKDAY_HEIGHT = 11.dp
        private val DOT_SPACE = 6.dp
        private const val MIN_DOT_DATE_FONT = 6.5f
        const val MAX_LANES = 6

        /**
         * [systemFontScale]은 기기 글꼴 크기. sp 글자가 실제로 차지하는 높이를 dp로 어림해 줄이 잘리지 않게 한다.
         */
        fun of(size: DpSize, config: WidgetConfig, systemFontScale: Float): WidgetMetrics {
            val mode = widgetMode(size)
            val scale = config.fontScale / 100f
            val small = mode == WidgetMode.SMALL
            val chipFont = 10f * scale
            val spacing = when (config.lineSpacing) {
                LineSpacing.COMPACT -> 1.15f
                LineSpacing.NORMAL -> 1.35f
                LineSpacing.WIDE -> 1.6f
            }
            val header = if (small) SMALL_HEADER_HEIGHT else HEADER_HEIGHT
            val weekday = if (small) SMALL_WEEKDAY_HEIGHT else WEEKDAY_HEIGHT
            val contentWidth = size.width - PADDING * 2
            val weekHeight = (size.height - PADDING * 2 - header - weekday) / 6
            // 작음(2×2, 4×2)은 주 한 줄 높이가 10~18dp까지 줄어든다. 점 자리를 먼저 남기고 날짜 숫자를 맞춘다.
            // 그래도 숫자가 너무 작아지면(6.5sp 미만) 점을 포기하고 숫자만 겹치지 않게 그린다.
            val wantedDateFont = (if (small) 9f else 11f) * scale
            fun fontFor(height: Dp) = (height - 2.dp).value / 1.3f / systemFontScale
            val withDots = fontFor(weekHeight - DOT_SPACE)
            val showDots = !small || withDots >= MIN_DOT_DATE_FONT
            val dateFont = when {
                !small -> wantedDateFont
                showDots -> minOf(wantedDateFont, withDots)
                else -> minOf(wantedDateFont, fontFor(weekHeight).coerceAtLeast(5f))
            }
            val dateLabel = (dateFont * systemFontScale * 1.3f).dp + 2.dp
            val lane = (chipFont * systemFontScale * spacing).dp + 1.dp
            val fit = floor((weekHeight - dateLabel) / lane).toInt().coerceIn(1, MAX_LANES)
            val lanes = when {
                mode == WidgetMode.MEDIUM -> minOf(fit, 2)
                config.maxPerCell > 0 -> minOf(fit, config.maxPerCell)
                else -> fit
            }
            return WidgetMetrics(
                mode = mode,
                headerHeight = header,
                weekdayHeight = weekday,
                cellWidth = contentWidth / 7,
                weekHeight = weekHeight,
                dateLabelHeight = dateLabel,
                laneHeight = lane,
                lanes = lanes,
                chipFontSp = chipFont,
                dateFontSp = dateFont,
                showDots = small && showDots,
            )
        }
    }
}

/** 레인 한 줄을 이루는 셀. 일정 막대, +N, 빈칸 중 하나. 연속 빈칸은 하나로 합쳐 셀 수를 7개 이하로 유지한다. */
data class LaneSegment(val startCol: Int, val span: Int, val placed: PlacedEvent?, val hiddenCount: Int)

fun laneSegments(result: WeekLayoutResult, lane: Int): List<LaneSegment> {
    val segments = mutableListOf<LaneSegment>()
    var col = 0
    while (col < 7) {
        val placed = result.placed.firstOrNull { it.lane == lane && it.startCol == col }
        when {
            placed != null -> {
                segments += LaneSegment(col, placed.span, placed, 0)
                col += placed.span
            }
            result.hiddenCount[col] > 0 && result.overflowLane[col] == lane -> {
                segments += LaneSegment(col, 1, null, result.hiddenCount[col])
                col++
            }
            else -> {
                val last = segments.lastOrNull()
                if (last != null && last.placed == null && last.hiddenCount == 0) {
                    segments[segments.lastIndex] = last.copy(span = last.span + 1)
                } else {
                    segments += LaneSegment(col, 1, null, 0)
                }
                col++
            }
        }
    }
    return segments
}
