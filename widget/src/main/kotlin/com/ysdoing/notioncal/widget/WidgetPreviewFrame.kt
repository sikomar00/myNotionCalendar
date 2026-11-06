package com.ysdoing.notioncal.widget

import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.recipe.RecipeConfig
import com.ysdoing.notioncal.core.data.view.ViewBoard
import java.time.DayOfWeek

/**
 * 위젯 [RemoteViews]를 [size]로 놓고, 폭이 화면보다 넓으면 폭에 맞춰 줄이며 높이는 화면의 [maxScreenFraction]까지만 보여 준다(남는 아래쪽은 자른다).
 * 설정 화면 미리보기와 레시피 화면 미리보기가 함께 쓴다.
 */
@Composable
internal fun RemoteViewsFrame(views: RemoteViews?, size: DpSize, maxScreenFraction: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = previewScale(size, maxWidth)
        val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * maxScreenFraction
        Box(Modifier.width(size.width * scale).height(previewHeight(size, scale, maxHeight)).clipToBounds()) {
            AndroidView(
                factory = { FrameLayout(it) },
                update = { frame ->
                    views?.let { remote ->
                        frame.removeAllViews()
                        frame.addView(remote.apply(frame.context, frame))
                    }
                },
                // requiredSize만 쓰면 부모보다 큰 내용이 가운데 기준으로 놓여 위가 잘린다: 위쪽 기준으로 놓는다.
                modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(size.width, size.height)
                    .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) },
            )
        }
    }
}

private val RECIPE_PREVIEW_SIZE = DpSize(330.dp, 380.dp) // 4×4 근사(설정 화면이 크기를 모를 때와 같다)
private const val RECIPE_PREVIEW_MAX_FRACTION = 0.45f

/**
 * Phase 11h 레시피 화면의 미리보기: 레시피가 만든 구성을 설정 화면과 같은 방식(실제 위젯과 같은 Glance 내용을 RemoteViews로)으로 그린다.
 * 집계 타일은 [boards](등록한 보기의 실제 행)로 계산하고, 행을 아직 못 받은 보기의 칸은 예시 행이다. [names]는 보기 ID → 이름.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@Composable
fun RecipePreview(config: RecipeConfig, names: Map<String, String>, boards: Map<String, ViewBoard?>, weekStart: DayOfWeek) {
    val context: Context = LocalContext.current
    var views by remember { mutableStateOf<RemoteViews?>(null) }
    LaunchedEffect(config, names, boards, weekStart) {
        val state = WidgetPreviews.layoutPreview(WidgetConfig(0, layoutJson = config.layoutJson, formJson = config.formJson), names, boards = boards, weekStart = weekStart)
        views = GlanceRemoteViews().compose(context, RECIPE_PREVIEW_SIZE) { LayoutWidgetContent(state, staticRows = true) }.remoteViews
    }
    RemoteViewsFrame(views, RECIPE_PREVIEW_SIZE, RECIPE_PREVIEW_MAX_FRACTION)
}
