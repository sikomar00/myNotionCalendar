package com.ysdoing.notioncal.core.data.recipe

import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetConfigRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 홈 화면에 놓아 달라고 요청한(핀) 레시피 구성. 위젯 번호는 놓인 뒤에 정해지니 그때까지 [requestedAt]과 함께 보관한다. */
@Serializable
data class PendingRecipe(val layoutJson: String, val formJson: String, val requestedAt: Long)

/** [PendingRecipe]의 저장 글자와 위젯 설정으로의 변환(순수). */
object PendingRecipes {
    /** 요청한 지 이 시간(5분)이 지나면 쓰지 않는다: 사용자가 핀 대화상자를 닫고 한참 뒤 다른 노션 위젯을 놓을 때 옛 레시피가 붙지 않게 한다. */
    const val TTL_MS = 5 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(pending: PendingRecipe): String = json.encodeToString(PendingRecipe.serializer(), pending)

    /** 비었거나 깨진 글자는 없음으로 읽는다. */
    fun decode(text: String?): PendingRecipe? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(PendingRecipe.serializer(), it) }.getOrNull() }

    /** 새 위젯 [appWidgetId]의 설정. 대기 레시피가 없거나 [TTL_MS]가 지났거나 시계가 거꾸로 갔으면 null이다. */
    fun configFor(appWidgetId: Int, pending: PendingRecipe?, now: Long): WidgetConfig? =
        pending?.takeIf { now - it.requestedAt in 0..TTL_MS }?.let { WidgetConfig(appWidgetId, layoutJson = it.layoutJson, formJson = it.formJson) }
}

/**
 * 핀 요청 전후의 대기 레시피를 위젯 설정에 옮기는 일. 옮기는 길이 둘이다(런처마다 순서가 달라 둘 다 둔다):
 * 핀 성공 콜백([applyToNew], 가져가며 지움)과 그 위젯의 설정 화면이 열릴 때([startFor], 보기만 하고 저장 뒤 [consume]).
 * 한 번만 쓰이고, 이미 저장된 설정은 덮지 않는다.
 */
@Singleton
class RecipeClaims @Inject constructor(private val widgets: WidgetConfigRepository, private val settings: SettingsRepository) {
    /** 핀을 요청하기 직전에 구성을 보관한다(앞선 대기는 바뀐다). */
    suspend fun request(config: RecipeConfig, now: Long) {
        settings.setPendingRecipe(PendingRecipe(config.layoutJson, config.formJson, now))
    }

    /** 핀 성공 콜백: 대기를 가져가고(그 위젯이 이미 저장돼 있어도 지운다), 저장된 설정이 없는 위젯이면 레시피 구성으로 저장해 돌려준다. */
    suspend fun applyToNew(appWidgetId: Int, now: Long): WidgetConfig? {
        val pending = settings.takePendingRecipe()
        if (widgets.saved(appWidgetId) != null) return null
        return PendingRecipes.configFor(appWidgetId, pending, now)?.also { widgets.save(it) }
    }

    /**
     * 설정 화면: 저장된 설정이 없는 새 위젯이면 대기 레시피를 시작 설정으로 돌려준다. 지우지 않고 보기만 하니 화면이 다시 만들어져도(회전·글꼴 변경)
     * 같은 구성으로 시작하고, 지우는 것은 사용자가 '저장'을 누른 뒤의 [consume]이다. 다시 설정하는 위젯은 건드리지 않는다.
     */
    suspend fun startFor(appWidgetId: Int, now: Long): WidgetConfig? {
        if (widgets.saved(appWidgetId) != null) return null
        return PendingRecipes.configFor(appWidgetId, settings.pendingRecipe(), now)
    }

    /** 설정 화면이 저장한 뒤: 대기 레시피를 지운다(뒤늦은 핀 콜백이나 다른 새 위젯이 가져가지 않게). */
    suspend fun consume() {
        settings.takePendingRecipe()
    }
}
