package hk.uwu.reareye.repository.presetpack

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

/** Durable segments in filesDir survive activity recreation and process death. */
internal class PresetPackDownloader(
    private val client: OkHttpClient,
    private val directory: File,
    private val maxBytes: Long = PresetPackContract.MAX_PACK_BYTES,
    private val chunkBytes: Long = 4L * 1024 * 1024,
) {
    private class InvalidRange(message: String) : IOException(message)
    private class HttpFailure(val status: Int) : IOException("下载失败：HTTP $status")
    private data class Remote(val size: Long, val validator: String)

    suspend fun download(
        url: String,
        identity: String,
        expectedSize: Long,
        onProgress: (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        check(expectedSize <= maxBytes) { "RPP file exceeds size limit" }
        directory.mkdirs()
        val output = File(directory, "assembled.rpp")
        val remote = retry {
            request(url, "bytes=0-0") { response ->
                when (response.code) {
                    206 -> {
                        val range = parseRange(response.header("Content-Range"))
                        if (range == null || range.first != 0L || range.second != 0L) {
                            throw InvalidRange("服务器返回了错误的下载分段")
                        }
                        checkSize(range.third, expectedSize)
                        validator(response)?.let { Remote(range.third, it) }
                    }

                    200 -> null
                    else -> throw HttpFailure(response.code)
                }
            }
        }
        if (remote == null) {
            // A server without ranges or a validator cannot safely append old bytes.
            clear()
            directory.mkdirs()
            retry {
                request(url) { response ->
                    if (response.code != 200) throw HttpFailure(response.code)
                    val total = response.body.contentLength().takeIf { it > 0 } ?: expectedSize
                    if (total > 0) checkSize(total, expectedSize)
                    output.outputStream().use { sink ->
                        response.body.byteStream().use { source ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            onProgress(0, total)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                done += count
                                check(done <= maxBytes && (total <= 0 || done <= total)) {
                                    "RPP file exceeds expected size"
                                }
                                sink.write(buffer, 0, count)
                                onProgress(done, total)
                            }
                            if (done == 0L || (total > 0 && done != total)) {
                                throw IOException("资源包下载不完整，请重试")
                            }
                        }
                    }
                }
            }
            return@withContext output
        }

        val metadata = Properties().apply {
            setProperty("url", url)
            setProperty("identity", identity)
            setProperty("size", remote.size.toString())
            setProperty("validator", remote.validator)
            setProperty("chunkBytes", chunkBytes.toString())
        }
        val metadataFile = File(directory, "download.properties")
        val previous = runCatching {
            Properties().apply { metadataFile.inputStream().use { load(it) } }
        }.getOrNull()
        if (metadata != previous) {
            clear()
            directory.mkdirs()
            // Identity is written before any bytes. A torn metadata write discards the cache.
            metadataFile.outputStream().use { metadata.store(it, null) }
        }
        val count = ((remote.size + chunkBytes - 1) / chunkBytes).toInt()
        val parts = List(count) { File(directory, "$it.part") }
        fun partSize(index: Int) = minOf(chunkBytes, remote.size - index * chunkBytes)
        parts.forEachIndexed { index, file ->
            if (file.length() > partSize(index)) check(file.delete()) { "Unable to reset RPP segment" }
        }
        val progressLock = Any()
        var done = parts.sumOf { it.length() }
        onProgress(done, remote.size)
        try {
            coroutineScope {
                repeat(minOf(4, count)) { worker ->
                    launch {
                        for (index in worker until count step 4) {
                            retry {
                                val part = parts[index]
                                val remaining = partSize(index) - part.length()
                                if (remaining == 0L) return@retry
                                val start = index * chunkBytes + part.length()
                                val end = index * chunkBytes + partSize(index) - 1
                                request(url, "bytes=$start-$end", remote.validator) { response ->
                                    if (response.code == 200 || response.code == 416) {
                                        throw InvalidRange("资源包已变更或服务器不再支持续传，请重试")
                                    }
                                    if (response.code != 206) throw HttpFailure(response.code)
                                    if (parseRange(response.header("Content-Range")) != Triple(
                                            start,
                                            end,
                                            remote.size
                                        )
                                    ) {
                                        throw InvalidRange("服务器返回了错误的下载分段，请重试")
                                    }
                                    val currentValidator = validator(response)
                                    if (currentValidator != null && currentValidator != remote.validator) {
                                        throw InvalidRange("资源包已变更，请重试")
                                    }
                                    val bodySize = response.body.contentLength()
                                    if (bodySize >= 0 && bodySize != remaining) {
                                        throw InvalidRange("下载分段长度不一致，请重试")
                                    }
                                    FileOutputStream(part, true).use { sink ->
                                        response.body.byteStream().use { source ->
                                            val buffer = ByteArray(64 * 1024)
                                            var left = remaining
                                            while (left > 0) {
                                                currentCoroutineContext().ensureActive()
                                                val read = source.read(
                                                    buffer,
                                                    0,
                                                    minOf(buffer.size.toLong(), left).toInt()
                                                )
                                                if (read < 0) throw IOException("资源包下载中断，将从已下载位置继续")
                                                sink.write(buffer, 0, read)
                                                left -= read
                                                synchronized(progressLock) {
                                                    done += read
                                                    onProgress(done, remote.size)
                                                }
                                            }
                                            if (source.read() != -1) throw InvalidRange("下载分段超出预期长度")
                                        }
                                        sink.fd.sync()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: InvalidRange) {
            clear()
            throw error
        }
        output.outputStream().use { sink ->
            parts.forEachIndexed { index, part ->
                currentCoroutineContext().ensureActive()
                check(part.length() == partSize(index)) { "Incomplete RPP segment" }
                part.inputStream().use { it.copyTo(sink) }
            }
        }
        output
    }

    fun clear() {
        if (directory.exists()) check(directory.deleteRecursively()) { "Unable to reset RPP download" }
    }

    private fun validator(response: Response): String? =
        response.header("ETag")?.takeUnless { it.startsWith("W/") }
            ?: response.header("Last-Modified")

    private fun checkSize(actual: Long, expected: Long) {
        check(actual in 1..maxBytes) { "RPP file exceeds size limit or is empty" }
        check(expected <= 0 || actual == expected) { "RPP download size mismatch" }
    }

    private fun parseRange(value: String?): Triple<Long, Long, Long>? {
        val match =
            value?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) } ?: return null
        val numbers = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        return Triple(numbers[0], numbers[1], numbers[2])
    }

    private suspend fun <T> retry(block: suspend () -> T): T {
        repeat(3) { attempt ->
            try {
                return block()
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (error is InvalidRange ||
                    (error is HttpFailure && error.status !in listOf(
                        408,
                        429
                    ) && error.status < 500) ||
                    attempt == 2
                ) throw error
                delay(1000L shl attempt)
            }
        }
        error("Unreachable")
    }

    private suspend fun <T> request(
        url: String,
        range: String? = null,
        validator: String? = null,
        consume: suspend (Response) -> T,
    ): T = coroutineScope {
        val request = Request.Builder().url(url)
            .header("Accept", "application/octet-stream")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "REAREye")
            .apply {
                range?.let { header("Range", it) }
                validator?.let { header("If-Range", it) }
            }.build()
        val call = client.newCall(request)
        // Cancellation closes blocking reads; structured concurrency also waits for file writes.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use { consume(it) } }
        } finally {
            cancellation.cancel()
        }
    }
}
