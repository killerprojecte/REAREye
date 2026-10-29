package hk.uwu.reareye.utils

import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SharedRuntimeFileStoreTest {
    private val target = File("/fixture/runtime.json")
    private val original = SharedRuntimeFileStore.FileState(
        "[old]",
        SharedRuntimeFileStore.Metadata(6101, 6101, 0x1ff, "original-label"),
        SharedRuntimeFileStore.Identity(1, 10, 5, 100, 100),
    )

    private inner class FakeFiles : SharedRuntimeFileStore.FileOperations {
        val files = ConcurrentHashMap<File, SharedRuntimeFileStore.FileState>().apply {
            put(target, original)
        }
        var fail: String? = null
        var created = 0
        var replaced = 0
        var deleted = 0
        var skipMetadata = false
        var corruptTemp = false
        var onPreserve: (() -> Unit)? = null
        var onRename: (() -> Unit)? = null
        var failCleanup = false

        private fun check(stage: String) {
            if (fail == stage) throw IOException(stage)
        }

        override fun read(target: File): SharedRuntimeFileStore.FileState {
            check(if (target == this@SharedRuntimeFileStoreTest.target) "read" else "readTemp")
            return files[target] ?: throw IOException("missing")
        }

        override fun createTemp(target: File): File {
            check("create")
            val temp = File(target.parentFile, "unique-${++created}.tmp")
            files[temp] = original.copy(
                text = "",
                metadata = SharedRuntimeFileStore.Metadata(10213, 10213, 0x180, "original-label"),
                identity = original.identity.copy(inode = 20L + created),
            )
            return temp
        }

        override fun writeAndSync(temp: File, text: String) {
            // A partial write must also be harmless to the real target.
            files[temp] = files.getValue(temp).copy(text = text.take(1))
            check("write")
            files[temp] = files.getValue(temp).copy(text = if (corruptTemp) "partial" else text)
            check("sync")
        }

        override fun preserveMetadata(temp: File, metadata: SharedRuntimeFileStore.Metadata) {
            check("metadata")
            if (!skipMetadata) files[temp] = files.getValue(temp).copy(metadata = metadata)
            onPreserve?.invoke()
        }

        override fun replaceAtomically(temp: File, target: File) {
            onRename?.invoke()
            check("rename")
            files[target] = files.remove(temp)!!
            replaced++
        }

        override fun deleteTemp(temp: File) {
            deleted++
            if (failCleanup) throw IOException("cleanup")
            files.remove(temp)
        }
    }

    private fun store(fs: FakeFiles) = SharedRuntimeFileStore(fs) { text ->
        // Shape-only fixture validator: the Android integration uses actual JSONArray parsing.
        if (!text.startsWith("[") || !text.endsWith("]")) throw IOException("invalid array")
    }

    private fun fails(block: () -> Unit): Throwable {
        try {
            block()
            fail("Expected failure")
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("unreachable")
    }

    @Test fun successfulReplacementPreservesMetadataAndUsesNewInode() {
        val fs = FakeFiles()
        val store = store(fs)
        store.write(store.read(target), "[new]")
        assertEquals("[new]", fs.files.getValue(target).text)
        assertEquals(original.metadata, fs.files.getValue(target).metadata)
        assertNotEquals(original.identity.inode, fs.files.getValue(target).identity.inode)
        assertEquals(1, fs.replaced)
        assertEquals(setOf(target), fs.files.keys)
        assertEquals(0, fs.deleted)
    }

    @Test fun everyPrecommitFailurePreservesOriginal() {
        for (stage in listOf("read", "create", "write", "sync", "metadata", "readTemp", "rename")) {
            val fs = FakeFiles()
            val store = store(fs)
            val snapshot = store.read(target)
            fs.fail = stage
            fails { store.write(snapshot, "[new]") }
            assertEquals(stage, original, fs.files[target])
            assertEquals(stage, setOf(target), fs.files.keys)
            assertEquals(stage, 0, fs.replaced)
        }
    }

    @Test fun missingUnreadableBlankAndMalformedOriginalNeverBecomeEmptyArray() {
        for (text in listOf<String?>(null, "", " ", "{broken", "null")) {
            val fs = FakeFiles()
            if (text == null) fs.files.remove(target) else fs.files[target] = original.copy(text = text)
            fails { store(fs).read(target) }
            assertEquals(0, fs.created)
        }
        val fs = FakeFiles().apply { fail = "read" }
        fails { store(fs).read(target) }
        assertEquals(0, fs.created)
    }

    @Test fun invalidNewDocumentFailsBeforeCreatingTemp() {
        val fs = FakeFiles()
        val store = store(fs)
        val snapshot = store.read(target)
        fails { store.write(snapshot, "broken") }
        assertEquals(original, fs.files[target])
        assertEquals(0, fs.created)
    }

    @Test fun metadataAndContentAreVerifiedRatherThanTrustingAdapterSuccess() {
        for (metadataFailure in listOf(true, false)) {
            val fs = FakeFiles().apply {
                skipMetadata = metadataFailure
                corruptTemp = !metadataFailure
            }
            val store = store(fs)
            fails { store.write(store.read(target), "[new]") }
            assertEquals(original, fs.files[target])
            assertEquals(setOf(target), fs.files.keys)
            assertEquals(0, fs.replaced)
        }
    }

    @Test fun staleConcurrentWriterCannotOverwriteCommittedUpdate() {
        val fs = FakeFiles()
        val first = store(fs)
        val second = store(fs)
        val firstRead = first.read(target)
        val secondRead = second.read(target)
        first.write(firstRead, "[first]")
        fails { second.write(secondRead, "[second]") }
        assertEquals("[first]", fs.files.getValue(target).text)
        assertEquals(1, fs.created)
        assertEquals(1, fs.replaced)
    }

    @Test fun externalChangeDuringStagingIsPreservedAndTempRemoved() {
        val mutations = listOf(
            original.copy(text = "[external]"),
            original.copy(metadata = original.metadata.copy(mode = 0x1a4)),
            original.copy(identity = original.identity.copy(inode = 99)),
        )
        for (external in mutations) {
            val fs = FakeFiles()
            fs.onPreserve = { fs.files[target] = external }
            val store = store(fs)
            fails { store.write(store.read(target), "[new]") }
            assertEquals(external, fs.files[target])
            assertEquals(setOf(target), fs.files.keys)
            assertEquals(0, fs.replaced)
        }
    }

    @Test fun disappearingTargetIsNotRecreatedFromStaleSnapshot() {
        val fs = FakeFiles()
        fs.onPreserve = { fs.files.remove(target) }
        val store = store(fs)
        fails { store.write(store.read(target), "[new]") }
        assertTrue(fs.files.isEmpty())
        assertEquals(0, fs.replaced)
    }

    @Test fun reusedSnapshotFailsAfterSuccessfulCommit() {
        val fs = FakeFiles()
        val store = store(fs)
        val snapshot = store.read(target)
        store.write(snapshot, "[new]")
        fails { store.write(snapshot, "[again]") }
        assertEquals("[new]", fs.files.getValue(target).text)
        assertEquals(1, fs.replaced)
    }

    @Test fun snapshotFromDifferentStoreIsRejected() {
        val fs = FakeFiles()
        val snapshot = store(fs).read(target)
        fails { store(fs).write(snapshot, "[new]") }
        assertEquals(original, fs.files[target])
        assertEquals(0, fs.created)
    }

    @Test fun cleanupFailureDoesNotHideOriginalError() {
        val fs = FakeFiles().apply { fail = "metadata"; failCleanup = true }
        val store = store(fs)
        val failure = fails { store.write(store.read(target), "[new]") }
        assertEquals("metadata", failure.message)
        assertEquals("cleanup", failure.suppressed.single().message)
        assertEquals(original, fs.files[target])
        assertEquals(0, fs.replaced)
    }

    @Test fun preflightValidatesPermissionsWithoutReplacingOriginal() {
        val fs = FakeFiles()
        val store = store(fs)
        store.checkWritable(store.read(target))
        assertEquals(original, fs.files[target])
        assertEquals(setOf(target), fs.files.keys)
        assertEquals(1, fs.created)
        assertEquals(1, fs.deleted)
        assertEquals(0, fs.replaced)
    }

    @Test fun preflightFailureNeverReplacesOriginal() {
        for (stage in listOf("read", "create", "write", "sync", "metadata", "readTemp")) {
            val fs = FakeFiles()
            val store = store(fs)
            val snapshot = store.read(target)
            fs.fail = stage
            fails { store.checkWritable(snapshot) }
            assertEquals(stage, original, fs.files[target])
            assertEquals(stage, setOf(target), fs.files.keys)
            assertEquals(stage, 0, fs.replaced)
        }
    }

    @Test fun successfulPreflightDoesNotBypassLaterPermissionCheck() {
        val fs = FakeFiles()
        val store = store(fs)
        val snapshot = store.read(target)
        store.checkWritable(snapshot)
        fs.fail = "metadata"
        fails { store.write(snapshot, "[new]") }
        assertEquals(original, fs.files[target])
        assertEquals(setOf(target), fs.files.keys)
        assertEquals(0, fs.replaced)
    }

    @Test(timeout = 10000) fun concurrentStoreInstancesSerializeFinalCheckAndRename() {
        val fs = FakeFiles()
        val first = store(fs)
        val second = store(fs)
        val firstSnapshot = first.read(target)
        val secondSnapshot = second.read(target)
        val firstAtRename = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()
        fs.onRename = {
            if (Thread.currentThread().name == "runtime-first-writer") {
                firstAtRename.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS)) { "First writer was not released" }
            }
        }
        val firstThread = Thread({
            try {
                first.write(firstSnapshot, "[first]")
            } catch (failure: Throwable) {
                firstFailure.set(failure)
            }
        }, "runtime-first-writer").apply { isDaemon = true }
        val secondThread = Thread({
            secondStarted.countDown()
            try {
                second.write(secondSnapshot, "[second]")
            } catch (failure: Throwable) {
                secondFailure.set(failure)
            }
        }, "runtime-second-writer").apply { isDaemon = true }
        try {
            firstThread.start()
            assertTrue("First writer must pause after the final comparison", firstAtRename.await(3, TimeUnit.SECONDS))
            secondThread.start()
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var blockedByFirst = false
            while (secondThread.isAlive && System.nanoTime() < deadline) {
                val info = threads.getThreadInfo(secondThread.id)
                if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == firstThread.id) {
                    blockedByFirst = true
                    break
                }
                Thread.yield()
            }
            assertTrue("Second store must block on the first store's commit monitor", blockedByFirst)
            assertEquals("Second writer must not stage before first commits", 1, fs.created)
        } finally {
            releaseFirst.countDown()
            firstThread.join(2000)
            secondThread.join(2000)
        }
        assertFalse(firstThread.isAlive)
        assertFalse(secondThread.isAlive)
        assertNull(firstFailure.get())
        assertTrue(secondFailure.get() is IOException)
        assertEquals("Runtime file changed; reload before retrying", secondFailure.get()?.message)
        assertEquals("[first]", fs.files.getValue(target).text)
        assertEquals(1, fs.created)
        assertEquals(1, fs.replaced)
        assertEquals(setOf(target), fs.files.keys)
    }
}
