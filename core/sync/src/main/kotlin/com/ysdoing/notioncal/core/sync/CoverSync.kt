// minSdk 31이라 플랫폼 ExifInterface(스트림 생성자)가 이미 보안 수정을 포함한다. androidx 버전은 그 이전 API를 위한 것이라 새 라이브러리를 들이지 않는다.
@file:Suppress("ExifInterface")

package com.ysdoing.notioncal.core.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.core.graphics.createBitmap
import com.ysdoing.notioncal.core.data.view.CoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
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
 * 규칙: https만, 공개 주소만([guard]와 클라이언트의 [PublicOnlyDns]), 이미지 형식만, 응답 [MAX_BYTES] 이하. 어기면 null이다.
 * 리다이렉트는 클라이언트가 따라가지 않게 하고(`followRedirects(false)`) 여기서 [MAX_REDIRECTS]번까지 한 단계씩 같은 규칙으로 따라간다.
 */
class CoverDownloader(
    private val client: OkHttpClient,
    private val scale: (ByteArray) -> ByteArray?,
    /** 시험용: MockWebServer는 http라 열어 둔다. 실제 앱에서는 항상 false. */
    private val allowInsecure: Boolean = false,
    /** 요청 전에 주소를 검사한다(IP 주소를 직접 적은 경우). 시험에서만 바꾼다. */
    private val guard: (HttpUrl) -> Boolean = PublicHosts::isAllowed,
) : CoverDownload {
    override suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            fetch(url)
        } catch (e: IOException) {
            null
        }
    }

    private fun fetch(url: String): ByteArray? {
        var current = url.toHttpUrlOrNull() ?: return null
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(current)) return null
            val request = Request.Builder().url(current).header("Accept", "image/*").build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    current = response.header("Location")?.let { response.request.url.resolve(it) } ?: return null
                    return@repeat
                }
                return read(response)
            }
        }
        return null // 리다이렉트가 너무 많다
    }

    private fun allowed(url: HttpUrl): Boolean = (url.isHttps || allowInsecure) && guard(url)

    private fun read(response: Response): ByteArray? {
        if (!response.isSuccessful) return null
        val body = response.body
        if (body.contentType()?.type != "image") return null
        if (body.contentLength() > MAX_BYTES) return null
        val source = body.source()
        if (source.request(MAX_BYTES + 1)) return null // 길이를 모르는 응답도 상한을 넘으면 거부
        return scale(source.readByteArray())
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        const val MAX_REDIRECTS = 3
    }
}

/**
 * 표지 내려받기 전용 클라이언트. 노션 클라이언트와 달리 인터셉터가 없다(토큰 헤더 없음). 방어 설정:
 * - [PublicOnlyDns]: 이름이 사설·로컬 주소로 풀리면 연결하지 않는다.
 * - `proxy(NO_PROXY)`: 시스템 프록시를 쓰면 대상 이름을 프록시가 풀어 위 DNS 방어를 지나치므로 프록시를 쓰지 않는다(프록시가 필수인 망에서는 표지만 못 받는다).
 * - `followRedirects(false)`: 리다이렉트는 [CoverDownloader]가 한 단계씩 같은 규칙으로 따라간다.
 */
fun coverHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .proxy(Proxy.NO_PROXY)
    .dns(PublicOnlyDns)
    .followRedirects(false)
    .build()

/**
 * 받은 이미지를 [maxWidth]×[maxHeight] 안으로 줄여(키우지 않는다) JPEG로 다시 쓴다. 이미지가 아니거나 너무 크면 null.
 * JPEG로 다시 쓰면 EXIF 방향 태그가 사라지므로 **방향을 먼저 바로잡고**(휴대폰 사진 표지가 눕지 않게), 투명한 PNG는 **흰 바탕**에 올려 쓴다(검게 나오지 않게).
 */
object CoverScaler {
    private const val MAX_SOURCE_SIDE = 8000

    fun scale(bytes: ByteArray, maxWidth: Int = 240, maxHeight: Int = 360, quality: Int = 80): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0 || w > MAX_SOURCE_SIDE || h > MAX_SOURCE_SIDE) return null
        val decode = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(w, h, maxWidth, maxHeight) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) ?: return null
        val upright = rotated(decoded, exifRotation(bytes))
        val (tw, th) = fitWithin(upright.width, upright.height, maxWidth, maxHeight)
        val sized = if (tw == upright.width && th == upright.height) upright else Bitmap.createScaledBitmap(upright, tw, th, true)
        val flat = onWhite(sized)
        return try {
            ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally {
            listOf(flat, sized, upright, decoded).distinct().forEach { it.recycle() }
        }
    }

    /** EXIF 방향 태그가 요구하는 회전 각도(0·90·180·270). 읽을 수 없으면 0. */
    internal fun exifRotation(bytes: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    }

    private fun rotated(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun onWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = createBitmap(bitmap.width, bitmap.height)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
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
 * 표지는 꾸밈이라 어떤 실패도 보기 동기화를 실패로 만들지 않는다. 받지 못한 주소는 [CoverStore.FAILURE_RETRY_MILLIS] 동안 다시 두드리지 않는다
 * (없는 주소·막힌 주소를 동기화마다 기다리지 않게). 동시에 [PARALLEL]개까지 받는다.
 */
@Singleton
class CoverSync @Inject constructor(
    private val store: CoverStore,
    private val downloader: CoverDownload,
) {
    /** 새로 받아 저장한 표지가 있으면 true(위젯을 다시 그려야 한다). */
    suspend fun fetch(urls: Collection<String>): Boolean = coroutineScope {
        val todo = urls.filter { it.isNotEmpty() }.distinct().filter { !store.has(it) && !store.failedRecently(it) }
        val gate = Semaphore(PARALLEL)
        todo.map { url -> async { gate.withPermit { fetchOne(url) } } }.awaitAll().any { it }
    }

    private suspend fun fetchOne(url: String): Boolean {
        val saved = try {
            downloader.download(url)?.let { store.save(url, it) } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // 파일 쓰기 실패 등: 이 표지만 건너뛴다.
        }
        if (!saved) markFailed(url)
        return saved
    }

    private fun markFailed(url: String) = try {
        store.markFailed(url)
    } catch (e: IOException) {
        // 표시를 못 써도 다음 동기화에서 다시 시도할 뿐이다.
    }

    /** 등록한 모든 보기의 표지 주소([keepUrls])에 없는 파일을 정리한다. 한 보기만 보고 지우면 다른 보기의 표지가 사라지므로 전체 동기화 끝에서만 부른다. */
    fun prune(keepUrls: Collection<String>) {
        try {
            store.prune(keepUrls)
        } catch (e: IOException) {
            // 정리는 다음 동기화에서 다시 한다.
        }
    }

    companion object {
        private const val PARALLEL = 3
    }
}
