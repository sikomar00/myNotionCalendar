package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.view.ViewPreset
import com.ysdoing.notioncal.core.data.view.ViewPresets
import com.ysdoing.notioncal.core.data.view.ViewWriter

// Phase 11c IX-04·AD-05: 위젯 프리셋 줄. 눌러서 프리셋 이름을 제목으로, 저장해 둔 값 묶음으로 새 항목을 만든다(4초 지연이라 되돌릴 수 있다).

private val PRESET_HEIGHT = 36.dp
internal const val PRESETS_PER_ROW = 3

/** 프리셋 이름 칩들(한 줄에 3개, 최대 5개라 두 줄까지). 칩 너비는 줄마다 같고 남는 자리는 비운다. */
@Composable
internal fun ViewPresetLine(viewId: String, presets: List<ViewPreset>, palette: WidgetPalette, fontSp: Float) {
    val context = LocalContext.current
    presets.chunked(PRESETS_PER_ROW).forEach { line ->
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 3.dp)) {
            line.forEach { preset ->
                Box(
                    GlanceModifier.defaultWeight().height(PRESET_HEIGHT).padding(end = 4.dp)
                        .clickable(actionRunCallback<PresetAddAction>(actionParametersOf(ViewCellAction.ViewKey to viewId, PresetAddAction.PresetKey to preset.name)))
                        .semantics {
                            testTag = "viewPreset"
                            contentDescription = context.getString(R.string.view_widget_preset_add, preset.name)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        preset.name, modifier = GlanceModifier.fillMaxWidth().tile(palette).padding(horizontal = 6.dp, vertical = 8.dp),
                        style = TextStyle(color = palette.text, fontSize = fontSp.sp, fontWeight = FontWeight.Medium), maxLines = 1,
                    )
                }
            }
            repeat(PRESETS_PER_ROW - line.size) { Spacer(GlanceModifier.defaultWeight()) }
        }
    }
}

/** 프리셋 누르기: 그 위젯의 같은 이름 프리셋으로 새 항목을 쓰기 큐에 4초 지연으로 쌓고(되돌릴 수 있다) 위젯을 다시 그린다. 노션은 부르지 않는다. */
class PresetAddAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val viewId = parameters[ViewCellAction.ViewKey] ?: return
        val name = parameters[PresetKey] ?: return
        val dependencies = WidgetDependencies.from(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val preset = ViewPresets.parse(dependencies.widgetConfigs().get(appWidgetId).presetsJson).firstOrNull { it.name == name } ?: return
        dependencies.viewWriter().create(viewId, preset.name, null, preset.props, ViewWriter.UNDO_WINDOW_MS)
        WidgetKinds.update(context, glanceId)
    }

    companion object {
        val PresetKey = ActionParameters.Key<String>("presetName")
    }
}
