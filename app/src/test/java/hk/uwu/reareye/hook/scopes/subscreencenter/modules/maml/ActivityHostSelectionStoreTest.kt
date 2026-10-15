package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.io.IOException

/** Windows File.renameTo cannot replace an existing file; Android's POSIX rename can. */
@Implements(android.util.AtomicFile::class)
class ActivitySelectionAtomicRenameShadow {
    companion object {
        var dropRename = false

        @JvmStatic
        @Implementation
        fun rename(source: File, target: File) {
            if (dropRename) return
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ActivitySelectionAtomicRenameShadow::class])
class ActivityHostSelectionStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private fun card(id: Int): File {
        val dir = File(temporary.root, "reareye-maml-cards-v1").apply { mkdirs() }
        return MamlCardScopeFiles(dir).prepare(id.toString())
    }

    @Test
    fun applicationsAndCardsAreIsolatedAndResetPreservesDrafts() {
        val a = ActivityHostSelectionStore.open(card(1))
        val b = ActivityHostSelectionStore.open(card(2))
        val initial = a.restore()
        val selected = initial.save("app.one", "app.one/.One", 220)
            .save("app.two", "app.two/.Two", 0)
        a.write(selected)
        assertEquals(220, a.restore().entries["app.one"]!!.dpi)
        assertEquals(0, a.restore().entries["app.two"]!!.dpi)
        assertEquals("app.two", a.restore().activePackage)
        assertTrue(b.restore().entries.isEmpty())
        assertNotEquals(initial.instance, b.restore().instance)
        a.write(selected.reset())
        assertNull(a.restore().activePackage)
        assertEquals(selected.entries, a.restore().entries)
    }

    @Test
    fun cancelAndRetireDoNotPersistDraftOrEraseSelection() {
        val file = card(3)
        val store = ActivityHostSelectionStore.open(file)
        val saved = store.restore().save("app.one", "app.one/.One", 300)
        store.write(saved)
        saved.save("app.two", "app.two/.Two", 400) // cancelled in-memory draft
        store.retire()
        assertThrows(IllegalStateException::class.java) { store.write(saved.reset()) }
        assertEquals(saved, ActivityHostSelectionStore.open(file).restore())
    }

    @Test
    fun deleteRejectsAllOldHandlesAndReaddUsesNewInstance() {
        val file = card(4)
        val a = ActivityHostSelectionStore.open(file)
        val b = ActivityHostSelectionStore.open(file)
        val saved = a.restore().save("app.one", "app.one/.One", 0)
        a.write(saved)
        ActivityHostSelectionStore.delete(file)
        assertFalse(a.isActive())
        assertFalse(b.isActive())
        assertThrows(IllegalStateException::class.java) { a.write(saved) }
        assertThrows(IllegalStateException::class.java) { b.restore() }
        val fresh = ActivityHostSelectionStore.open(file).restore()
        assertNotEquals(saved.instance, fresh.instance)
        assertTrue(fresh.entries.isEmpty())
    }

