package com.ysdoing.notioncal.core.notion.http

import com.ysdoing.notioncal.core.notion.NotionApiConfig
import com.ysdoing.notioncal.core.notion.TokenProvider
import okhttp3.Interceptor
import okhttp3.Response

/** PRD §8.2 공통 헤더. 토큰이 없으면 Authorization 없이 보내 401로 처리되게 한다. */
class NotionHeadersInterceptor(private val tokenProvider: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("Notion-Version", NotionApiConfig.NOTION_VERSION)
            .header("Content-Type", "application/json")
        tokenProvider.token()?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        return chain.proceed(builder.build())
    }
}
