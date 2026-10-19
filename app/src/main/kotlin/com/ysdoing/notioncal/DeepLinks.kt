package com.ysdoing.notioncal

import java.net.URI
import java.time.LocalDate

/** EX-12 바로 여는 링크가 여는 화면. 화면 열기만 하고 체크·생성 같은 쓰기는 하지 않는다(다른 앱도 열 수 있으므로). */
sealed interface DeepLink {
    data object Today : DeepLink
    data object Habit : DeepLink
    data object Ledger : DeepLink
    data class Date(val date: LocalDate) : DeepLink
    data object Dday : DeepLink
}

/**
 * EX-12 `notioncal://today`, `://habit`, `://ledger`, `://date/2026-10-20`, `://dday`.
 * 정확히 이 모양만 받는다. 앱 안 인텐트가 쓰는 data URI(`notioncal://tab/…`, `://ledger/add/…` 등)는 action이 달라
 * 여기로 오지 않지만, 와도 길이가 맞지 않아 무시된다.
 */
object DeepLinks {
    const val SCHEME = "notioncal"

    fun parse(uri: String?): DeepLink? {
        val parsed = uri?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        if (!parsed.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (parsed.query != null || parsed.fragment != null) return null
        val segments = parsed.path.orEmpty().split('/').filter { it.isNotEmpty() }
        return when (parsed.host?.lowercase()) {
            "today" -> DeepLink.Today.takeIf { segments.isEmpty() }
            "habit" -> DeepLink.Habit.takeIf { segments.isEmpty() }
            "ledger" -> DeepLink.Ledger.takeIf { segments.isEmpty() }
            "dday" -> DeepLink.Dday.takeIf { segments.isEmpty() }
            "date" -> segments.singleOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let(DeepLink::Date)
            else -> null
        }
    }
}
