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

    private fun downloader(allowInsecure: Boolean = true) =
        CoverDownloader(OkHttpClient.Builder().followRedirects(false).build(), scale = { it }, allowInsecure = allowInsecure, guard = { true }) // MockWebServer는 로컬 http

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
    fun followsRedirectsOneHopAtATimeAndStopsAfterThree() = runTest {
        server.start()
        val final = server.url("/final.jpg")
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", final.toString()).build())
        enqueue(jpegLike)
        assertArrayEquals(jpegLike, downloader().download(server.url("/start").toString()))
        repeat(5) { server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/loop").toString()).build()) }
        assertNull(downloader().download(server.url("/loop").toString())) // 3번을 넘기면 거부
    }

    @Test
    fun neverSendsARequestToALocalOrPrivateAddress() = runTest {
        server.start()
        // 실제 방어와 같은 구성: 이름은 PublicOnlyDns가, IP 주소를 직접 적은 주소는 기본 guard가 요청 전에 막는다.
        val real = CoverDownloader(OkHttpClient.Builder().dns(PublicOnlyDns).followRedirects(false).build(), scale = { it }, allowInsecure = true)
        enqueue(jpegLike)
        assertNull(real.download(server.url("/c.jpg").toString())) // localhost → 루프백으로 풀려 연결하지 않는다
        assertEquals(0, server.requestCount)
        listOf("https://192.168.0.1/a.jpg", "https://10.0.0.5/a.jpg", "https://169.254.169.254/latest", "https://[::1]/a.jpg", "https://[fd00::1]/a.jpg", "https://100.64.0.1/a.jpg")
            .forEach { assertNull(it, real.download(it)) }
    }

    @Test
    fun aRedirectToAPrivateAddressIsRefused() = runTest {
        server.start()
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://192.168.0.1/admin").build())
        // 첫 요청은 허용(시험용 guard)하되 리다이렉트 목적지는 실제 규칙으로 막는다.
        var calls = 0
        val d = CoverDownloader(
            OkHttpClient.Builder().followRedirects(false).build(), scale = { it }, allowInsecure = true,
            guard = { url -> calls++ == 0 || PublicHosts.isAllowed(url) },
        )
        assertNull(d.download(server.url("/start").toString()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun publicAddressRule() {
        fun public(ip: String) = PublicHosts.isPublic(java.net.InetAddress.getByName(ip))
        listOf(
            "8.8.8.8", "1.1.1.1", "93.184.216.34", "2606:4700:4700::1111", "100.63.255.255", "100.128.0.1", "192.0.1.1", "198.20.0.1", "223.255.255.255",
            "2002:0808:0808::1", // 6to4로 감춘 8.8.8.8
            "64:ff9b::0808:0808", // NAT64로 감춘 8.8.8.8
        ).forEach { assertTrue(it, public(it)) }
        listOf(
            "127.0.0.1", "0.0.0.0", "0.1.2.3", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "224.0.0.1",
            "100.64.0.1", "100.127.255.255", "192.0.0.5", "198.18.0.1", "198.19.255.255", "240.0.0.1", "255.255.255.255",
            "::1", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1",
            "::ffff:192.168.0.1", // IPv4 매핑(자바가 IPv4로 바꿈)
            "::192.168.0.1", // IPv4 호환
            "64:ff9b::c0a8:1", // NAT64로 감춘 192.168.0.1
            "64:ff9b::7f00:1", // NAT64로 감춘 127.0.0.1
            "2002:c0a8:0001::1", // 6to4로 감춘 192.168.0.1
            "2002:7f00:0001::", // 6to4로 감춘 127.0.0.1
        ).forEach { assertFalse(it, public(it)) }
    }

    @Test
    fun coverClientBypassesNoGuardViaAProxyOrARedirect() {
        val client = coverHttpClient()
        assertEquals(java.net.Proxy.NO_PROXY, client.proxy) // 프록시가 대상 이름을 풀면 DNS 방어를 지나친다
        assertTrue(client.dns === PublicOnlyDns)
        assertFalse(client.followRedirects) // 리다이렉트는 CoverDownloader가 규칙을 적용하며 따라간다
        assertFalse(client.fastFallback) // 소켓 태그(StrictMode)가 붙으려면 소켓이 호출 스레드에서 열려야 한다
        // 노션 토큰 헤더를 붙이는 인터셉터가 없다: 있는 것은 소켓에 태그만 다는 인터셉터 하나뿐이다.
        assertTrue(client.networkInterceptors.isEmpty())
        assertTrue(client.interceptors.all { it is com.ysdoing.notioncal.core.notion.http.SocketTagInterceptor })
    }

    /** 위젯이 메인 스레드에서 처음 만들어도 디스크를 건드리지 않는다(폴더는 처음 파일을 쓸 때 구함, StrictMode). */
    @Test
    fun creatingTheStoreAndReadingItsVersionDoesNotTouchTheFolder() {
        var asked = 0
        val store = CoverStore({ asked++; dir })
        assertEquals(0, store.version.value)
        assertEquals(0, asked)
        store.save("https://img.example/1.jpg", jpegLike)
        assertEquals(1, asked)
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
        val store = CoverStore(dir, pruneGraceMillis = 0)
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
    fun aFailingDownloadOrWriteNeverBreaksTheSyncAndIsNotRetriedRightAway() = runTest {
        var calls = 0
        val store = CoverStore(dir)
        val sync = CoverSync(store, CoverDownload { calls++; error("boom") })
        assertFalse(sync.fetch(listOf("https://a.example/1.jpg"))) // 예외가 밖으로 나오지 않는다
        assertEquals(1, calls)
        assertTrue(store.failedRecently("https://a.example/1.jpg"))
        sync.fetch(listOf("https://a.example/1.jpg"))
        assertEquals(1, calls) // 실패한 주소는 한동안 다시 두드리지 않는다(동기화마다 기다리지 않게)
        // 시간이 지나면(여기서는 표시를 지워) 다시 시도한다
        File(dir, "${CoverStore.keyOf("https://a.example/1.jpg")}.fail").setLastModified(1000L)
        sync.fetch(listOf("https://a.example/1.jpg"))
        assertEquals(2, calls)
    }

    @Test
    fun fetchReportsWhetherAnythingNewWasSaved() = runTest {
        val sync = CoverSync(CoverStore(dir), CoverDownload { jpegLike })
        assertTrue(sync.fetch(listOf("https://a.example/1.jpg")))
        assertFalse(sync.fetch(listOf("https://a.example/1.jpg"))) // 이미 있어 새로 받은 것이 없다
        assertFalse(sync.fetch(emptyList()))
    }

    @Test
    fun downloadsRunAtMostThreeAtATime() = runTest {
        var running = 0
        var peak = 0
        val sync = CoverSync(CoverStore(dir), CoverDownload {
            running++
            peak = maxOf(peak, running)
            kotlinx.coroutines.yield()
            running--
            jpegLike
        })
        sync.fetch((1..10).map { "https://a.example/$it.jpg" })
        assertTrue("동시 $peak", peak in 2..3)
    }

    @Test
    fun coversWhoseIdentityIsInTheQueryStayDistinctWhileSignaturesAreIgnored() {
        val a = "https://books.example/content?id=AAA&printsec=frontcover"
        val b = "https://books.example/content?id=BBB&printsec=frontcover"
        assertFalse(CoverStore.keyOf(a) == CoverStore.keyOf(b)) // 책마다 다른 표지(쿼리에 책 번호)
        assertEquals(CoverStore.keyOf(a), CoverStore.keyOf("https://books.example/content?printsec=frontcover&id=AAA")) // 순서 무관
        val signed1 = "https://prod-files.example/u/c.png?X-Amz-Signature=aaa&X-Amz-Expires=3600&x-id=GetObject"
        val signed2 = "https://prod-files.example/u/c.png?x-amz-signature=bbb&X-Amz-Date=2026&X-Amz-Expires=7200"
        assertEquals(CoverStore.keyOf(signed1), CoverStore.keyOf(signed2)) // 서명은 바뀌어도 같은 파일
        assertFalse(CoverStore.keyOf(signed1) == CoverStore.keyOf("https://prod-files.example/u/other.png?X-Amz-Signature=aaa"))
    }

    @Test
    fun pruneNeverDeletesFilesWrittenJustNow() {
        // 정리가 도는 사이 다른 동기화가 막 저장한 표지·임시 파일을 지우지 않는다(기본 유예 10분).
        val store = CoverStore(dir)
        store.save("https://a.example/new.jpg", jpegLike)
        File(dir, "half.jpg.tmp").writeBytes(jpegLike)
        store.prune(emptyList())
        assertTrue(store.has("https://a.example/new.jpg"))
        assertTrue(File(dir, "half.jpg.tmp").exists())
        // 오래된 것은 지운다
        store.fileFor("https://a.example/new.jpg").setLastModified(1000L)
        File(dir, "half.jpg.tmp").setLastModified(1000L)
        store.prune(emptyList())
        assertFalse(store.has("https://a.example/new.jpg"))
        assertFalse(File(dir, "half.jpg.tmp").exists())
    }

    @Test
    fun versionRisesOnlyWhenCoverFilesChange() {
        val store = CoverStore(dir, pruneGraceMillis = 0)
        val start = store.version.value
        store.save("https://a.example/1.jpg", jpegLike)
        assertEquals(start + 1, store.version.value) // 새 표지가 저장되면 위젯이 다시 읽는다
        store.prune(listOf("https://a.example/1.jpg"))
        assertEquals(start + 1, store.version.value) // 지울 것이 없으면 그대로
        store.prune(emptyList())
        assertEquals(start + 2, store.version.value) // 파일을 지웠다
        assertFalse(store.has("https://a.example/1.jpg"))
    }

    @Test
    fun theDefaultCacheCapIsFiftyMegabytesAndGalleriesGetMoreCoversThanOtherViews() {
        assertEquals(50L * 1024 * 1024, CoverStore.MAX_TOTAL_BYTES)
        assertEquals(9, CoverStore.coverLimit("gallery"))
        assertEquals(6, CoverStore.coverLimit("table"))
        assertEquals(6, CoverStore.coverLimit(null))
    }

    @Test
    fun storeEnforcesTheTotalSizeCapDroppingOldestFirst() {
        val store = CoverStore(dir, maxTotalBytes = 2500, pruneGraceMillis = 0)
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
