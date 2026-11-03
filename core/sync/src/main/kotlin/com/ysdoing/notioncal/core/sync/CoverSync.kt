package com.ysdoing.notioncal.core.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ysdoing.notioncal.core.data.view.CoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** 표지 이미지 하나를 받아 줄인 JPEG로 돌려준다. 못 받으면 null. 테스트에서는 가짜로 바꾼다. */
fun interface CoverDownload {
    suspend fun download(url: String): ByteArray?
}

/**
 * M5 표지 내려받기. **노션 토큰을 보내지 않는 별도 OkHttp 클라이언트**로 노션이 알려 준 이미지 주소를 부른다
 * (서점 이미지 서버 같은 제3자에게 토큰이 가면 안 된다, 불변 규칙). 이 클래스는 요청에 인증 헤더·쿠키를 붙이지 않는다.
 * 규칙: https만(리다이렉트로 http로 내려가도 거부), 이미지 형식만, 응답 [MAX_BYTES] 이하. 어기면 null이다.
 */
class CoverDownloader(
    private val client: OkHttpClient,
    private val scale: (ByteArray) -> ByteArray?,
    /** 시험용: MockWebServer는 http라 열어 둔다. 실제 앱에서는 항상 false. */
    private val allowInsecure: Boolean = false,
) : CoverDownload {
    override suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            fetch(url)
        } catch (e: IOException) {
            null
        }
    }

    private fun fetch(url: String): ByteArray? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        if (!httpUrl.isHttps && !allowInsecure) return null
        val request = Request.Builder().url(httpUrl).header("Accept", "image/*").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            if (!response.request.url.isHttps && !allowInsecure) return null
            val body = response.body
            if (body.contentType()?.type != "image") return null
            if (body.contentLength() > MAX_BYTES) return null
            val source = body.source()
            if (source.request(MAX_BYTES + 1)) return null // 길이를 모르는 응답도 상한을 넘으면 거부
            return scale(source.readByteArray())
        }
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}

/** 받은 이미지를 [maxWidth]×[maxHeight] 안으로 줄여(키우지 않는다) JPEG로 다시 쓴다. 이미지가 아니거나 너무 크면 null. */
object CoverScaler {
    private const val MAX_SOURCE_SIDE = 8000

    fun scale(bytes: ByteArray, maxWidth: Int = 240, maxHeight: Int = 360, quality: Int = 80): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0 || w > MAX_SOURCE_SIDE || h > MAX_SOURCE_SIDE) return null
        val decode = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(w, h, maxWidth, maxHeight) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) ?: return null
        val (tw, th) = fitWithin(bitmap.width, bitmap.height, maxWidth, maxHeight)
        val scaled = if (tw == bitmap.width && th == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, tw, th, true)
        return try {
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }
}

/** 가로세로 비율을 지키며 [maxWidth]×[maxHeight] 안에 들어오는 크기. 원본이 작으면 그대로다(키우지 않는다). */
internal fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    if (width <= maxWidth && height <= maxHeight) return width to height
    val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    return maxOf(1, (width * ratio).toInt()) to maxOf(1, (height * ratio).toInt())
}

/** 디코딩 메모리를 줄이는 2의 거듭제곱 배율: 줄인 뒤에도 목표 크기 이상이 남는 가장 큰 값. */
internal fun sampleSizeFor(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= maxWidth && height / (sample * 2) >= maxHeight) sample *= 2
    return sample
}

/**
 * 보기 동기화가 부르는 표지 처리. 받은 파일이 없는 주소만 받고(노션에 올린 파일은 주소가 새로 서명돼도 같은 파일이라 다시 받지 않는다),
 * 표지는 꾸밈이라 어떤 실패도 보기 동기화를 실패로 만들지 않는다.
 */
@Singleton
class CoverSync @Inject constructor(
    private val store: CoverStore,
    private val downloader: CoverDownload,
) {
    suspend fun fetch(urls: Collection<String>) {
        for (url in urls.filter { it.isNotEmpty() }.distinct()) {
            if (store.has(url)) continue
            try {
                downloader.download(url)?.let { store.save(url, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 파일 쓰기 실패 등: 이 표지만 건너뛴다.
            }
        }
    }

    /** 등록한 모든 보기의 표지 주소([keepUrls])에 없는 파일을 정리한다. 한 보기만 보고 지우면 다른 보기의 표지가 사라지므로 전체 동기화 끝에서만 부른다. */
    fun prune(keepUrls: Collection<String>) {
        try {
            store.prune(keepUrls)
        } catch (e: Exception) {
            // 정리는 다음 동기화에서 다시 한다.
        }
    }
}
