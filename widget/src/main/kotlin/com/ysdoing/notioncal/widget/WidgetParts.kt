package com.ysdoing.notioncal.widget

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

// 위젯 공통 부품(2026-11-03 시안, DESIGN.md '위젯'). 새 스타일을 쓰는 위젯이 함께 쓴다.
// 비트맵 없이 레이아웃·배경색·벡터 아이콘만 쓴다(PF-07). 색은 [WidgetPalette]에서 받는다.

/** 눌림 영역 최소(위젯 품질 기준 WL-4.2). 보이는 크기가 작아도 눌림만 이만큼 넓힌다. */
internal val TAP_TARGET = 48.dp

/** 위젯 안쪽 모양: 타일·알약·원형 세 가지뿐이다. 바깥 모서리는 시스템 반경이다. */
internal object WidgetShape {
    val TILE = 16.dp

    /** 월간 날짜 칸처럼 작은 타일. */
    val CELL = 8.dp

    /** 알약(상태·날짜·연속일). 높이 24dp 안팎에서 끝이 완전히 둥글다. */
    val PILL = 12.dp
}

/** 타일 면: 바탕에서 한 단계 올린 둥근 사각형([high]면 한 단계 더). */
internal fun GlanceModifier.tile(palette: WidgetPalette, high: Boolean = false, radius: Dp = WidgetShape.TILE): GlanceModifier =
    background(if (high) palette.tileHigh else palette.tile).cornerRadius(radius)

internal enum class IconButtonStyle { PLAIN, TONAL, FILLED }

/**
 * 머리·행의 아이콘 버튼. 보이는 면은 [visual]이고 눌림 영역은 폭 [TAP_TARGET] x 높이 [height]다.
 * 높이를 줄여야 하는 곳(월간 머리: 일정 줄 수를 지키려고)은 [height]를 낮추되 폭은 48dp를 지킨다.
 */
@Composable
internal fun WidgetIconButton(
    @DrawableRes icon: Int,
    description: String,
    tag: String,
    action: Action,
    palette: WidgetPalette,
    style: IconButtonStyle = IconButtonStyle.TONAL,
    visual: Dp = 36.dp,
    height: Dp = TAP_TARGET,
) {
    val face = when (style) {
        IconButtonStyle.PLAIN -> null
        IconButtonStyle.TONAL -> palette.tileHigh
        IconButtonStyle.FILLED -> palette.accent
    }
    val tint = if (style == IconButtonStyle.FILLED) palette.todayText else palette.text
    Box(
        GlanceModifier.width(TAP_TARGET).height(height).clickable(action).semantics {
            contentDescription = description
            testTag = tag
        },
        contentAlignment = Alignment.Center,
    ) {
        val faceModifier = GlanceModifier.size(visual).let { if (face != null) it.background(face).cornerRadius(visual / 2) else it }
        Box(faceModifier, contentAlignment = Alignment.Center) {
            WidgetIcon(icon, tint, visual * 0.5f)
        }
    }
}

/** 원형 체크. 체크 전은 테두리, 체크하면 채운 초록. 보이는 지름은 [size], 눌림 영역은 폭 [TAP_TARGET] x 높이 [height]. */
@Composable
internal fun WidgetCheck(
    checked: Boolean,
    description: String,
    tag: String,
    action: Action,
    palette: WidgetPalette,
    size: Dp = 28.dp,
    height: Dp = TAP_TARGET,
) {
    Box(
        GlanceModifier.width(TAP_TARGET).height(height).clickable(action).semantics {
            contentDescription = description
            testTag = tag
        },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) WidgetIcon(R.drawable.ic_widget_check_circle, palette.green, size) else WidgetIcon(R.drawable.ic_widget_circle, palette.subtle, size)
    }
}

/** 알약: 상태·날짜·연속일처럼 짧은 말 한 마디. [icon]은 글자 앞의 작은 벡터. */
@Composable
internal fun WidgetPill(
    text: String,
    background: ColorProvider,
    foreground: ColorProvider,
    fontSp: Float,
    modifier: GlanceModifier = GlanceModifier,
    @DrawableRes icon: Int? = null,
) {
    Row(
        modifier.background(background).cornerRadius(WidgetShape.PILL).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            WidgetIcon(icon, foreground, (fontSp * 1.1f).dp)
            Spacer(GlanceModifier.width(3.dp))
        }
        Text(text, style = TextStyle(color = foreground, fontSize = fontSp.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/**
 * 머리: 강조 컨테이너 원 안의 아이콘 + 제목 + 부제 + 동작 버튼들([actions]). 제목 영역을 누르면 [titleModifier]의 동작이 일어난다.
 * 높이와 글자 크기는 위젯마다 다른 메트릭이 정하고, 구조와 모양은 모든 위젯이 같다(WL-3).
 */
@Composable
internal fun WidgetHeader(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String?,
    palette: WidgetPalette,
    height: Dp,
    titleSp: Float,
    subSp: Float,
    modifier: GlanceModifier = GlanceModifier,
    titleModifier: GlanceModifier = GlanceModifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Row(titleModifier.defaultWeight().height(height), verticalAlignment = Alignment.CenterVertically) {
            Box(
                GlanceModifier.size(HEADER_CHIP).background(palette.accentContainer).cornerRadius(HEADER_CHIP / 2),
                contentAlignment = Alignment.Center,
            ) {
                WidgetIcon(icon, palette.onAccentContainer, HEADER_CHIP * 0.56f)
            }
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(title, style = TextStyle(color = palette.text, fontSize = titleSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                if (subtitle != null) Text(subtitle, style = TextStyle(color = palette.subtle, fontSize = subSp.sp), maxLines = 1)
            }
        }
        actions()
    }
}

private val HEADER_CHIP = 32.dp

/** 상태 줄: 오류·오래됨은 경고 아이콘과 글자로 알린다(색만으로 알리지 않는다, A11Y-03). */
@Composable
internal fun WidgetStatusLine(text: String, error: Boolean, palette: WidgetPalette, fontSp: Float, modifier: GlanceModifier = GlanceModifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (error) {
            WidgetIcon(R.drawable.ic_widget_warning, palette.error, (fontSp * 1.15f).dp)
            Spacer(GlanceModifier.width(4.dp))
        }
        Text(text, style = TextStyle(color = if (error) palette.text else palette.subtle, fontSize = fontSp.sp), maxLines = 1)
    }
}

/** 빈 상태: 아이콘 + 한 줄 + 행동 버튼(있으면). 눌림 영역은 높이 [TAP_TARGET]이다. */
@Composable
internal fun WidgetEmpty(
    @DrawableRes icon: Int,
    text: String,
    palette: WidgetPalette,
    fontSp: Float,
    ctaText: String? = null,
    ctaAction: Action? = null,
    ctaTag: String = "emptyAction",
) {
    Column(GlanceModifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WidgetIcon(icon, palette.subtle, 28.dp)
        Spacer(GlanceModifier.height(4.dp))
        Text(text, style = TextStyle(color = palette.subtle, fontSize = fontSp.sp, textAlign = TextAlign.Center), maxLines = 2)
        if (ctaText != null && ctaAction != null) {
            val description = ctaText
            Box(
                GlanceModifier.height(TAP_TARGET).clickable(ctaAction).semantics {
                    contentDescription = description
                    testTag = ctaTag
                },
                contentAlignment = Alignment.Center,
            ) {
                WidgetPill(ctaText, palette.accentContainer, palette.onAccentContainer, fontSp, GlanceModifier.padding(horizontal = 6.dp, vertical = 4.dp))
            }
        }
    }
}
