package com.ysdoing.notioncal.crash

import android.content.Context
import com.ysdoing.notioncal.core.data.Redactor
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroid

/**
 * PF-04 크래시·ANR 수집(Sentry). DSN은 local.properties의 `sentry.dsn`에서 빌드 때 넣고, 비어 있으면 끈다.
 * SEC-02: 보내기 직전에 토큰 형식과 따옴표 안 문자열(노션 응답·일정 제목이 섞일 수 있음)을 지운다.
 * 화면 캡처·뷰 계층·화면 녹화는 일정 내용이 보이므로 쓰지 않는다.
 */
object CrashReporting {
    fun start(context: Context, dsn: String, environment: String, release: String) {
        if (dsn.isBlank()) return
        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            options.environment = environment
            options.release = release
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableNetworkEventBreadcrumbs = false
            options.isAnrEnabled = true
            options.isAnrReportInDebug = true // 매일 쓰는 앱이 debug 빌드다
            options.isMemoryLimiterEnabled = true // S25-09: Android 17 메모리 한도 종료(API 37+)
            options.setBeforeSend { event, _ -> scrub(event) }
            options.setBeforeBreadcrumb { crumb, _ -> scrub(crumb) }
        }
    }

    /** 이벤트 안 글을 모두 가린다. 예외 종류·스택 위치는 남는다. */
    fun scrub(event: SentryEvent): SentryEvent {
        event.message?.let { m ->
            m.formatted = m.formatted?.let(Redactor::scrubReport)
            m.message = m.message?.let(Redactor::scrubReport)
            m.params = null
        }
        event.exceptions?.forEach { e -> e.value = e.value?.let(Redactor::scrubReport) }
        event.breadcrumbs?.forEach { scrub(it) }
        event.request = null
        event.user = null
        return event
    }

    /** HTTP 브레드크럼은 주소에 페이지 ID가 들어가므로 버린다. 나머지는 글만 가린다. */
    fun scrub(crumb: Breadcrumb): Breadcrumb? {
        if (crumb.type == "http" || crumb.category == "http") return null
        crumb.message = crumb.message?.let(Redactor::scrubReport)
        crumb.data.keys.toList().forEach { key ->
            val value = crumb.data[key]
            if (value is String) crumb.setData(key, Redactor.scrubReport(value))
        }
        return crumb
    }
}
