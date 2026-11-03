package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.view.CoverStore
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** M5 표지 내려받기와 저장. 토큰·쿠키를 보내지 않고, https·이미지·크기 규칙을 지킨다. */
class CoverSyncTest {
    private val server = MockWebServer()
    private val dir: File = Files.createTempDirectory("covers").toFile()
    private val jpegLike = ByteArray(1000) { it.toByte() }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun downloader(allowInsecure: Boolean = true) = CoverDownloader(OkHttpClient(), scale = { it }, allowInsecure = allowInsecure)

    private fun enqueue(body: ByteArray, type: String = "image/jpeg", code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", type).body(Buffer().write(body)).build())
    }

    @Test
    fun downloadsAnImageAndSendsNoCredentials() = runTest {
        enqueue(jpegLike)
        server.start()
        val bytes = downloader().download(server.url("/c.jpg").toString())
        assertArrayEquals(jpegLike, bytes)
        val request = server.takeRequest()
        // 제3자 이미지 서버에 노션 토큰이나 쿠키가 가면 안 된다.
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
        assertNull(request.headers["Notion-Version"])
    }

    @Test
    fun rejectsNonImagesErrorsAndHugeResponses() = runTest {
        server.start()
        enqueue(jpegLike, type = "text/html")
        assertNull(downloader().download(server.url("/a").toString()))
        enqueue(jpegLike, code = 404)
        assertNull(downloader().download(server.url("/b").toString()))
        enqueue(ByteArray((CoverDownloader.MAX_BYTES + 1).toInt()))
        assertNull(downloader().download(server.url("/c").toString()))
    }

    @Test
    fun refusesPlainHttpAndBadAddressesWithoutCallingOut() = runTest {
        server.start()
        assertNull(downloader(allowInsecure = false).download(server.url("/c.jpg").toString())) // http 주소
        assertNull(downloader().download("not a url"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun storeKeepsTheSameFileWhenOnlyTheSignatureChanges() {
        val store = CoverStore(dir)
        val a = "https://prod-files.example/uuid/cover.png?X-Amz-Signature=aaa&X-Amz-Expires=3600"
        val b = "https://prod-files.example/uuid/cover.png?X-Amz-Signature=bbb&X-Amz-Expires=3600"
        store.save(a, jpegLike)
        assertTrue(store.has(b)) // 서명만 새로 된 주소는 같은 표지
        assertFalse(store.has("https://prod-files.example/uuid/other.png"))
        assertArrayEquals(jpegLike, store.fileFor(b).readBytes())
    }

    @Test
    fun syncDownloadsOnlyMissingCoversAndPruneKeepsTheOnesInUse() = runTest {
        val store = CoverStore(dir)
        val calls = mutableListOf<String>()
        val sync = CoverSync(store, CoverDownload { calls += it; jpegLike })
        sync.fetch(listOf("https://a.example/1.jpg", "https://a.example/2.jpg", "", "https://a.example/1.jpg"))
        assertEquals(listOf("https://a.example/1.jpg", "https://a.example/2.jpg"), calls) // 빈 주소·중복 제외
        sync.fetch(listOf("https://a.example/1.jpg", "https://a.example/3.jpg"))
        assertEquals(3, calls.size) // 이미 있는 1번은 다시 받지 않는다
        sync.prune(listOf("https://a.example/1.jpg", "https://a.example/3.jpg"))
        assertTrue(store.has("https://a.example/1.jpg") && store.has("https://a.example/3.jpg"))
        assertFalse(store.has("https://a.example/2.jpg")) // 어느 보기에도 없는 표지는 지운다
    }

    @Test
    fun aFailingDownloadOrWriteNeverBreaksTheSync() = runTest {
        val sync = CoverSync(CoverStore(dir), CoverDownload { error("boom") })
        sync.fetch(listOf("https://a.example/1.jpg")) // 예외가 밖으로 나오지 않는다
        val none = CoverSync(CoverStore(dir), CoverDownload { null })
        none.fetch(listOf("https://a.example/2.jpg"))
        assertFalse(CoverStore(dir).has("https://a.example/2.jpg"))
    }

    @Test
    fun storeEnforcesTheTotalSizeCapDroppingOldestFirst() {
        val store = CoverStore(dir, maxTotalBytes = 2500)
        listOf("1", "2", "3").forEachIndexed { i, id ->
            store.save("https://a.example/$id.jpg", jpegLike)
            store.fileFor("https://a.example/$id.jpg").setLastModified(1000L + i)
        }
        store.prune(listOf("https://a.example/1.jpg", "https://a.example/2.jpg", "https://a.example/3.jpg"))
        assertFalse(store.has("https://a.example/1.jpg")) // 3000바이트 > 2500: 가장 오래된 것부터
        assertTrue(store.has("https://a.example/2.jpg") && store.has("https://a.example/3.jpg"))
    }

    @Test
    fun scalingMathNeverEnlargesAndKeepsTheAspectRatio() {
        assertEquals(240 to 360, fitWithin(240, 360, 240, 360))
        assertEquals(100 to 150, fitWithin(100, 150, 240, 360)) // 키우지 않는다
        assertEquals(240 to 360, fitWithin(480, 720, 240, 360))
        assertEquals(180 to 360, fitWithin(500, 1000, 240, 360)) // 높이에 맞춘다
        assertEquals(1, sampleSizeFor(300, 400, 240, 360))
        assertEquals(4, sampleSizeFor(1000, 1500, 240, 360)) // 줄인 뒤에도 목표 이상
    }
}
