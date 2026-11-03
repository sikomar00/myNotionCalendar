package com.ysdoing.notioncal.core.data.view

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import javax.inject.Singleton

/**
 * M5 책 표지 캐시. 동기화(:core:sync)가 받아 줄여 둔 JPEG를 위젯이 읽는다. 이 클래스는 네트워크를 쓰지 않는다(파일 읽기·쓰기·정리뿐).
 * 파일 이름은 주소에서 **서명 쿼리(X-Amz-*, x-id)만** 뺀 글자의 해시다. 노션에 올린 파일은 주소가 1시간마다 새로 서명되지만
 * 경로는 같아서 같은 표지를 다시 받지 않는다. 책을 가리키는 값이 쿼리에 든 외부 주소(`?id=…`)는 서로 다른 파일이다.
 */
class CoverStore(
    private val dir: File,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    /** 이보다 새로 쓴 파일은 정리하지 않는다: 정리가 도는 사이 다른 동기화가 막 저장한 표지·임시 파일을 지우지 않게. */
    private val pruneGraceMillis: Long = PRUNE_GRACE_MILLIS,
) {
    private val lock = Any()

    fun fileFor(url: String): File = File(dir, "${keyOf(url)}.jpg")

    fun has(url: String): Boolean = url.isNotEmpty() && fileFor(url).length() > 0

    /** 임시 파일에 쓴 뒤 바꿔치기해서, 쓰다 끊겨도 반쪽짜리 파일을 위젯이 읽지 않는다. */
    fun save(url: String, jpeg: ByteArray) = synchronized(lock) {
        dir.mkdirs()
        val target = fileFor(url)
        val temp = File(dir, "${target.name}.tmp")
        temp.writeBytes(jpeg)
        target.delete()
        check(temp.renameTo(target)) { "표지 파일을 저장하지 못했습니다" }
        failureMarker(url).delete()
        _version.value++
    }

    /** 받지 못한 표지를 [FAILURE_RETRY_MILLIS] 동안 다시 받지 않게 표시한다(없는 주소를 동기화마다 두드리지 않게). */
    fun markFailed(url: String) = synchronized(lock) {
        if (url.isEmpty()) return@synchronized
        dir.mkdirs()
        failureMarker(url).writeText("")
    }

    fun failedRecently(url: String, now: Long = System.currentTimeMillis()): Boolean {
        val marker = failureMarker(url)
        return marker.isFile && now - marker.lastModified() < FAILURE_RETRY_MILLIS
    }

    private val _version = MutableStateFlow(0)

    /**
     * 받아 둔 표지가 바뀔 때마다(저장·정리로 지움) 올라가는 번호. 메모리 값이라 읽어도 디스크를 건드리지 않는다.
     * 위젯은 이것을 구독해 보기 행이 그대로여도 표지만 나중에 받아졌을 때 다시 읽는다(위젯을 그리는 도중 메인 스레드에서 파일을 읽지 않는다, StrictMode).
     */
    val version: StateFlow<Int> get() = _version

    /**
     * [keepUrls]에 없는 파일(남은 임시 파일·실패 표시 포함)을 지우고, 합계가 상한을 넘으면 오래된 것부터 지운다.
     * 방금(유예 시간 안에) 쓴 파일은 건드리지 않는다.
     */
    fun prune(keepUrls: Collection<String>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val keep = keepUrls.filter { it.isNotEmpty() }.flatMap { listOf(fileFor(it).name, failureMarker(it).name) }.toSet()
        val removed = dir.listFiles().orEmpty().filter { it.name !in keep && now - it.lastModified() >= pruneGraceMillis }.count { it.delete() }
        var total = dir.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }.sumOf { it.length() }
        var trimmed = 0
        for (file in dir.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }.sortedBy { it.lastModified() }) {
            if (total <= maxTotalBytes) break
            total -= file.length()
            if (file.delete()) trimmed++
        }
        if (removed + trimmed > 0) _version.value++
    }

    private fun failureMarker(url: String) = File(dir, "${keyOf(url)}.fail")

    companion object {
        /** 표지 파일 전체 합계 상한(한 장 20~40KB라 수백 장 분량). */
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

        /** 한 보기에서 받는 표지 수 상한. 책 위젯이 한꺼번에 그리는 수와 같다(위젯이 그리지 않는 표지는 받지 않는다). */
        const val MAX_COVERS_PER_VIEW = 6

        const val PRUNE_GRACE_MILLIS = 10 * 60 * 1000L
        const val FAILURE_RETRY_MILLIS = 6 * 60 * 60 * 1000L

        /**
         * 주소의 SHA-256 앞 16자리. 쿼리에서는 서명 값(`X-Amz-*`, `x-id`)만 빼고 나머지는 정렬해 남긴다:
         * 서명은 시간마다 바뀌지만 `?id=책번호` 같은 값은 표지를 가리키는 값이다.
         */
        fun keyOf(url: String): String {
            val noFragment = url.substringBefore('#')
            val query = noFragment.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !isSignature(it.substringBefore('=')) }.sorted()
            val stable = noFragment.substringBefore('?') + if (query.isEmpty()) "" else "?" + query.joinToString("&")
            return MessageDigest.getInstance("SHA-256").digest(stable.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        }

        private fun isSignature(name: String) = name.startsWith("x-amz-", ignoreCase = true) || name.equals("x-id", ignoreCase = true)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object CoverModule {
    /** 자동 백업에 넣지 않는 폴더(다시 받을 수 있는 이미지라 백업이 부풀지 않게). */
    @Provides
    @Singleton
    fun provideCoverStore(@ApplicationContext context: Context): CoverStore = CoverStore(File(context.noBackupFilesDir, "covers"))
}
