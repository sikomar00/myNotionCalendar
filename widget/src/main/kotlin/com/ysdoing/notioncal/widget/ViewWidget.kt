package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.GalleryConfig
import com.ysdoing.notioncal.core.data.view.ViewActions
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.ViewPreset
import com.ysdoing.notioncal.core.data.view.ViewPresets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** [board]가 null이고 [config]에 보기가 있으면 등록을 해제한 보기다(VW-06). 노션에서 지워진 보기는 [ViewBoard.notFound]로 온다. */
internal data class ViewWidgetState(
    val board: ViewBoard?,
    val config: WidgetConfig,
    val nowMillis: Long,
    val calendar: CalendarInfo = CalendarInfo(),
    /** 갤러리 카드가 그릴 표지(Phase 12d). 갤러리가 아니면 비어 있다. */
    val covers: Covers = Covers.EMPTY,
    /** 갤러리 카드가 별점 글자로 그릴 속성 ID(노션 위젯 배치의 항목이 가진다, 없으면 빈 글자). */
    val ratingProp: String = "",
)

/** 표(넓을 때)에 넣을 열 수. 열 하나에 [COLUMN_MIN]dp는 있어야 글자가 읽힌다. */
internal fun tableColumnCount(width: Dp, columns: Int): Int =
    ((width - PADDING_H * 2) / COLUMN_MIN).toInt().coerceIn(1, maxOf(columns, 1))

internal val COLUMN_MIN = 72.dp
internal val PADDING_H = 10.dp
internal val VIEW_PADDING_V = 4.dp
private val TABLE_MIN_WIDTH = 220.dp
private const val STALE_MILLIS = 12 * 60 * 60 * 1000L

/**
 * 위젯 안 치수. 글자 배율(위젯별)과 시스템 글꼴(상한 [WidgetMetrics.MAX_SYSTEM_TEXT_SCALE])에 맞춰 줄 높이를 잡아 글자가 잘리지 않게 한다.
 * [spFactor]는 시스템 글꼴을 상한에서 자른 만큼 sp를 되돌린다(위젯 글자는 시스템 배율을 또 곱해 그려진다).
 */
internal data class ViewMetrics(
    val spFactor: Float,
    val titleSp: Float,
    val subSp: Float,
    val headerSp: Float,
    /** 제목만 있는 머리와, 제목 아래 부제('완료 n/m')가 있는 머리의 높이. 눌림 버튼이 들어가도록 40dp 이상이다. */
    val header: Dp,
    val headerWithSub: Dp,
    val tableHeader: Dp,
    val listRow: Dp,
    val tableRow: Dp,
    /** Phase 11e 집계 타일: 값 글자 크기와, 라벨 + 값 줄만 있는 타일 높이·막대와 목표 글자 줄이 더 들 때 늘어나는 높이. */
    val valueSp: Float = 18f,
    val tile: Dp = 64.dp,
    val tileExtras: Dp = 28.dp,
    /** 기기 글꼴 배율 그대로(상한을 자르기 전). 캘린더 격자가 [WidgetMetrics]를 다시 계산할 때 쓴다. */
    val systemFontScale: Float = 1f,
)

