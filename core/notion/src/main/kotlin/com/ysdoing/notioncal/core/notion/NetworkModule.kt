package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.http.NotionHeadersInterceptor
import com.ysdoing.notioncal.core.notion.http.RateLimitInterceptor
import com.ysdoing.notioncal.core.notion.http.RetryInterceptor
import com.ysdoing.notioncal.core.notion.http.SocketTagInterceptor
import com.ysdoing.notioncal.core.notion.http.TokenBucket
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = NotionJson

    @Provides
    fun provideNotionReader(client: NotionClient): NotionReader = client

    @Provides
    @Singleton
    fun provideNotionApi(tokenProvider: TokenProvider, json: Json): NotionApi =
        createNotionApi(NotionApiConfig.BASE_URL.toHttpUrl(), tokenProvider, json)
}

val NotionJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** 데이터 사용량 통계에서 노션 동기화 트래픽을 구분하는 소켓 태그(StrictMode 소켓 태그 검사 대응). */
const val NOTION_SOCKET_TAG = 0x4E01

/**
 * 인터셉터 순서: 소켓 태그 → 헤더 → 재시도 → 속도 제한(재시도마다 토큰을 소비). 테스트에서도 같은 구성을 쓴다.
 * [socketTag]가 null이면 태그를 붙이지 않는다(android.net 없이 도는 JVM 단위 테스트용).
 */
fun createNotionApi(
    baseUrl: HttpUrl,
    tokenProvider: TokenProvider,
    json: Json = NotionJson,
    retry: RetryInterceptor = RetryInterceptor(),
    bucket: TokenBucket = TokenBucket(),
    socketTag: Int? = NOTION_SOCKET_TAG,
): NotionApi {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 빠른 대체 연결은 별도 스레드에서 소켓을 열어 스레드 태그가 안 붙는다. 노션 한 곳만 접속하므로 끈다.
        .fastFallback(false)
        .apply { socketTag?.let { addInterceptor(SocketTagInterceptor(it)) } }
        .addInterceptor(NotionHeadersInterceptor(tokenProvider))
        .addInterceptor(retry)
        .addInterceptor(RateLimitInterceptor(bucket))
        .build()
    return Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(NotionApi::class.java)
}
