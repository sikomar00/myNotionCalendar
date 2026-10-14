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

/**
 * 위젯 크기·설정에서 계산한 치수. Glance에는 임의 위치 지정이 없어 막대 폭을 칸 폭의 배수로 그린다.
 *
 * 글자 크기(…Sp)는 Glance에 그대로 넘기는 sp 값이다. 위젯은 줄 높이를 dp로 고정하므로, 기기 글꼴 크기가
 * [MAX_SYSTEM_TEXT_SCALE]를 넘으면 그 이상 커지지 않게 sp를 줄여 넘긴다(S25-06: 글꼴 최대에서 날짜·요일이 잘림).
 * 위젯 글자를 더 키우려면 위젯 설정의 글자 배율(최대 150%)을 쓴다. 앱 화면은 기기 글꼴을 끝까지 따른다.
 */
data class WidgetMetrics(
    val mode: WidgetMode,
    val headerHeight: Dp,
    val weekdayHeight: Dp,
    val cellWidth: Dp,
    val weekHeight: Dp,
    val dateLabelHeight: Dp,
    val laneHeight: Dp,
    /** 일정 글자가 실제로 차지하는 높이(줄 간격 여백 제외). WS-05 넘침 판정에 쓴다. */
    val chipTextHeight: Dp,
    /** 칸당 일정 줄 수(마지막 줄은 넘칠 때 +N 자리). 1이면 날짜마다 "첫 일정 + N"을 한 줄에 그린다. */
    val lanes: Int,
    val chipFontSp: Float,
    val dateFontSp: Float,
    val headerFontSp: Float,
    val weekdayFontSp: Float,
    /** 작음 단계에서 점을 그릴 공간이 있는지. */
    val showDots: Boolean,
) {
    /** WS-05: 날짜 줄과 일정 한 줄이 주 높이에 들어가지 않는다(글자가 칸을 넘친다). */
    val overflows: Boolean get() = mode != WidgetMode.SMALL && dateLabelHeight + chipTextHeight > weekHeight + 0.5.dp

    companion object {
        val PADDING = 6.dp
        private val HEADER_HEIGHT = 34.dp
        private val WEEKDAY_HEIGHT = 16.dp
        private val SMALL_HEADER_HEIGHT = 24.dp
        private val SMALL_WEEKDAY_HEIGHT = 11.dp
        private val DOT_SPACE = 6.dp
        private const val MIN_DOT_DATE_FONT = 6.5f
        const val MAX_LANES = 6

        /** 위젯 글자가 기기 글꼴 크기를 따라 커지는 상한. */
        const val MAX_SYSTEM_TEXT_SCALE = 1.3f

        /** 한글 글꼴의 실제 줄 높이(글자 크기 대비). 1.3으로는 S25에서 아랫부분이 잘렸다. */
        internal const val LINE = 1.45f
        private const val WEEKDAY_LINE = 1.35f

        /**
         * 글자 칸(TextView)이 실제로 차지하는 높이(글자 크기 대비). S25 실측: 날짜 숫자 칸이 글꼴 0.8에서 14.5dp/8.8dp,
         * 2.0에서 23.2dp/14.3dp. 이보다 줄이 낮으면 날짜·요일 위아래가 잘렸다.
         */
        internal const val TEXT_BOX = 1.65f

        /** 작음(2×2, 4×2)의 날짜 줄. 높이가 모자라 글자 위아래 여백만 잘리는 수준(1.3)까지 줄인다. */
        private const val SMALL_DATE_LINE = 1.3f

        /**
         * [systemFontScale]은 기기 글꼴 크기. sp 글자가 실제로 차지하는 높이를 dp로 어림해 줄이 잘리지 않게 한다.
         *
         * WS-05: 글자 배율 = 위젯 글자 배율 × 기기 글꼴 크기(상한 [MAX_SYSTEM_TEXT_SCALE]). 이 배율로 크기 단계(큼·중간)에서
         * 날짜 줄과 일정 한 줄이 주 높이에 들어가지 않으면 한 단계 작은 레이아웃으로 바꿔 다시 계산한다(큼 → 중간 → 작음).
         * 작음은 줄 높이를 고정하고 글자를 줄이므로 넘치지 않는다.
         */
        fun of(size: DpSize, config: WidgetConfig, systemFontScale: Float): WidgetMetrics {
            var mode = widgetMode(size)
            while (true) {
                val metrics = compute(size, config, systemFontScale, mode)
                if (mode == WidgetMode.SMALL || !metrics.overflows) return metrics
                mode = if (mode == WidgetMode.LARGE) WidgetMode.MEDIUM else WidgetMode.SMALL
            }
        }

        private fun compute(size: DpSize, config: WidgetConfig, systemFontScale: Float, mode: WidgetMode): WidgetMetrics {
            val scale = config.fontScale / 100f
            val small = mode == WidgetMode.SMALL
            // 실제로 그려지는 배율과, 그 배율이 되도록 sp에 곱할 값.
            val textScale = minOf(systemFontScale, MAX_SYSTEM_TEXT_SCALE)
            val spFactor = textScale / systemFontScale
            val chipFont = 10f * scale
            val spacing = when (config.lineSpacing) {
                LineSpacing.COMPACT -> 1.15f
                LineSpacing.NORMAL -> 1.35f
                LineSpacing.WIDE -> 1.6f
            }
            fun lineHeight(fontSp: Float, line: Float = LINE) = (fontSp * textScale * line).dp
            // 큰·중간: 글자에 맞춰 머리줄·요일 줄을 키운다. 작음(2×2, 4×2): 높이가 모자라 줄은 고정하고 글자를 줄인다.
            val headerFont = if (small) minOf(13f, (SMALL_HEADER_HEIGHT - 4.dp).value / LINE / textScale) else 15f
            val weekdayFont = if (small) minOf(8f, SMALL_WEEKDAY_HEIGHT.value / WEEKDAY_LINE / textScale) else 10f
            val header = if (small) SMALL_HEADER_HEIGHT else maxOf(HEADER_HEIGHT, lineHeight(headerFont) + 4.dp)
            val weekday = if (small) SMALL_WEEKDAY_HEIGHT else maxOf(WEEKDAY_HEIGHT, lineHeight(weekdayFont, TEXT_BOX))
            val contentWidth = size.width - PADDING * 2
            val weekHeight = (size.height - PADDING * 2 - header - weekday) / 6
            // 작음(2×2, 4×2)은 주 한 줄 높이가 10~18dp까지 줄어든다. 점 자리를 먼저 남기고 날짜 숫자를 맞춘다.
            // 그래도 숫자가 너무 작아지면(6.5sp 미만) 점을 포기하고 숫자만 겹치지 않게 그린다.
            val wantedDateFont = (if (small) 9f else 11f) * scale
            val dateLine = if (small) SMALL_DATE_LINE else TEXT_BOX
            fun fontFor(height: Dp) = (height - 2.dp).value / dateLine / textScale
            val withDots = fontFor(weekHeight - DOT_SPACE)
            val showDots = !small || withDots >= MIN_DOT_DATE_FONT
            val dateFont = when {
                !small -> wantedDateFont
                showDots -> minOf(wantedDateFont, withDots)
                else -> minOf(wantedDateFont, fontFor(weekHeight).coerceAtLeast(5f))
            }
            val dateLabel = lineHeight(dateFont, dateLine) + 2.dp
            // 줄 간격 설정은 글자 높이 위에 더하는 여백으로 쓴다(촘촘하게 해도 글자는 잘리지 않음).
            val lane = lineHeight(chipFont) + (chipFont * textScale * (spacing - 1.15f)).dp + 1.dp
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
                chipTextHeight = lineHeight(chipFont),
                lanes = lanes,
                chipFontSp = chipFont * spFactor,
                dateFontSp = dateFont * spFactor,
                headerFontSp = headerFont * spFactor,
                weekdayFontSp = weekdayFont * spFactor,
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