internal fun viewMetrics(userScale: Float, systemFontScale: Float): ViewMetrics {
    val textScale = minOf(systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
    fun box(sp: Float) = (sp * textScale * WidgetMetrics.TEXT_BOX).dp
    val titleSp = 12f * userScale
    val subSp = 10f * userScale
    val headerSp = 13f * userScale
    return ViewMetrics(
        spFactor = textScale / systemFontScale,
        titleSp = titleSp,
        subSp = subSp,
        headerSp = headerSp,
        header = maxOf(40.dp, box(headerSp) + 8.dp),
        headerWithSub = maxOf(44.dp, box(headerSp) + box(subSp) + 4.dp),
        tableHeader = box(subSp) + 4.dp,
        // 행은 타일이라 위아래 여백을 조금 더 둔다(틈 4dp는 행 밖).
        listRow = box(titleSp) + box(subSp) + 10.dp,
        tableRow = box(titleSp) + 10.dp,
        valueSp = 18f * userScale,
        tile = box(subSp) + box(18f * userScale) + AGG_TILE_PADDING * 2,
        tileExtras = 6.dp + box(subSp) + 4.dp, // 막대 + 목표 글자 줄 + 위아래 틈
        systemFontScale = systemFontScale,
    )
}

/** VW-06 보기를 다시 고르러 위젯 설정을 연다. 설정 액티비티는 위젯 ID를 `EXTRA_APPWIDGET_ID`로 받는다(길게 눌러 다시 설정할 때와 같다). */
internal fun viewConfigIntent(context: Context, appWidgetId: Int): Intent =
    Intent(context, WidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** [viewStatusText]가 글자를 낼 조건과 같다(높이를 미리 알아야 하는 곳용). 둘이 어긋나지 않는지 시험으로 확인한다. */
internal fun viewHasStatus(board: ViewBoard, nowMillis: Long): Boolean =
    board.notFound || board.unsupported || board.syncedAt == 0L || board.lastError != null || nowMillis - board.syncedAt > STALE_MILLIS

/** VW-06 머리 아래 상태 한 줄. 색이 아니라 글자로 구분한다(A11Y-03). */
internal fun viewStatusText(context: Context, board: ViewBoard, nowMillis: Long): String? = when {
    board.notFound -> context.getString(R.string.view_widget_not_found)
    board.unsupported -> context.getString(R.string.view_widget_unsupported)
    board.syncedAt == 0L -> context.getString(R.string.view_widget_not_synced)
    board.lastError != null -> context.getString(R.string.view_widget_failed, timeText(board.syncedAt))
    nowMillis - board.syncedAt > STALE_MILLIS -> context.getString(R.string.view_widget_stale, timeText(board.syncedAt))
    else -> null
}

private fun timeText(millis: Long): String = DateTimeFormatter.ofPattern("M/d HH:mm").format(Instant.ofEpochMilli(millis).atZone(APP_ZONE))

/**
 * VW-03 보기 위젯. 넓으면 표(열 머리 + 행), 좁으면 목록(제목 + 둘째 줄에 다음 속성 1개). 머리를 누르면 새로고침.
 * 위젯·미리보기가 같은 코드를 쓴다. 비트맵 없이 그린다(PF-07).
 * [staticRows]는 설정 화면 미리보기용이다. 그 화면은 변환 결과를 View에 직접 적용하는데 목록(LazyColumn)의 행이 그려지지 않아,
 * 목록 대신 고정 Column으로 앞쪽 [PREVIEW_ROWS]행만 그린다. 실제 위젯은 항상 목록이다.
 */
@Composable
internal fun ViewWidgetContent(
    state: ViewWidgetState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
    staticRows: Boolean = false,
) {
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, LocalContext.current)
    val metrics = viewMetrics(state.config.fontScale / 100f, systemFontScale)
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(palette.background).padding(horizontal = PADDING_H, vertical = VIEW_PADDING_V),
    ) {
        ViewPane(state, palette, metrics, PaneSpec(staticRows = staticRows))
    }
}

/**
 * [ViewPane]을 그리는 방식. [staticRows]는 설정 화면 미리보기용, [height]는 탭·섹션 한 칸의 높이(단일 위젯은 null), [presets]는 프리셋 줄을 둘지
 * (프리셋은 한 보기의 값이라 여러 보기가 든 배치에서는 끈다).
 */
internal data class PaneSpec(val staticRows: Boolean = false, val height: Dp? = null, val presets: Boolean = true)

