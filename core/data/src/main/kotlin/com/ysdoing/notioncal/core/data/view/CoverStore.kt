package com.ysdoing.notioncal.core.data.view

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import javax.inject.Singleton

/**
 * M5 책 표지 캐시. 동기화(:core:sync)가 받아 줄여 둔 JPEG를 위젯이 읽는다. 이 클래스는 네트워크를 쓰지 않는다(파일 읽기·쓰기·정리뿐).
 * 파일 이름은 주소에서 쿼리(서명·만료 시각)를 뺀 글자의 해시다. 노션에 올린 파일은 주소가 1시간마다 새로 서명되지만
 * 경로는 같아서, 같은 표지를 다시 받지 않는다. 다른 파일로 바꾸면 경로가 달라져 새로 받는다.
 */
class CoverStore(private val dir: File, private val maxTotalBytes: Long = MAX_TOTAL_BYTES) {
    fun fileFor(url: String): File = File(dir, "${keyOf(url)}.jpg")

    fun has(url: String): Boolean = url.isNotEmpty() && fileFor(url).length() > 0

    /** 임시 파일에 쓴 뒤 바꿔치기해서, 쓰다 끊겨도 반쪽짜리 파일을 위젯이 읽지 않는다. */
    fun save(url: String, jpeg: ByteArray) {
        dir.mkdirs()
        val target = fileFor(url)
        val temp = File(dir, "${target.name}.tmp")
        temp.writeBytes(jpeg)
        target.delete()
        check(temp.renameTo(target)) { "표지 파일을 저장하지 못했습니다" }
    }

    /** [keepUrls]에 없는 파일(남은 임시 파일 포함)을 지우고, 합계가 상한을 넘으면 오래된 것부터 지운다. */
    fun prune(keepUrls: Collection<String>) {
        val keep = keepUrls.filter { it.isNotEmpty() }.map { fileFor(it).name }.toSet()
        dir.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.delete() }
        var total = dir.listFiles().orEmpty().sumOf { it.length() }
        for (file in dir.listFiles().orEmpty().sortedBy { it.lastModified() }) {
            if (total <= maxTotalBytes) break
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        /** 표지 파일 전체 합계 상한(한 장 20~40KB라 수백 장 분량). */
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

        /** 주소에서 쿼리를 뺀 글자의 SHA-256 앞 16자리. */
        fun keyOf(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.substringBefore('?').toByteArray()).take(8).joinToString("") { "%02x".format(it) }
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
