package hk.uwu.reareye.repository.presetpack

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PresetPackDownloaderTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
    private val chunk = 128 * 1024

    @Volatile
    private var payload = ByteArray(chunk * 4 + 127) { (it % 251).toByte() }

    @Volatile
    private var etag = "\"release-1\""

    @Volatile
    private var truncate = false

    @Volatile
    private var ranges = true

    @Volatile
    private var invalidRange = false

    @Volatile
    private var omitValidator = false

    @Volatile
    private var gate: CountDownLatch? = null

    @Volatile
    private var holdBody = false
    private val requests = CopyOnWriteArrayList<String>()
    private val validators = CopyOnWriteArrayList<String>()
    private val url get() = server.url("/pack.rpp").toString()

    @Before
    fun setUp() {
        directory = temporary.newFolder("download")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    private fun downloader() = PresetPackDownloader(client, directory, chunkBytes = chunk.toLong())

    private suspend fun fetch(progress: (Long, Long) -> Unit = { _, _ -> }): File =
        downloader().download(url, "v1/pack.rpp", payload.size.toLong(), progress)

    private fun respond(request: RecordedRequest): MockResponse {
        val range = request.headers["Range"]
        requests.add(range ?: "full")
        request.headers["If-Range"]?.let { validators.add(it) }
        val response = MockResponse.Builder()
        if (!omitValidator) response.setHeader("ETag", etag)
        if (!ranges || range == null) {
            return response.body(Buffer().write(payload)).build()
        }
        val bounds = range.removePrefix("bytes=").split('-').map { it.toInt() }
        val (start, end) = bounds
        val probe = range == "bytes=0-0"
        if (!probe) gate?.let { latch ->
            latch.countDown()
            check(latch.await(5, TimeUnit.SECONDS)) { "Requests did not run concurrently" }
        }
        val advertisedStart = if (invalidRange && !probe) start + 1 else start
        response.code(206)
            .setHeader("Content-Range", "bytes $advertisedStart-$end/${payload.size}")
        if (!probe && holdBody) response.bodyDelay(10, TimeUnit.SECONDS)
        val length = if (truncate && !probe) minOf(4096, end - start + 1) else end - start + 1
        response.body(Buffer().write(payload, start, length))
            .setHeader("Content-Length", end - start + 1)
        // Close after the short body so the client observes an interrupted transfer.
        if (length < end - start + 1) response.setHeader("Connection", "close")
        return response.build()
    }

    @Test
    fun usesFourConcurrentRequestsAndAssemblesExactBytes() = runBlocking {
        gate = CountDownLatch(4)
        val progress = mutableListOf<Long>()
        assertArrayEquals(payload, fetch { done, _ -> progress.add(done) }.readBytes())
        assertEquals(0L, gate!!.count)
        assertTrue(validators.isNotEmpty())
        assertTrue(validators.all { it == etag })
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun retriesAndResumesPartialSegmentsWithANewDownloader() = runBlocking {
        truncate = true
        assertTrue(runCatching { fetch() }.isFailure)
        val saved = directory.listFiles()!!.filter { it.extension == "part" }
            .associate { it.name to it.length() }
        assertTrue(saved.values.any { it > 0 && it < chunk })
        requests.clear()
        truncate = false
        assertArrayEquals(payload, fetch().readBytes())
        saved.forEach { (name, size) ->
            val index = name.substringBefore('.').toInt()
            val end = minOf((index + 1) * chunk, payload.size) - 1
            if (size < end - index * chunk + 1) {
                assertTrue(requests.contains("bytes=${index * chunk + size}-$end"))
            }
        }
    }

    @Test
    fun changedEtagDiscardsOldBytesEvenWhenUrlAndSizeMatch() = runBlocking {
        truncate = true
        assertTrue(runCatching { fetch() }.isFailure)
        payload = ByteArray(payload.size) { 42 }
        etag = "\"release-2\""
        truncate = false
        requests.clear()
        assertArrayEquals(payload, fetch().readBytes())
        assertTrue(requests.contains("bytes=0-${chunk - 1}"))
    }

    @Test
    fun serverWithoutRangesFallsBackToOneFullDownload() = runBlocking {
        ranges = false
        assertArrayEquals(payload, fetch().readBytes())
        assertEquals(listOf("bytes=0-0", "full"), requests.toList())
    }

    @Test
    fun serverWithoutValidatorDoesNotRiskMixingVersions() = runBlocking {
        omitValidator = true
        assertArrayEquals(payload, fetch().readBytes())
        assertEquals(listOf("bytes=0-0", "full"), requests.toList())
    }

    @Test
    fun invalidContentRangeIsRejectedAndCheckpointsAreDiscarded() = runBlocking {
        invalidRange = true
        assertTrue(runCatching { fetch() }.isFailure)
        assertFalse(directory.exists())
    }

    @Test
    fun cancellationClosesBlockedRequestsAndAllowsResume() = runBlocking {
        val firstBytes = CompletableDeferred<Unit>()
        val transfer = launch {
            fetch { done, _ -> if (done > 0) firstBytes.complete(Unit) }
        }
        withTimeout(5000) { firstBytes.await() }
        withTimeout(5000) { transfer.cancelAndJoin() }
        assertArrayEquals(payload, fetch().readBytes())
    }

    @Test
    fun cancellationDoesNotWaitForSocketReadTimeout() = runBlocking {
        holdBody = true
        val transfer = launch { fetch() }
        withTimeout(5000) {
            while (requests.count { it != "bytes=0-0" } < 4) kotlinx.coroutines.delay(10)
        }
        withTimeout(1000) { transfer.cancelAndJoin() }
    }

    @Test
    fun oversizedReleaseIsRejectedBeforeNetworkRequest() = runBlocking {
        assertTrue(runCatching {
            PresetPackDownloader(client, directory, maxBytes = 100).download(
                url,
                "v1",
                101
            ) { _, _ -> }
        }.isFailure)
        assertTrue(requests.isEmpty())
    }
}