/**
 * 보기 위젯의 본문(머리줄 + 상태·되돌리기·프리셋 줄 + 행)을 바깥 틀 없이 그린다. 틀(`appWidgetBackground`)은 위젯당 한 번만 둘 수 있어
 * 단일 위젯([ViewWidgetContent])과 탭·섹션 배치가 각자 틀을 두고 이것을 한 칸마다 쓴다. 크기 단계·열 수는 [LocalSize]로 정하니 칸마다 덮어 쓴다.
 */
@Composable
internal fun ViewPane(state: ViewWidgetState, palette: WidgetPalette, metrics: ViewMetrics, spec: PaneSpec) {
    val board = state.board
    ViewHeader(board, state.config.appWidgetId, metrics, palette)
    if (board == null) {
        val message = if (state.config.viewId == null) R.string.view_widget_choose else R.string.view_widget_missing
        ViewNote(message, state.config.appWidgetId, metrics, palette)
    } else {
        ViewBody(board, state, metrics, palette, spec)
    }
}

/**
 * VW-08 새 항목 입력 시트 열기. 보기마다(위젯이 있으면 위젯마다) 다른 data URI를 달아 PendingIntent가 서로 덮지 않게 한다(:widget은 :feature에 의존하지 않아
 * action 문자열로 연다). [appWidgetId]는 시트가 이 위젯의 입력 양식을 읽고 기본값·프리셋을 저장하도록 싣는다(0이면 양식·프리셋이 없다. 프리셋은 시트가 프리셋 줄을 그리는 배치일 때만 보인다).
 * [date]는 캘린더 날짜 칸을 눌렀을 때의 날짜다(시트의 날짜 칩이 그날로 시작한다). 날짜마다 다른 data URI라 PendingIntent가 날짜끼리 덮지 않는다.
 */
