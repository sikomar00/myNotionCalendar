package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ysdoing.notioncal.core.data.DesignTokens

/**
 * 앱과 퀵 시트가 함께 쓰는 테마(S25-10). 시스템 다크 모드를 따른다.
 * - 색: 기본은 노션 문서 색([DesignTokens]). 설정에서 '배경화면 색 쓰기'를 켜면 Material You 색. 일정 색(노션 색)은 테마와 무관하다.
 * - 글꼴: NotionCal Sans(Pretendard 한글 2,350자 부분집합, 가변 굵기). 숫자는 같은 폭(tnum)이라 날짜 열이 세로로 맞는다.
 * - 모양: 노션처럼 작은 둥근 모서리(칩 6dp, 카드 8dp, 대화상자·시트 16dp).
 */
@Composable
fun NotionCalTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && dark -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        dark -> NotionDark
        else -> NotionLight
    }
    MaterialTheme(colorScheme = scheme, typography = NotionTypography, shapes = NotionShapes, content = content)
}

private fun c(argb: Long) = Color(argb)

private val NotionLight: ColorScheme = with(DesignTokens.Light) {
    lightColorScheme(
        primary = c(ACCENT), onPrimary = c(ON_ACCENT),
        primaryContainer = c(SELECTED), onPrimaryContainer = c(INK),
        secondary = c(GRAPHITE), onSecondary = c(PAPER),
        secondaryContainer = c(SELECTED), onSecondaryContainer = c(INK),
        tertiary = c(ACCENT), onTertiary = c(ON_ACCENT),
        tertiaryContainer = c(SELECTED), onTertiaryContainer = c(INK),
        background = c(PAPER), onBackground = c(INK),
        surface = c(PAPER), onSurface = c(INK),
        surfaceVariant = c(SURFACE_HIGH), onSurfaceVariant = c(GRAPHITE),
        surfaceTint = c(PAPER),
        inverseSurface = c(INK), inverseOnSurface = c(PAPER), inversePrimary = c(DesignTokens.Dark.ACCENT),
        error = c(ERROR), onError = c(PAPER),
        outline = c(OUTLINE), outlineVariant = c(RULE),
        surfaceBright = c(PAPER), surfaceDim = c(SURFACE_HIGHEST),
        surfaceContainerLowest = c(PAPER), surfaceContainerLow = c(SURFACE_LOW),
        surfaceContainer = c(SURFACE), surfaceContainerHigh = c(SURFACE_HIGH),
        surfaceContainerHighest = c(SURFACE_HIGHEST),
    )
}

private val NotionDark: ColorScheme = with(DesignTokens.Dark) {
    darkColorScheme(
        primary = c(ACCENT), onPrimary = c(ON_ACCENT),
        primaryContainer = c(SELECTED), onPrimaryContainer = c(INK),
        secondary = c(GRAPHITE), onSecondary = c(PAPER),
        secondaryContainer = c(SELECTED), onSecondaryContainer = c(INK),
        tertiary = c(ACCENT), onTertiary = c(ON_ACCENT),
        tertiaryContainer = c(SELECTED), onTertiaryContainer = c(INK),
        background = c(PAPER), onBackground = c(INK),
        surface = c(PAPER), onSurface = c(INK),
        surfaceVariant = c(SURFACE_HIGHEST), onSurfaceVariant = c(GRAPHITE),
        surfaceTint = c(PAPER),
        inverseSurface = c(INK), inverseOnSurface = c(PAPER), inversePrimary = c(DesignTokens.Light.ACCENT),
        error = c(ERROR), onError = c(PAPER),
        outline = c(OUTLINE), outlineVariant = c(RULE),
        surfaceBright = c(SURFACE_HIGHEST), surfaceDim = c(PAPER),
        surfaceContainerLowest = c(0xFF141414), surfaceContainerLow = c(SURFACE_LOW),
        surfaceContainer = c(SURFACE), surfaceContainerHigh = c(SURFACE_HIGH),
        surfaceContainerHighest = c(SURFACE_HIGHEST),
    )
}

/** NotionCal Sans 한 파일(가변 굵기)에서 쓰는 굵기들. 굵기마다 wght 축 값을 맞춘다. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val NotionCalSans: FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map { weight ->
        Font(
            com.ysdoing.notioncal.core.data.R.font.app_sans,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    },
)

/**
 * 모든 글자에 NotionCal Sans와 같은 폭 숫자(tnum). 한글은 Material 기본 자간(라틴 기준 0.1~0.5sp)이 헐거워 보여
 * 본문은 0, 제목은 살짝 좁힌다.
 */
private val NotionTypography: Typography = Typography().let { base ->
    fun TextStyle.notion(tracking: Float = 0f) = copy(fontFamily = NotionCalSans, fontFeatureSettings = "tnum", letterSpacing = tracking.sp)
    Typography(
        displayLarge = base.displayLarge.notion(-0.5f),
        displayMedium = base.displayMedium.notion(-0.4f),
        displaySmall = base.displaySmall.notion(-0.3f),
        headlineLarge = base.headlineLarge.notion(-0.3f),
        headlineMedium = base.headlineMedium.notion(-0.2f),
        headlineSmall = base.headlineSmall.notion(-0.2f),
        titleLarge = base.titleLarge.notion(-0.2f),
        titleMedium = base.titleMedium.notion(),
        titleSmall = base.titleSmall.notion(),
        bodyLarge = base.bodyLarge.notion(),
        bodyMedium = base.bodyMedium.notion(),
        bodySmall = base.bodySmall.notion(),
        labelLarge = base.labelLarge.notion(),
        labelMedium = base.labelMedium.notion(),
        labelSmall = base.labelSmall.notion(),
    )
}

/** 노션처럼 작은 둥근 모서리. 알약 모양(완전 둥근)은 Material 버튼 기본값으로만 남는다. */
private val NotionShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)