    @Test
    fun atomicFailureLeavesPreviousSelectionAndInstanceUntouched() {
        val store = ActivityHostSelectionStore.open(card(5))
        val previous = store.restore().save("app.one", "app.one/.One", 200)
        store.write(previous)
        val real = ActivityHostSelectionStore.persistence
        ActivityHostSelectionStore.persistence = object : ActivityHostSelectionStore.Persistence {
            override fun read(file: File) = real.read(file)
            override fun delete(file: File) = real.delete(file)
            override fun write(file: File, content: String) {
                val atomic = android.util.AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    stream.write("broken".toByteArray())
                    throw IOException("injected write failure")
                } catch (error: Throwable) {
                    atomic.failWrite(stream)
                    throw error
                }
            }
        }
        try {
            assertThrows(IOException::class.java) {
                store.write(
                    previous.save(
                        "app.two",
                        "app.two/.Two",
                        400
                    )
                )
            }
        } finally {
            ActivityHostSelectionStore.persistence = real
        }
        assertEquals(previous, store.restore())
    }

    @Test
    fun silentDeleteFailureIsVisibleButStillRevokesOldHandles() {
        val file = card(9)
        val store = ActivityHostSelectionStore.open(file)
        store.restore()
        val real = ActivityHostSelectionStore.persistence
        ActivityHostSelectionStore.persistence = object : ActivityHostSelectionStore.Persistence {
            override fun read(file: File) = real.read(file)
            override fun write(file: File, content: String) = real.write(file, content)
            override fun delete(file: File) = Unit
        }
        try {
            assertThrows(IllegalStateException::class.java) { ActivityHostSelectionStore.delete(file) }
            assertFalse(store.isActive())
        } finally {
            ActivityHostSelectionStore.persistence = real
        }
        ActivityHostSelectionStore.delete(file)
        assertFalse(store.file.exists())
    }

    @Test
    fun silentFinishWriteFailureCannotReportCommittedSelection() {
        val store = ActivityHostSelectionStore.open(card(7))
        val saved = store.restore().save("app.one", "app.one/.One", 200)
        store.write(saved)
        ActivitySelectionAtomicRenameShadow.dropRename = true
        try {
            assertThrows(IllegalStateException::class.java) {
                store.write(saved.save("app.two", "app.two/.Two", 400))
            }
        } finally {
            ActivitySelectionAtomicRenameShadow.dropRename = false
        }
        assertEquals(saved, store.restore())
    }

    @Test
    fun oversizedBackupIsRejectedUsingActualReadBound() {
        val store = ActivityHostSelectionStore.open(card(8))
        store.restore()
        File(store.file.path + ".bak").writeBytes(ByteArray(1_048_577) { 32 })
        assertThrows(IllegalArgumentException::class.java) { store.restore() }
    }

    @Test
    fun storageRejectsUntrustedAndSymlinkStylePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHostSelectionStore.open(File(temporary.root, "attacker.xml"))
        }
        val file = card(6)
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHostSelectionStore.open(
                File(
                    file.parentFile,
                    "../reareye-maml-cards-v1/${file.name}"
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHostSelectionStore.open(File(file.parentFile, "./${file.name}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHostSelectionStore.open(File(temporary.root, file.name))
        }
        assertEquals(
            "reareye-maml-activities-v1",
            ActivityHostSelectionStore.selectionFile(file).parentFile!!.name
        )
        assertFalse(file.exists())
    }

    private fun symlink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (error: IOException) {
            assumeNoException(error) // Windows hosts may lack the privilege to create symlinks.
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        } catch (error: SecurityException) {
            assumeNoException(error)
        }
        assumeTrue(
            "Host File canonicalization must resolve symlinks as Android does",
            link.canonicalFile == target.canonicalFile
        )
    }

    @Test
    fun trustedFilesDirectoryAliasSharesSelectionAndDeletionGeneration() {
        val filesDir = temporary.newFolder("real-files")
        val alias = File(temporary.root, "files-alias")
        symlink(alias, filesDir)
        val directCard = MamlCardScopeFiles(File(filesDir, "reareye-maml-cards-v1")).prepare("10")
        val aliasCard = MamlCardScopeFiles(File(alias, "reareye-maml-cards-v1")).prepare("10")
        assertNotEquals(aliasCard.absoluteFile, aliasCard.canonicalFile)
        val direct = ActivityHostSelectionStore.open(directCard)
        val aliased = ActivityHostSelectionStore.open(aliasCard)
        assertEquals(direct.file, aliased.file)
        assertEquals(direct.file.canonicalFile, aliased.file)
        val saved = aliased.restore().save("app.one", "app.one/.One", 220)
        aliased.write(saved)
        assertEquals(saved, direct.restore())
        ActivityHostSelectionStore.delete(aliasCard)
        assertFalse(direct.isActive())
        assertFalse(aliased.isActive())
        assertNotEquals(
            saved.instance,
            ActivityHostSelectionStore.open(directCard).restore().instance
        )
    }

    @Test
    fun cardDirectorySymlinkCannotEscapeTrustedFilesDirectory() {
        val filesDir = temporary.newFolder("files")
        val outsideCards = temporary.newFolder("outside", "reareye-maml-cards-v1")
        symlink(File(filesDir, "reareye-maml-cards-v1"), outsideCards)
        val file = MamlCardScopeFiles(File(filesDir, "reareye-maml-cards-v1")).config("11")
        assertThrows(IllegalArgumentException::class.java) { ActivityHostSelectionStore.open(file) }
    }

    @Test
    fun cardFileSymlinkCannotEscapeItsBinding() {
        val file = card(12)
        val outside = temporary.newFile("outside.xml")
        symlink(file, outside)
        assertThrows(IllegalArgumentException::class.java) { ActivityHostSelectionStore.open(file) }
    }

    @Test
    fun selectionDirectorySymlinkCannotEscapeTrustedFilesDirectory() {
        val file = card(13)
        val outside = temporary.newFolder("outside-selections")
        symlink(File(temporary.root, "reareye-maml-activities-v1"), outside)
        assertThrows(IllegalArgumentException::class.java) { ActivityHostSelectionStore.open(file) }
    }

    @Test
    fun selectionFileSymlinkCannotEscapeItsBinding() {
        val file = card(14)
        val target = ActivityHostSelectionStore.selectionFile(file)
        assertTrue(target.parentFile!!.mkdirs())
        symlink(target, temporary.newFile("outside.json"))
        assertThrows(IllegalArgumentException::class.java) { ActivityHostSelectionStore.open(file) }
    }

    @Test
    fun persistedSnapshotCannotContainOrReplayLaunchRequests() {
        val state = ActivityHostSelectionState(MamlCardScopeFiles.newInstance())
            .save("app.one", "app.one/.One", 250)
        val json = ActivityHostSelectionStore.encode(state)
        assertFalse(json.contains("launchRequest"))
        assertFalse(json.contains("sequence"))
        assertEquals(state, ActivityHostSelectionStore.decode(json))
    }

    @Test
    fun componentRemovalOnlyDropsItsOwnPackageAndActiveSelection() {
        val state = ActivityHostSelectionState(MamlCardScopeFiles.newInstance())
            .save("app.one", "app.one/.One", 250).save("app.two", "app.two/.Two", 350)
        val invalidated = state.remove("app.two")
        assertNull(invalidated.activePackage)
        assertEquals(state.entries["app.one"], invalidated.entries["app.one"])
        assertFalse(invalidated.entries.containsKey("app.two"))
    }

    @Test
    fun actionGrammarRejectsMissingSequenceAndUnsafeDpiState() {
        val instance = MamlCardScopeFiles.newInstance()
        val parsed =
            ActivityHostSelectionState.Action.parse("$instance|save|app.one|app.one/.One|240|1")
        assertEquals(1L, parsed.sequence)
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHostSelectionState.Action.parse("$instance|save|app.one|app.one/.One|240|0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHostSelectionState.Action.parse("$instance|save|app.one|app.one/.One|240|9007199254740992")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHostSelectionState(instance).save("app.one", "app.two/.Two", 240)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHostSelectionState(instance).save("app.one", "app.one/.One", 119)
        }
    }
}