internal fun viewAddIntent(context: Context, viewId: String, appWidgetId: Int = 0, date: LocalDate? = null): Intent =
    Intent(AppIntents.ACTION_VIEW_ADD)
        .setPackage(context.packageName)
        .setData(((if (appWidgetId > 0) "notioncal://view/add/$viewId/$appWidgetId" else "notioncal://view/add/$viewId") + (date?.let { "?d=$it" } ?: "")).toUri())
        .putExtra(AppIntents.EXTRA_VIEW_ID, viewId)
        .apply { if (appWidgetId > 0) putExtra(AppIntents.EXTRA_APPWIDGET_ID, appWidgetId) }
        .apply { if (date != null) putExtra(AppIntents.EXTRA_DATE, date.toString()) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/**
 * Phase 11g AD-05: 위젯 편집 화면에서 새 항목 시트를 '프리셋 만들기'로 연다. 편집 화면 위에 쌓아야 하니 [viewAddIntent]의 새 작업·작업 비우기 플래그는 쓰지 않는다.
 */
internal fun presetAddIntent(context: Context, viewId: String, appWidgetId: Int): Intent =
    viewAddIntent(context, viewId, appWidgetId).setFlags(0).putExtra(AppIntents.EXTRA_PRESET_ONLY, true)

/** 프리셋 만들기 시트([presetAddIntent])가 돌려준 결과의 프리셋. 이름이 비었으면 null이다. */
internal fun presetFrom(data: Intent?): ViewPreset? {
    val name = data?.getStringExtra(AppIntents.EXTRA_PRESET_NAME)?.trim().orEmpty()
    if (name.isEmpty()) return null
    return ViewPreset(name, ViewJson.typed(data?.getStringExtra(AppIntents.EXTRA_PRESET_PROPS).orEmpty()))
}

/**
 * 행 편집 시트 열기(ED-01, 행 탭). 행마다 다른 data URI를 달아 PendingIntent가 위젯의 행끼리 서로 덮지 않게 한다
 * (:widget은 :feature에 의존하지 않아 action 문자열로 연다). [appWidgetId]는 이 보기에 액션 버튼이 있을 때만 싣는다(시트가 위젯 설정에서
 * 액션을 읽는다). 위젯마다 액션이 달라 URI에도 넣어 두 위젯의 같은 행이 서로의 PendingIntent를 덮지 않게 한다.
 */
internal fun viewRowIntent(context: Context, viewId: String, pageId: String, appWidgetId: Int = 0): Intent =
    Intent(AppIntents.ACTION_VIEW_ROW)
        .setPackage(context.packageName)
        .setData((if (appWidgetId > 0) "notioncal://view/row/$viewId/$pageId?widget=$appWidgetId" else "notioncal://view/row/$viewId/$pageId").toUri())
        .putExtra(AppIntents.EXTRA_VIEW_ID, viewId)
        .putExtra(AppIntents.EXTRA_PAGE_ID, pageId)
        .apply { if (appWidgetId > 0) putExtra(AppIntents.EXTRA_APPWIDGET_ID, appWidgetId) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/** 선택 시트 열기(IX-02, 선택지 4개 이상 select 탭). 칸마다 다른 data URI를 달아 PendingIntent가 서로 덮지 않게 한다. */
internal fun viewChoiceIntent(context: Context, viewId: String, pageId: String, propId: String): Intent =
    Intent(AppIntents.ACTION_VIEW_CHOICE)
        .setPackage(context.packageName)
        .setData(android.net.Uri.Builder().scheme("notioncal").authority("view").appendPath("choice").appendPath(viewId).appendPath(pageId).appendPath(propId).build())
        .putExtra(AppIntents.EXTRA_VIEW_ID, viewId)
        .putExtra(AppIntents.EXTRA_PAGE_ID, pageId)
        .putExtra(AppIntents.EXTRA_PROP_ID, propId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/**
 * 머리(공통 [WidgetHeader]): 체크리스트 아이콘 + 보기 이름(누르면 새로고침) + 부제 '완료 n/m'(높이가 넉넉하고 진행을 셀 수 있을 때),
 * 오른쪽에 새로고침(넓을 때)과, 새 항목을 만들 수 있는 보기(제목 열 정보가 있음)면 '+'.
 */
@Composable
private fun ViewHeader(board: ViewBoard?, appWidgetId: Int, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    // 이름 없는 보기도 노션에서 허용된다. 접근성 설명이 글자를 대신하므로 보기 이름을 함께 읽게 한다.
    val title = board?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.view_widget_label)
    val size = LocalSize.current
    val progress = progressOf(board, size)
    val height = if (progress != null) m.headerWithSub else m.header
    val subtitle = progress?.let {
        if (it.label == null) context.getString(R.string.view_widget_progress, it.done, it.total)
        else context.getString(R.string.view_widget_progress_named, it.label, it.done, it.total)
    }
    // 제목 영역 전체가 눌림이라 설명이 안의 글자를 대신한다. 부제(완료 n/m)도 함께 읽히게 설명에 넣는다.
    val description = context.getString(R.string.view_widget_header_description, listOfNotNull(title, subtitle).joinToString(", "))
    WidgetHeader(
        icon = R.drawable.ic_widget_checklist,
        title = title,
        subtitle = subtitle,
        palette = palette,
        height = height,
        titleSp = m.headerSp * m.spFactor,
        subSp = m.subSp * m.spFactor,
        titleModifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()).semantics {
            contentDescription = description
            testTag = "viewHeader"
        },
    ) {
        if (board != null && size.width >= REFRESH_MIN_WIDTH) {
            WidgetIconButton(
                R.drawable.ic_widget_refresh, context.getString(R.string.widget_refresh), "viewRefresh", actionRunCallback<RefreshAction>(), palette, height = height,
            )
        }
        if (board?.canCreate == true) {
            WidgetIconButton(
                R.drawable.ic_widget_add, context.getString(R.string.view_widget_add_description, title), "viewAdd",
                actionStartActivity(viewAddIntent(context, board.viewId, appWidgetId)), palette, style = IconButtonStyle.FILLED, height = height,
            )
        }
    }
}

/** VR-02 '더 보기': 앱의 뷰어 화면에서 그 보기를 연다(:widget은 :feature에 의존하지 않아 딥링크로 연다). */
internal fun viewerIntent(context: Context, viewId: String): Intent =
    Intent(Intent.ACTION_VIEW, "notioncal://view/$viewId".toUri()).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** 머리 부제('완료 n/m')의 값. 높이가 [SUBTITLE_MIN_HEIGHT] 이상이고 진행을 셀 수 있을 때만 있고, 그러면 머리가 [ViewMetrics.headerWithSub]만큼 높다. */
internal fun progressOf(board: ViewBoard?, size: DpSize): ViewProgress? = board?.takeIf { size.height >= SUBTITLE_MIN_HEIGHT }?.let(::viewProgress)

/** 부제는 3줄 높이쯤부터, 새로고침 버튼은 4칸 폭쯤부터 둔다(2×2에서 머리와 한 줄이 함께 보여야 한다). */
internal val SUBTITLE_MIN_HEIGHT = 140.dp
internal val REFRESH_MIN_WIDTH = 200.dp

/** 고르지 않았거나 등록이 지워진 보기: 글자로 알리고, 누르면 위젯 설정에서 다시 고른다. */
@Composable
internal fun ViewNote(message: Int, appWidgetId: Int, m: ViewMetrics, palette: WidgetPalette, tag: String = "viewNote") {
    val context = LocalContext.current
    Text(
        context.getString(message),
        modifier = GlanceModifier.fillMaxWidth().tile(palette).padding(12.dp)
            .clickable(actionStartActivity(viewConfigIntent(context, appWidgetId))).semantics { testTag = tag },
        style = TextStyle(color = palette.text, fontSize = (12f * m.spFactor).sp),
    )
}

@Composable
private fun ViewBody(board: ViewBoard, state: ViewWidgetState, m: ViewMetrics, palette: WidgetPalette, spec: PaneSpec) {
    ViewStatusLine(board, state.config.appWidgetId, state.nowMillis, m, palette)
    val delayedUndo = board.undoableAt(state.nowMillis) > 0
    val undoShown = delayedUndo || board.sentUndoableAt(state.nowMillis) > 0
    if (undoShown) ViewUndoLine(board.viewId, palette, m.subSp * m.spFactor, sent = !delayedUndo)
    // 머리 아래 상태·되돌리기 줄이 칸 높이를 차지하니 행·카드 수는 그만큼 뺀 높이로 정한다.
    val lines = (if (viewStatusText(LocalContext.current, board, state.nowMillis) != null) m.tableHeader + 4.dp else 0.dp) + (if (undoShown) UNDO_HEIGHT else 0.dp)
    val paneHeight = spec.height?.let { (it - lines).coerceAtLeast(0.dp) }
    val size = LocalSize.current
    val step = sizeStep(size)
    // 이 보기의 액션 버튼(IX-07): 행 시트가 위젯 설정에서 읽도록 위젯 번호를 알리고, 2×2보다 넓으면 첫 액션을 행 옆에 둔다.
    val actions = ViewActions.forView(ViewActions.parse(state.config.actionsJson), board.viewId)
    val actionWidgetId = if (actions.isEmpty()) 0 else state.config.appWidgetId
    // 프리셋 줄(IX-04): 새 항목을 만들 수 있는 보기에서, 2×2보다 넓을 때만 둔다.
    val presets = ViewPresets.parse(state.config.presetsJson).takeIf { spec.presets && board.canCreate && step != SizeStep.COMPACT }.orEmpty()
    if (presets.isNotEmpty()) ViewPresetLine(board.viewId, presets, palette, m.subSp * m.spFactor)
    // 되돌리기·프리셋 줄이 높이를 차지하니 보드는 줄 수만큼 열마다 카드를 줄인다(보드는 목록과 달리 스크롤이 없어 넘치면 잘린다).
    val extraLines = (if (undoShown) 1 else 0) + (presets.size + PRESETS_PER_ROW - 1) / PRESETS_PER_ROW
    // 캘린더는 행이 없어도 격자를 그린다(날짜 칸을 눌러 새 항목을 만든다). 격자가 읽힐 높이가 없으면 아래 표·목록으로 대체한다.
    val calendar = calendarLayoutFor(board, size, spec, m, lines + presetLinesHeight(presets.size))
    if (calendar != null) {
        CalendarBody(board.calendarSource(state.config.appWidgetId), state, ViewLook(m, palette, actionWidgetId = actionWidgetId), calendar)
        return
    }
    // 타임라인(Phase 12e): 막대 줄. 행이 없어도 창을 그려 ‹ ›로 다른 기간을 볼 수 있다. 자리가 없으면(2×2·낮은 칸) 아래 표·목록으로 대신 그린다.
    val timeline = timelineLayoutFor(board, state, size, spec, m, lines + presetLinesHeight(presets.size))
    if (timeline != null) {
        TimelineBody(board, timeline, state, ViewLook(m, palette, actionWidgetId = actionWidgetId))
        return
    }
    if (board.rows.isEmpty()) {
        ViewEmpty(board, state.config.appWidgetId, m, palette)
        return
    }
    // 차트 보기: 그릴 수 있으면 차트로(Phase 12b), 못 그리는 설정이면 한 줄 안내 뒤에 아래 표·목록으로 이어 그린다.
    if (board.type == "chart" && ChartOrNote(board, size, spec, ViewLook(m, palette), lines + presetLinesHeight(presets.size))) return
    // 숫자 − + 단계 버튼은 2×2보다 넓을 때만 둔다(좁으면 자리가 없어 행을 눌러 시트에서 고친다).
    val look = ViewLook(
        m, palette, state.config.numberStep, stepNumbers = step != SizeStep.COMPACT,
        actionWidgetId = actionWidgetId, rowAction = actions.firstOrNull()?.takeIf { step != SizeStep.COMPACT },
    )
    // 갤러리(Phase 12d): 표지 카드 격자. 자리가 없으면(2×2·낮은 칸) 아래 표·목록으로 대신 그린다.
    val gallery = GalleryConfig.parse(board.viewConfig).takeIf { board.type == "gallery" }
    val galleryLayout = gallery?.let { galleryLayoutFor(board, size, spec, m, lines + presetLinesHeight(presets.size), it) }
    if (gallery != null && galleryLayout != null) {
        GalleryBody(board, galleryLayout, GalleryDraw(gallery, state.covers, state.ratingProp), look)
        return
    }
    // 보드는 group_by 열로 나눠 그린다. 그룹 열을 모르거나 2×2처럼 좁으면 아래 목록·표로 대체한다(타임라인 같은 다른 종류도 같다).
    val groups = if (board.type == "board") boardGroups(board) else null
    val layout = groups?.let { boardLayout(step, size.width) }?.let { it.copy(cardsPerColumn = (it.cardsPerColumn - extraLines).coerceAtLeast(1)) }
        // 탭·섹션의 낮은 칸이면 카드 수도 그 칸 높이에 맞춘다(보드는 스크롤이 없어 넘치면 잘린다). 열 이동 줄과 열 머리는 카드 위에 늘 그려진다.
        ?.let { l -> paneHeight?.let { h -> l.copy(cardsPerColumn = minOf(l.cardsPerColumn, paneRowLimit(h - NAV_HEIGHT - m.tableHeader, m, false, false).coerceAtLeast(1))) } ?: l }
    if (groups != null && layout != null) {
        BoardBody(board, groups, layout, state.config.viewPage, look)
        return
    }
    val columns = tableColumnCount(size.width, board.columns.size)
    val tableColumns = columns.takeIf { size.width >= TABLE_MIN_WIDTH && it > 1 }
    if (tableColumns != null) ViewTableHeader(board, tableColumns, m, palette, trailing = look.rowAction?.let { rowActionWidth(it, look) } ?: 0.dp)
    ViewRows(board, tableColumns, rowCap(step), look, spec.staticRows, paneHeight)
}

/** 빈 보기: 아이콘 + 안내 + '새 항목 만들기'(만들 수 있는 보기). 높이가 모자라면 [WidgetEmpty]가 한 줄로 줄인다. */
@Composable
private fun ViewEmpty(board: ViewBoard, appWidgetId: Int, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val canAdd = board.canCreate
    WidgetEmpty(
        R.drawable.ic_widget_checklist, context.getString(R.string.view_widget_empty), palette, 12f * m.spFactor,
        ctaText = if (canAdd) context.getString(R.string.view_widget_empty_cta) else null,
        ctaAction = if (canAdd) actionStartActivity(viewAddIntent(context, board.viewId, appWidgetId)) else null,
        ctaTag = "viewEmpty",
    )
}

/** 정상이면 나오지 않는다. 노션에서 보기를 찾을 수 없을 때만 눌러서 설정을 연다(다른 오류는 머리를 눌러 새로고침). 책 위젯도 함께 쓴다. */
@Composable
internal fun ViewStatusLine(board: ViewBoard, appWidgetId: Int, nowMillis: Long, m: ViewMetrics, palette: WidgetPalette, tag: String = "viewStatus") {
    val context = LocalContext.current
    val text = viewStatusText(context, board, nowMillis) ?: return
    val base = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)
    val modifier = if (board.notFound || board.unsupported) base.clickable(actionStartActivity(viewConfigIntent(context, appWidgetId))) else base
    WidgetStatusLine(
        text, error = board.notFound || board.lastError != null, palette = palette, fontSp = m.subSp * m.spFactor,
        modifier = modifier.semantics { testTag = tag },
    )
}

