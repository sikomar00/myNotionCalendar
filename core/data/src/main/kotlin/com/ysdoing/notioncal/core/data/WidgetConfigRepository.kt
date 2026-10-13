package com.ysdoing.notioncal.core.data

import com.ysdoing.notioncal.core.data.db.WidgetConfigDao
import com.ysdoing.notioncal.core.data.db.WidgetConfigEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class WidgetTheme { SYSTEM, LIGHT, DARK }

enum class LineSpacing { COMPACT, NORMAL, WIDE }

/** 위젯 하나의 설정(WG-04)과 보고 있는 달(WI-04). */
data class WidgetConfig(
    val appWidgetId: Int,
    val monthOffset: Int = 0,
    /** 비어 있으면 앱에서 켠 DB 전체. */
    val dataSourceIds: Set<String> = emptySet(),
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
    val opacity: Int = 100,
    val fontScale: Int = 100,
    val maxPerCell: Int = 0,
    val lineSpacing: LineSpacing = LineSpacing.NORMAL,
    val hideDone: Boolean = false,
) {
    companion object {
        val FONT_SCALE_RANGE = 70..150
        val MAX_PER_CELL_RANGE = 0..6
    }
}

internal fun WidgetConfigEntity.toModel() = WidgetConfig(
    appWidgetId = appWidgetId,
    monthOffset = monthOffset,
    dataSourceIds = dataSourceIds.split(',').filter { it.isNotBlank() }.toSet(),
    theme = runCatching { WidgetTheme.valueOf(theme) }.getOrDefault(WidgetTheme.SYSTEM),
    opacity = opacity.coerceIn(0, 100),
    fontScale = fontScale.coerceIn(WidgetConfig.FONT_SCALE_RANGE),
    maxPerCell = maxPerCell.coerceIn(WidgetConfig.MAX_PER_CELL_RANGE),
    lineSpacing = runCatching { LineSpacing.valueOf(lineSpacing) }.getOrDefault(LineSpacing.NORMAL),
    hideDone = hideDone,
)

internal fun WidgetConfig.toEntity() = WidgetConfigEntity(
    appWidgetId = appWidgetId,
    monthOffset = monthOffset,
    dataSourceIds = dataSourceIds.sorted().joinToString(","),
    theme = theme.name,
    opacity = opacity.coerceIn(0, 100),
    fontScale = fontScale.coerceIn(WidgetConfig.FONT_SCALE_RANGE),
    maxPerCell = maxPerCell.coerceIn(WidgetConfig.MAX_PER_CELL_RANGE),
    lineSpacing = lineSpacing.name,
    hideDone = hideDone,
)

@Singleton
class WidgetConfigRepository @Inject constructor(private val dao: WidgetConfigDao) {
    /** 저장된 설정이 없으면 기본값(설정 화면을 건너뛰고 놓은 위젯). */
    suspend fun get(appWidgetId: Int): WidgetConfig = dao.get(appWidgetId)?.toModel() ?: WidgetConfig(appWidgetId)

    /** 위젯 화면이 구독한다. 달 이동·설정 저장이 바로 반영된다. */
    fun observe(appWidgetId: Int): Flow<WidgetConfig> =
        dao.observe(appWidgetId).map { it?.toModel() ?: WidgetConfig(appWidgetId) }.distinctUntilChanged()

    suspend fun save(config: WidgetConfig) = dao.upsert(config.toEntity())

    /** WI-04: 이전(-1)·다음(+1) 달. */
    suspend fun shiftMonth(appWidgetId: Int, delta: Int) {
        val current = get(appWidgetId)
        save(current.copy(monthOffset = current.monthOffset + delta))
    }

    /** WI-04: 오늘 버튼. */
    suspend fun resetMonth(appWidgetId: Int) = save(get(appWidgetId).copy(monthOffset = 0))

    suspend fun delete(appWidgetIds: List<Int>) = dao.delete(appWidgetIds)
}