@Composable
private fun ViewTableHeader(board: ViewBoard, count: Int, m: ViewMetrics, palette: WidgetPalette, trailing: Dp = 0.dp) {
    Row(GlanceModifier.fillMaxWidth().height(m.tableHeader).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        board.columns.take(count).forEachIndexed { i, name ->
            val isCheck = board.tappableAt(i)?.type == "checkbox"
            Text(
                name,
                modifier = GlanceModifier.defaultWeight().padding(start = if (isCheck) CHECK_INSET else 0.dp),
                style = TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        if (trailing > 0.dp) Spacer(GlanceModifier.width(trailing)) // 행 옆 액션 칩 자리(열 머리와 칸이 어긋나지 않게)
    }
}

/** VW-03 노션 보기 위젯. Room 스냅샷만 읽는다(WG-02). */
class ViewWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 180.dp)))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.viewState()
        provideContent { ViewWidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initialBoard = initialConfig.viewId?.let { deps.views().observe(it).first() }
        val initialCalendar = if (initialBoard?.needsCalendarInfo() == true) CalendarSession.load(deps, initialConfig) else CalendarInfo()
        val initialCoverVersion = deps.covers().version.value // 읽기 전에 정해 그 사이에 받아진 표지가 다시 읽히게 한다
        val initialCovers = CoverSession.load(deps, listOf(initialBoard))
        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val board by remember(config.viewId) { config.viewId?.let { deps.views().observe(it) } ?: flowOf(null) }
                .collectAsState(initialBoard)
            val calendar = CalendarSession.info(deps, initialCalendar, config, board?.needsCalendarInfo() == true)
            val covers = CoverSession.covers(deps, initialCovers, initialCoverVersion, listOf(initialBoard), listOf(board))
            val fontScale by SystemFontScale.flow.collectAsState()
            widgetToday() // 자정에 살아 있는 세션도 다시 그리게 오늘을 구독한다(그릴 때 지금 시각으로 오늘을 계산)
            ViewWidgetContent(ViewWidgetState(board, config, System.currentTimeMillis(), calendar, covers), fontScale ?: SystemFontScale.current(context))
        }
    }
}
