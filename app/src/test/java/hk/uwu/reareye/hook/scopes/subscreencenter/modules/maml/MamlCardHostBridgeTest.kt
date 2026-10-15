package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Properties

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MamlCardHostBridgeTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private fun bridge() = MamlCardHostBridge(object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.miui.maml.ScreenElementRoot" -> CardFixtureRoot::class.java
            "com.miui.maml.component.MamlView" -> CardFixtureView::class.java
            "com.miui.maml.util.ConfigFile" -> CardFixtureConfig::class.java
            "com.miui.maml.elements.ButtonScreenElement" -> CardFixtureGesture::class.java
            "com.miui.maml.widget.edit.WidgetEditSave" -> CardFixtureEdit::class.java
            else -> super.loadClass(name)
        }
    })

    private fun root() = CardFixtureRoot(RuntimeEnvironment.getApplication())

    private class GestureManagerFixture {
        fun handle(event: MotionEvent): Boolean = event.actionMasked == MotionEvent.ACTION_MOVE
        fun wrongSignature(): Boolean = false
    }

    class MainGestureActivity : Activity() {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean =
            super.dispatchTouchEvent(event)
    }

    class GuideGestureActivity : Activity() {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean =
            super.dispatchTouchEvent(event)
    }

    private fun managerMethod() =
        GestureManagerFixture::class.java.getDeclaredMethod("handle", MotionEvent::class.java)

    private fun mainDispatcher() = MainGestureActivity::class.java.getDeclaredMethod(
        "dispatchTouchEvent",
        MotionEvent::class.java
    )

    private fun guideDispatcher() = GuideGestureActivity::class.java.getDeclaredMethod(
        "dispatchTouchEvent",
        MotionEvent::class.java
    )

    @Test
    fun multipleActivityCallersAreValidAndNonActivityCandidatesAreIgnored() {
        val errors = mutableListOf<Throwable>()
        val main = mainDispatcher()
        val guide = guideDispatcher()
        val points = resolveMamlGesturePoints(
            ::managerMethod,
            {
                listOf(
                    main,
                    guide,
                    main,
                    View::class.java.getDeclaredMethod(
                        "dispatchTouchEvent",
                        MotionEvent::class.java
                    )
                )
            },
            { errors += it },
        )
        assertNotNull(points)
        assertEquals(listOf(main, guide), points!!.dispatchers)
        assertEquals(managerMethod(), points.manager)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun singleActivityCallerRemainsSupported() {
        val points =
            resolveMamlGesturePoints(::managerMethod, { listOf(mainDispatcher()) }, { throw it })
        assertEquals(listOf(mainDispatcher()), points!!.dispatchers)
    }

    @Test
    fun invalidManagerDoesNotQueryOrHookDispatchers() {
        var queried = false
        val errors = mutableListOf<Throwable>()
        val points = resolveMamlGesturePoints(
            { GestureManagerFixture::class.java.getDeclaredMethod("wrongSignature") },
            { queried = true; listOf(mainDispatcher()) },
            { errors += it },
        )
        assertNull(points)
        assertFalse(queried)
        assertEquals(1, errors.size)
    }

    @Test
    fun absentOrFailedGestureLookupNeverBlocksChosenConfigRestoration() {
        repeat(3) { failureKind ->
            val bridge = bridge()
            val root = root()
            val file =
                MamlCardScopeFiles(temporary.newFolder()).prepare("app:resource-$failureKind")
            val key = bridge.initialize(root, file)
            root.saveVar("rw_provider", "test.pkg/Provider")
            root.saveVar("rw_package", "test.pkg")
            root.saveVar("rw_selected", 1.0)
            bridge.commit(root, file)
            val errors = mutableListOf<Throwable>()
            val points = resolveMamlGesturePoints(
                { if (failureKind == 0) null else managerMethod() },
                {
                    if (failureKind == 2) throw IllegalStateException("Native gesture lookup unavailable")
                    emptyList()
                },
                { errors += it },
            )
            assertNull(points)
            assertEquals(1, errors.size)
            bridge.nativeInitializing(root)
            bridge.prepare(root, file)
            root.init()
            assertEquals(key, bridge.initialize(root, file))
            assertEquals(1.0, root.variables.values["rw_ready"])
            assertEquals(1.0, root.variables.values["rw_selected"])
            assertEquals("test.pkg/Provider", root.variables.values["rw_provider"])
            assertEquals(key, bridge.savedKey(file))
        }
    }

    private class OwnerFixture(
        var id: String, var view: Any? = Any(), var type: Int? = 0,
        var appCard: Boolean = false, var resId: String? = null
    )

    private fun owners() = MamlCardOwnerTracker(
        { (it as OwnerFixture).id }, { (it as OwnerFixture).view },
        {
            (it as OwnerFixture).let { card ->
                MamlCardScopeFiles.storageIdentity(
                    card.id,
                    card.type,
                    card.appCard,
                    card.resId,
                    !card.appCard
                )
            }
        },
    )

    @Test
    fun actualCreationAcceptsRealCardWithoutATitleRegistry() {
        val card = OwnerFixture("42")
        val tracker = owners()
        tracker.remember(card, card.view!!)
        assertTrue(tracker.owns(card.view!!, "42"))
    }

    @Test
    fun previewCannotClaimAnExistingRealCardLocalId() {
        val card = OwnerFixture("42")
        val tracker = owners()
        tracker.remember(card, card.view!!)
        assertFalse(tracker.owns(Any(), "42"))
        assertFalse(tracker.owns(card.view!!, "43"))
    }

    @Test
    fun replacedOrDestroyedViewLosesItsOwnership() {
        val card = OwnerFixture("42")
        val old = card.view!!
        val tracker = owners()
        tracker.remember(card, old)
        card.view = Any()
        assertFalse(tracker.owns(old, "42"))
        tracker.remember(card, card.view!!)
        assertTrue(tracker.owns(card.view!!, "42"))
        val current = card.view!!
        card.view = null
        assertFalse(tracker.owns(current, "42"))
    }

    @Test
    fun changedIdentityIsRejectedUntilRecordedAgain() {
        val card = OwnerFixture("42")
        val tracker = owners()
        tracker.remember(card, card.view!!)
        card.id = "43"
        assertFalse(tracker.owns(card.view!!, "42"))
        assertFalse(tracker.owns(card.view!!, "43"))
    }

    @Test
    fun removalInvalidatesOnlyTheRemovedCard() {
        val first = OwnerFixture("42")
        val second = OwnerFixture("43")
        val tracker = owners()
        tracker.remember(first, first.view!!)
        tracker.remember(second, second.view!!)
        tracker.remove(first)
        assertFalse(tracker.owns(first.view!!, "42"))
        assertTrue(tracker.owns(second.view!!, "43"))
    }

    @Test
    fun closedGenerationRejectsLateCreationCallbacks() {
        val card = OwnerFixture("42")
        val tracker = owners()
        tracker.remember(card, card.view!!)
        tracker.close()
        tracker.remember(card, card.view!!)
        assertFalse(tracker.owns(card.view!!, "42"))
    }

    @Test
    fun creationCannotRegisterAnotherCardsView() {
        val card = OwnerFixture("42")
        assertThrows(IllegalStateException::class.java) { owners().remember(card, Any()) }
    }

    @Test
    fun expandedAppCardsWithNativeZeroHaveDistinctStableStorageAndDeletion() {
        val tracker = owners()
        val first = OwnerFixture("0", type = 7, appCard = true, resId = "resource-a")
        val second = OwnerFixture("0", type = 7, appCard = true, resId = "resource-b")
        val ordinary = OwnerFixture("0")
        listOf(first, second, ordinary).forEach { tracker.remember(it, it.view!!) }
        assertTrue(tracker.owns(first.view!!, "0"))
        assertEquals("app:resource-a", tracker.storageIdentity(first.view!!))
        assertEquals("app:resource-b", tracker.storageIdentity(second.view!!))
        assertEquals("0", tracker.storageIdentity(ordinary.view!!))
        val files = MamlCardScopeFiles(temporary.newFolder())
        val bridge = bridge()
        val firstRoot = root()
        val secondRoot = root()
        val firstFile = files.prepare(tracker.storageIdentity(first.view!!)!!)
        val secondFile = files.prepare(tracker.storageIdentity(second.view!!)!!)
        val numericFile = files.prepare(tracker.storageIdentity(ordinary.view!!)!!)
        val firstKey = bridge.initialize(firstRoot, firstFile)
        val secondKey = bridge.initialize(secondRoot, secondFile)
        val ordinaryKey = bridge.initialize(root(), numericFile)
        assertEquals(3, setOf(firstFile, secondFile, numericFile).size)
        assertEquals(3, setOf(firstKey, secondKey, ordinaryKey).size)
        // Reopening the same real resource, not its shared native localId, restores its binding.
        val reopened = OwnerFixture("0", type = 7, appCard = true, resId = "resource-b")
        tracker.remember(reopened, reopened.view!!)
        assertEquals(
            secondKey,
            bridge.initialize(root(), files.prepare(tracker.storageIdentity(reopened.view!!)!!))
        )
        tracker.remove(first)
        bridge.detachDeleted(firstRoot)
        assertTrue(firstFile.delete())
        firstRoot.pause()
        assertFalse(firstFile.exists())
        assertTrue(tracker.owns(second.view!!, "0"))
        assertTrue(tracker.owns(ordinary.view!!, "0"))
        assertEquals(secondKey, bridge.savedKey(secondFile))
        assertEquals(ordinaryKey, bridge.savedKey(numericFile))
    }

    @Test
    fun unresolvedApplicationTypeOrMissingBundleResourceFailsClosed() {
        val tracker = owners()
        listOf(
            OwnerFixture("0", type = null, appCard = true, resId = "a"),
            OwnerFixture("0", type = 7, appCard = false, resId = "a"),
            OwnerFixture("0", type = 7, appCard = true),
            OwnerFixture("0", type = 7, appCard = true, resId = "  "),
        ).forEach { card ->
            tracker.remember(card, card.view!!)
            assertFalse(tracker.owns(card.view!!, "0"))
            assertNull(tracker.storageIdentity(card.view!!))
        }
        val ordinary = OwnerFixture("0", type = null)
        tracker.remember(ordinary, ordinary.view!!)
        assertEquals("0", tracker.storageIdentity(ordinary.view!!))
    }

    @Test
    fun changedResourceCannotReuseARecordedNativeZeroOwner() {
        val tracker = owners()
        val card = OwnerFixture("0", type = 7, appCard = true, resId = "a")
        tracker.remember(card, card.view!!)
        card.resId = "b"
        assertFalse(tracker.owns(card.view!!, "0"))
        assertNull(tracker.storageIdentity(card.view!!))
    }

    @Test
    fun numericZeroRetainsItsLegacyV1DigestAndNoApplicationMigration() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        assertEquals(
            "5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9.xml",
            files.config("0").name
        )
        assertNotEquals(files.config("0"), files.config("app:0"))
        assertThrows(IllegalArgumentException::class.java) { files.config("app:") }
    }

    private fun moduleIdentity(
        business: String = "appwidget",
        cardId: String = "1a930ae9-f2fc-411d-9edc-5bb4128746fd",
    ) = requireNotNull(
        MamlCardScopeFiles.moduleIdentity(
            "hk.uwu.reareye",
            "hk.uwu.reareye",
            business,
            cardId
        )
    )

    @Test
    fun moduleDefinitionKeepsTheSameFileAndKeyAcrossNativeNotificationIds() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val bridge = bridge()
        val identity = moduleIdentity()
        val firstFile = files.prepare(identity, "-105038037")
        val key = bridge.initialize(root(), firstFile)
        val reopenedFile = files.prepare(identity, "-105038039")
        assertEquals(firstFile, reopenedFile)
        assertEquals(key, bridge.initialize(root(), reopenedFile))
        assertEquals(
            "module:hk.uwu.reareye:appwidget:1a930ae9-f2fc-411d-9edc-5bb4128746fd",
            identity
        )
    }

    @Test
    fun moduleMetadataAndDomainsAreStrictAndDifferentDefinitionsAreIsolated() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val identity = moduleIdentity()
        assertTrue(MamlCardScopeFiles.validStorageIdentity(identity))
        assertNotEquals(
            files.config(identity),
            files.config(moduleIdentity(business = "notification"))
        )
        assertNotEquals(
            files.config(identity),
            files.config(moduleIdentity(cardId = "1a930ae9-f2fc-411d-9edc-5bb4128746fe"))
        )
        assertNotEquals(files.config(identity), files.config("app:$identity"))
        listOf(null, "", "other.pkg", "hk.uwu.reareye ").forEach { invalid ->
            assertNull(
                MamlCardScopeFiles.moduleIdentity(
                    invalid,
                    "hk.uwu.reareye",
                    "appwidget",
                    "1a930ae9-f2fc-411d-9edc-5bb4128746fd"
                )
            )
            assertNull(
                MamlCardScopeFiles.moduleIdentity(
                    "hk.uwu.reareye",
                    invalid,
                    "appwidget",
                    "1a930ae9-f2fc-411d-9edc-5bb4128746fd"
                )
            )
        }
        listOf(null, "", "app:widget", "app widget", "app/widget", "小组件").forEach { invalid ->
            assertNull(
                MamlCardScopeFiles.moduleIdentity(
                    "hk.uwu.reareye",
                    "hk.uwu.reareye",
                    invalid,
                    "1a930ae9-f2fc-411d-9edc-5bb4128746fd"
                )
            )
        }
        listOf(
            null,
            "",
            "1-2-3-4-5",
            "1A930AE9-F2FC-411D-9EDC-5BB4128746FD",
            "1a930ae9-f2fc-411d-9edc-5bb4128746fd "
        ).forEach { invalid ->
            assertNull(
                MamlCardScopeFiles.moduleIdentity(
                    "hk.uwu.reareye",
                    "hk.uwu.reareye",
                    "appwidget",
                    invalid
                )
            )
        }
        listOf(
            "module:other.pkg:appwidget:1a930ae9-f2fc-411d-9edc-5bb4128746fd",
            "module:hk.uwu.reareye::1a930ae9-f2fc-411d-9edc-5bb4128746fd",
            "$identity:extra", "module:hk.uwu.reareye:appwidget:1-2-3-4-5",
        ).forEach { invalid ->
            assertFalse(MamlCardScopeFiles.validStorageIdentity(invalid))
            assertFalse(MamlCardScopeFiles.preservesBinding(invalid))
            assertThrows(IllegalArgumentException::class.java) { files.config(invalid) }
        }
        assertTrue(MamlCardScopeFiles.preservesBinding("app:resource"))
        assertFalse(MamlCardScopeFiles.preservesBinding("-105038037"))
    }

    @Test
    fun moduleNotificationRemovalRetiresRootsWithoutDeletingSelectionOrBinding() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val bridge = bridge()
        val identity = moduleIdentity()
        val file = files.prepare(identity)
        val first = root()
        val key = bridge.initialize(first, file)
        first.saveVar("rw_provider", "pkg/Widget")
        first.saveVar("rw_selected", 1.0)
        bridge.commit(first, file)
        val bindings = mutableSetOf(key)
        var retired = 0
        MamlCardScopeFiles.onNativeRemoval(identity, retireRoots = {
            retired++
            bridge.detachDeleted(first)
        }, deleteBinding = {
            bindings.remove(key)
            file.delete()
        })
        first.finish()
        assertEquals(1, retired)
        assertTrue(file.isFile)
        assertTrue(bindings.contains(key))
        val reopened = root()
        assertEquals(key, bridge.initialize(reopened, files.prepare(identity, "-105038039")))
        assertEquals("pkg/Widget", reopened.variables.values["rw_provider"])
        assertEquals(1.0, reopened.variables.values["rw_selected"])
    }

    @Test
    fun exactNumericLegacyMigrationPreservesBytesInstanceTokenAndBinding() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val bridge = bridge()
        val legacy = files.prepare("-105038037")
        val first = root()
        val key = bridge.initialize(first, legacy)
        first.saveVar("rw_provider", "pkg/Widget")
        first.saveVar("rw_package", "pkg")
        first.saveVar("rw_selected", 1.0)
        bridge.commit(first, legacy)
        val bindings = mutableSetOf(key)
        val bytes = legacy.readBytes()
        val unrelated = files.prepare("-105038038")
        val unrelatedRoot = root()
        val unrelatedKey = bridge.initialize(unrelatedRoot, unrelated)
        unrelatedRoot.saveVar("rw_provider", "pkg/Widget")
        unrelatedRoot.saveVar("rw_selected", 1.0)
        bridge.commit(unrelatedRoot, unrelated)
        val migrated = files.prepare(moduleIdentity(), "-105038037")
        assertFalse(legacy.exists())
        assertArrayEquals(bytes, migrated.readBytes())
        assertEquals(key, bridge.savedKey(migrated))
        val restored = root()
        assertEquals(key, bridge.initialize(restored, migrated))
        assertEquals(key, restored.variables.values["rw_instance"])
        assertTrue(bindings.contains(key))
        assertEquals("pkg/Widget", restored.variables.values["rw_provider"])
        assertEquals("pkg", restored.variables.values["rw_package"])
        assertEquals(1.0, restored.variables.values["rw_selected"])
        assertEquals(unrelatedKey, bridge.savedKey(unrelated))
    }

    @Test
    fun stableTargetAlwaysWinsAndNeitherAppNorMissingExactLegacyBorrowsAnotherFile() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val bridge = bridge()
        val identity = moduleIdentity()
        val stable = files.prepare(identity)
        val stableKey = bridge.initialize(root(), stable)
        val stableBytes = stable.readBytes()
        val legacy = files.prepare("-105038037")
        val legacyKey = bridge.initialize(root(), legacy)
        assertEquals(stable, files.prepare(identity, "-105038037"))
        assertArrayEquals(stableBytes, stable.readBytes())
        assertEquals(stableKey, bridge.savedKey(stable))
        assertEquals(legacyKey, bridge.savedKey(legacy))
        assertFalse(files.prepare(moduleIdentity(business = "other"), "-105038039").exists())
        assertFalse(files.prepare("app:resource", "-105038037").exists())
        assertEquals(legacyKey, bridge.savedKey(legacy))
        assertThrows(IllegalArgumentException::class.java) {
            files.prepare(
                moduleIdentity(business = "other"),
                "app:resource"
            )
        }
    }

    @Test
    fun emptyLegacyMigratesButCorruptLegacyIsNeverResetOrReplaced() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val empty = files.prepare("11")
        empty.writeBytes(byteArrayOf())
        val emptyTarget = files.prepare(moduleIdentity(business = "empty"), "11")
        assertTrue(emptyTarget.isFile)
        assertEquals(0L, emptyTarget.length())
        assertFalse(empty.exists())
        val legacy = files.prepare("12")
        legacy.writeText("not XML and must not be discarded")
        val bytes = legacy.readBytes()
        val target = files.prepare(moduleIdentity(business = "corrupt"), "12")
        assertThrows(Exception::class.java) { bridge().initialize(root(), target) }
        assertArrayEquals(bytes, target.readBytes())
        val invalidLegacy = files.config("13")
        assertTrue(invalidLegacy.mkdir())
        val invalidIdentity = moduleIdentity(business = "directory")
        assertThrows(IllegalStateException::class.java) { files.prepare(invalidIdentity, "13") }
        assertTrue(invalidLegacy.isDirectory)
        assertFalse(files.config(invalidIdentity).exists())
    }

    @Test
    fun inconsistentMigratedSelectionFailsWithoutClearingEitherStableOrLegacyData() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val legacy = files.prepare("14")
        CardFixtureConfig().apply {
            putNumber("rw_selected", 1.0)
            putString("rw_provider", "pkg/Widget")
            save(legacy.absolutePath, RuntimeEnvironment.getApplication())
        }
        val bytes = legacy.readBytes()
        val identity = moduleIdentity(business = "inconsistent")
        val target = files.prepare(identity, "14")
        assertThrows(IllegalStateException::class.java) { bridge().initialize(root(), target) }
        assertArrayEquals(bytes, target.readBytes())
        val anotherLegacy = files.prepare("15")
        anotherLegacy.writeText("another legacy must not repair the stable target")
        assertEquals(target, files.prepare(identity, "15"))
        assertThrows(IllegalStateException::class.java) { bridge().initialize(root(), target) }
        assertArrayEquals(bytes, target.readBytes())
        assertEquals("another legacy must not repair the stable target", anotherLegacy.readText())
    }

    @Test
    fun twoCardsGetIndependentNativeConfigFilesAndInstanceKeys() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val bridge = bridge()
        val first = root()
        val second = root()
        val firstFile = files.prepare("41")
        val secondFile = files.prepare("42")
        val firstKey = bridge.initialize(first, firstFile)
        val secondKey = bridge.initialize(second, secondFile)
        assertNotEquals(firstFile, secondFile)
        assertNotEquals(firstKey, secondKey)
        assertTrue(MamlCardScopeFiles.validInstance(firstKey))
        assertEquals(1.0, first.variables.values["rw_ready"])
        assertEquals(0.0, first.variables.values["rw_selected"])
        assertEquals(firstKey, bridge.savedKey(firstFile))
    }

    @Test
    fun confirmationPersistsAndANewRootRestoresTheSameInstanceAndProvider() {
        val file = MamlCardScopeFiles(temporary.newFolder()).prepare("10")
        val bridge = bridge()
        val first = root()
        val key = bridge.initialize(first, file)
        first.saveVar("rw_provider", "package/Widget")
        first.saveVar("rw_package", "package")
        first.saveVar("rw_selected", 1.0)
        bridge.commit(first, file)
        val restored = root()
        assertEquals(key, bridge.initialize(restored, file))
        assertEquals("package/Widget", restored.variables.values["rw_provider"])
        assertEquals("package", restored.variables.values["rw_package"])
        assertEquals(1.0, restored.variables.values["rw_selected"])
    }

    @Test
    fun repeatedInitializationDoesNotReloadOrPublishTransientEmptySelection() {
        val bridge = bridge()
        val root = root()
        val file = MamlCardScopeFiles(temporary.newFolder()).prepare("10")
        val key = bridge.initialize(root, file)
        root.saveVar("rw_provider", "pkg/Widget")
        root.saveVar("rw_package", "pkg")
        root.saveVar("rw_selected", 1.0)
        bridge.commit(root, file)
        root.variables.values["rw_selected"] = 1.0
        root.variables.values["rw_provider"] = "pkg/Widget"
        root.variables.writes.clear()
        bridge.loading(root)
        val native = root.mConfig
        assertEquals(key, bridge.initialize(root, file))
        assertSame(native, root.mConfig)
        repeat(10) { assertEquals(key, bridge.initialize(root, file)) }
        assertEquals(listOf("rw_error" to ""), root.variables.writes)
        assertEquals(1, root.loads)
        assertFalse(root.variables.writes.contains("rw_ready" to 0.0))
        assertFalse(root.variables.writes.contains("rw_selected" to 0.0))
        assertFalse(root.variables.writes.contains("rw_provider" to ""))
    }

    @Test
    fun nativeRootReinitializationKeepsItsFreshConfigAndRestoresTheBinding() {
        val bridge = bridge()
        val root = root()
        val file = MamlCardScopeFiles(temporary.newFolder()).prepare("10")
        val key = bridge.initialize(root, file)
        root.saveVar("rw_provider", "pkg/Widget")
        root.saveVar("rw_selected", 1.0)
        bridge.commit(root, file)
        root.loadConfig()
        val freshNative = root.mConfig
        root.variables.values.clear()
        assertEquals(key, bridge.initialize(root, file))
        assertSame(freshNative, root.mConfig)
        assertEquals(2, root.loads)
        assertEquals(1.0, root.variables.values["rw_selected"])
        assertEquals("pkg/Widget", root.variables.values["rw_provider"])
    }

    @Test
    fun prepareBindsBeforeNativeInitWithoutPublishingLoadingState() {
        val bridge = bridge()
        val root = root()
        val file = MamlCardScopeFiles(temporary.newFolder()).prepare("10")
        bridge.prepare(root, file)
        assertEquals(file.absolutePath, root.mConfigPath)
        assertNull(root.mConfig)
        assertTrue(root.variables.writes.isEmpty())
        root.loadConfig()
        val native = root.mConfig
        bridge.initialize(root, file)
        assertSame(native, root.mConfig)
        assertEquals(1, root.loads)
    }

    @Test
    fun earlyPrepareRestoresBeforeNativeInitAndDuplicateCallbacksPublishNothing() {
        val bridge = bridge()
        val file = MamlCardScopeFiles(temporary.newFolder()).prepare("app:resource")
        val original = root()
        val key = bridge.initialize(original, file)
        original.saveVar("rw_provider", "pkg/Widget")
        original.saveVar("rw_selected", 1.0)
        bridge.commit(original, file)
        val reopened = root()
        bridge.prepare(reopened, file)
        bridge.nativeInitializing(reopened)
        reopened.loadConfig() // Native init now sees the independent path, never shared 0.xml.
        val native = reopened.mConfig
        assertEquals(key, bridge.initialize(reopened, file))
        assertEquals(1.0, reopened.variables.values["rw_selected"])
        assertEquals("pkg/Widget", reopened.variables.values["rw_provider"])
        reopened.variables.writes.clear()
        repeat(5) { assertEquals(key, bridge.initialize(reopened, file)) }
        assertTrue(reopened.variables.writes.isEmpty())
        assertSame(native, reopened.mConfig)
        assertEquals(1, reopened.loads)
        // A genuine native init invalidates deduplication even if ConfigFile itself is reused.
        bridge.nativeInitializing(reopened)
        reopened.variables.values.clear()
        assertEquals(key, bridge.initialize(reopened, file))
        assertEquals(1.0, reopened.variables.values["rw_selected"])
        assertSame(native, reopened.mConfig)
    }

    @Test
    fun ordinaryPauseSavesRatherThanDeletingTheSelection() {
        val file = MamlCardScopeFiles(temporary.newFolder()).prepare("10")
        val bridge = bridge()
        val root = root()
        val key = bridge.initialize(root, file)
        root.saveVar("rw_provider", "pkg/Widget")
        root.saveVar("rw_selected", 1.0)
        root.pause()
        assertTrue(file.isFile)
        assertEquals(key, bridge.savedKey(file))
        assertEquals(1.0, root.variables.values["rw_ready"])
    }

    @Test
    fun deletionDetachesNativeConfigSoALatePauseCannotRecreateIt() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val bridge = bridge()
        val file = files.prepare("10")
        val removed = root()
        val oldKey = bridge.initialize(removed, file)
        bridge.detachDeleted(removed)
        assertTrue(file.delete())
        removed.pause()
        assertFalse(file.exists())
        assertNull(removed.mConfig)
        assertNull(removed.mConfigPath)
        assertEquals(0.0, removed.variables.values["rw_ready"])
        assertEquals(0.0, removed.variables.values["rw_selected"])
        val fresh = root()
        assertNotEquals(oldKey, bridge.initialize(fresh, files.prepare("10")))
        assertEquals(0.0, fresh.variables.values["rw_selected"])
    }

    @Test
    fun applicationNativeDestroyAndClearCacheCallbackKeepSelectionAndWidgetBinding() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val bridge = bridge()
        val identity = "app:resource"
        val file = files.prepare(identity)
        val first = root()
        val firstView = CardFixtureView(first)
        bridge.prepare(first, file) // nativeInit.before
        bridge.nativeInitializing(first) // root.init.before
        first.loadConfig()
        val key = bridge.initialize(first, file) // root.init.after
        CardFixtureEdit.setLocalId(firstView, "0")
        firstView.setConfig(Any())
        assertEquals(file.absolutePath, first.mConfigPath)
        first.saveVar("rw_provider", "pkg/Widget")
        first.saveVar("rw_selected", 1.0)
        bridge.commit(first, file)
        val bindings = mutableSetOf(key)
        // Ordinary releaseAppCard runs onDestroy -> selfFinish; no registry q callback.
        firstView.onDestroy()
        assertNull(bridge.root(firstView))
        assertEquals(1, first.sessionCloses)
        assertTrue(file.isFile)
        assertTrue(bindings.contains(key))
        val reopened = root()
        val reopenedView = CardFixtureView(reopened)
        bridge.prepare(reopened, file)
        bridge.nativeInitializing(reopened)
        reopened.loadConfig()
        assertEquals(key, bridge.initialize(reopened, file))
        CardFixtureEdit.setLocalId(reopenedView, "0")
        reopenedView.setConfig(Any())
        assertEquals(file.absolutePath, reopened.mConfigPath)
        assertEquals(1.0, reopened.variables.values["rw_selected"])
        assertEquals("pkg/Widget", reopened.variables.values["rw_provider"])
        // Exercise the exact native remove callback dispatcher used by ScopeHook as well.
        var retired = 0
        var permanentDeletes = 0
        MamlCardScopeFiles.onNativeRemoval(identity, retireRoots = {
            retired++
            bridge.detachDeleted(reopened)
        }, deleteBinding = {
            permanentDeletes++
            bindings.remove(key)
            file.delete()
        })
        reopenedView.onDestroy()
        assertEquals(1, retired)
        assertEquals(0, permanentDeletes)
        assertTrue(file.isFile)
        assertTrue(bindings.contains(key))
        val third = root()
        bridge.prepare(third, file)
        bridge.nativeInitializing(third)
        third.loadConfig()
        assertEquals(key, bridge.initialize(third, file))
        assertEquals(1.0, third.variables.values["rw_selected"])
        assertEquals("pkg/Widget", third.variables.values["rw_provider"])
    }

    @Test
    fun nativePersistentRemovalStillDeletesBindingAndConfig() {
        val bridge = bridge()
        val file = MamlCardScopeFiles(temporary.newFolder()).prepare("0")
        val root = root()
        val key = bridge.initialize(root, file)
        val bindings = mutableSetOf(key)
        MamlCardScopeFiles.onNativeRemoval(
            "0",
            retireRoots = { bridge.detachDeleted(root) },
            deleteBinding = {
                bindings.remove(key)
                assertTrue(file.delete())
            })
        root.finish()
        assertFalse(file.exists())
        assertFalse(bindings.contains(key))
    }

    @Test
    fun oldApplicationOwnerCannotRetireAnotherReopenedViewWithTheSameResource() {
        val bridge = bridge()
        val tracker = owners()
        val oldRoot = root()
        val newRoot = root()
        val oldView = CardFixtureView(oldRoot)
        val newView = CardFixtureView(newRoot)
        val old = OwnerFixture("0", oldView, 7, true, "resource")
        val reopened = OwnerFixture("0", newView, 7, true, "resource")
        tracker.remember(old, oldView)
        tracker.remember(reopened, newView)
        old.view = null // Native q2.k.s already cleared B.
        val targets = tracker.views(old).mapNotNull(bridge::root)
        assertEquals(listOf(oldRoot), targets)
        tracker.remove(old)
        assertEquals("app:resource", tracker.storageIdentity(newView))
        assertFalse(targets.contains(newRoot))
    }

    @Test
    fun deletingOneCardDoesNotTouchAnotherCardConfig() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        val bridge = bridge()
        val first = root()
        val second = root()
        val firstFile = files.prepare("1")
        val secondFile = files.prepare("2")
        bridge.initialize(first, firstFile)
        val secondKey = bridge.initialize(second, secondFile)
        bridge.detachDeleted(first)
        assertTrue(firstFile.delete())
        assertEquals(secondKey, bridge.savedKey(secondFile))
        assertEquals(1.0, second.variables.values["rw_ready"])
    }

    @Test
    fun invalidAndNonCanonicalLocalIdsCannotBecomeConfigPaths() {
        val files = MamlCardScopeFiles(temporary.newFolder())
        listOf("", "01", "+1", "../../42", "preview", "2147483648").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { files.config(id) }
        }
    }

    @Test
    fun realHostIntegerIdsMayIncludeZeroOrNegativeValues() {
        val directory = temporary.newFolder()
        val files = MamlCardScopeFiles(directory)
        val ids = listOf("0", "-1", "-2147483648", "2147483647")
        assertEquals(ids.size, ids.map(files::config).distinct().size)
        assertTrue(ids.map(files::config).all { it.parentFile == directory })
    }

    @Test
    fun corruptExistingConfigIsNotOverwritten() {
        val file = MamlCardScopeFiles(temporary.newFolder()).prepare("10")
        file.writeText("not native XML")
        assertThrows(IllegalStateException::class.java) { bridge().initialize(root(), file) }
        assertEquals("not native XML", file.readText())
    }

    @Test
    fun corruptSavedInstanceIsRejectedRatherThanClaimingAGlobalKey() {
        val file = MamlCardScopeFiles(temporary.newFolder()).prepare("10")
        val native = CardFixtureConfig().apply {
            putString(
                "rw_instance",
                "global-key"
            ); save(file.absolutePath, RuntimeEnvironment.getApplication())
        }
        assertNotNull(native)
        assertThrows(IllegalStateException::class.java) { bridge().initialize(root(), file) }
    }

    @Test
    fun selectionWithoutAnInstanceTokenIsRejected() {
        val file = MamlCardScopeFiles(temporary.newFolder()).prepare("10")
        CardFixtureConfig().apply {
            putNumber("rw_selected", 1.0); save(
            file.absolutePath,
            RuntimeEnvironment.getApplication()
        )
        }
        assertThrows(IllegalStateException::class.java) { bridge().initialize(root(), file) }
    }

    @Test
    fun onlyOptInThemesAreEnabledAndViewRootIsReadFromHostField() {
        val bridge = bridge()
        val root = root()
        assertTrue(bridge.enabled(root))
        root.picker = false
        assertFalse(bridge.enabled(root))
        assertSame(root, bridge.root(CardFixtureView(root)))
    }

    @Test
    fun pickerTouchOwnershipIsLimitedToReadyUnselectedOptInRoots() {
        val bridge = bridge()
        val root = root()
        assertFalse(bridge.pickerVisible(root))
        root.variables.put("rw_ready", 1.0)
        assertTrue(bridge.pickerVisible(root))
        root.variables.put("rw_selected", 1.0)
        assertFalse(bridge.pickerVisible(root))
        root.variables.put("rw_selected", 0.0)
        root.picker = false
        assertFalse(bridge.pickerVisible(root))
        root.picker = true
        root.variables.put("rw_ready", 0.0)
        assertFalse(bridge.pickerVisible(root))
    }

    @Test
    fun applicationWidgetTouchPreservesNativeAvailabilityAndSelectedDisplayGates() {
        val bridge = bridge()
        val root = root()
        mapOf("rw_ready" to 1.0, "rw_selected" to 1.0, "displayReady" to 1.0)
            .forEach { (name, value) -> root.variables.put(name, value) }
        assertTrue(bridge.appWidgetTouchable(root))
        for (name in listOf("rw_ready", "rw_selected", "displayReady")) {
            root.variables.put(name, 0.0)
            assertFalse(bridge.appWidgetTouchable(root))
            root.variables.put(name, 1.0)
        }
        root.finished = true
        assertFalse(bridge.appWidgetTouchable(root))
        root.finished = false
        root.mLoaded = false
        assertFalse(bridge.appWidgetTouchable(root))
        root.mLoaded = true
        root.mTouchable = false
        assertFalse(bridge.appWidgetTouchable(root))
        root.mTouchable = true
        root.picker = false
        assertFalse(bridge.appWidgetTouchable(root))
        root.picker = true
        assertTrue(bridge.appWidgetTouchable(root))
    }

    @Test
    fun pickerHitUsesPhysicalCoordinatesAndOnlyTheSafeGestureBounds() {
        val bridge = bridge()
        val root = root()
        root.variables.put("rw_ready", 1.0)
        root.variables.put("rw_selected", 0.0)
        root.variables.put("displayReady", 1.0)
        root.variables.put("safeX", 100.0)
        root.variables.put("safeY", 200.0)
        root.variables.put("safeW", 600.0)
        root.variables.put("safeH", 1000.0)
        assertTrue(bridge.pickerHit(root, 50f, 100f))
        assertTrue(bridge.pickerHit(root, 349f, 599f))
        assertFalse(bridge.pickerHit(root, 49f, 100f))
        assertFalse(bridge.pickerHit(root, 350f, 100f))
        assertFalse(bridge.pickerHit(root, 100f, 600f))
        assertFalse(bridge.pickerHit(root, Float.NaN, 100f))
        root.variables.put("safeW", Double.POSITIVE_INFINITY)
        assertFalse(bridge.pickerHit(root, 100f, 100f))
    }

    @Test
    fun pickerHitFailsClosedForUnavailableSelectedOrHiddenGestureRoots() {
        val bridge = bridge()
        val root = root()
        mapOf(
            "rw_ready" to 1.0, "rw_selected" to 0.0, "displayReady" to 1.0,
            "safeX" to 0.0, "safeY" to 0.0, "safeW" to 100.0, "safeH" to 100.0
        )
            .forEach { (name, value) -> root.variables.put(name, value) }
        assertTrue(bridge.pickerHit(root, 10f, 10f))
        root.finished = true
        assertFalse(bridge.pickerHit(root, 10f, 10f))
        root.finished = false
        root.mLoaded = false
        assertFalse(bridge.pickerHit(root, 10f, 10f))
        root.mLoaded = true
        root.mTouchable = false
        assertFalse(bridge.pickerHit(root, 10f, 10f))
        root.mTouchable = true
        root.variables.put("displayReady", 0.0)
        assertFalse(bridge.pickerHit(root, 10f, 10f))
        root.variables.put("displayReady", 1.0)
        root.variables.put("rw_selected", 1.0)
        assertFalse(bridge.pickerHit(root, 10f, 10f))
        root.variables.put("rw_selected", 0.0)
        (root.gesture as CardFixtureGesture).visible = false
        assertFalse(bridge.pickerHit(root, 10f, 10f))
        root.gesture = Any()
        assertFalse(bridge.pickerHit(root, 10f, 10f))
        root.gesture = null
        assertFalse(bridge.pickerHit(root, 10f, 10f))
    }

    private class TouchParent(context: Context) : android.widget.FrameLayout(context) {
        val requests = mutableListOf<Boolean>()
        override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
            requests += disallow
            super.requestDisallowInterceptTouchEvent(disallow)
        }
    }

    private fun touchFixture(): Pair<TouchParent, View> {
        val context = RuntimeEnvironment.getApplication()
        val parent = TouchParent(context)
        val view = View(context)
        parent.addView(view)
        return parent to view
    }

    @Test
    fun pickerClaimsDownBeforeFirstMoveAndReleasesOnUp() {
        val (parent, view) = touchFixture()
        val guard = MamlPickerTouchGuard()
        assertTrue(guard.afterTouch(view, MotionEvent.ACTION_DOWN, true, true))
        assertEquals(listOf(true), parent.requests)
        // A native move may report false outside a button: preserve the claimed stream.
        assertTrue(guard.afterTouch(view, MotionEvent.ACTION_MOVE, false, false))
        assertEquals(listOf(true, true), parent.requests)
        assertTrue(guard.afterTouch(view, MotionEvent.ACTION_UP, false, true))
        assertEquals(listOf(true, true, false), parent.requests)
        assertFalse(guard.afterTouch(view, MotionEvent.ACTION_MOVE, false, true))
    }

    @Test
    fun ordinarySelectedAndUnhandledThemesDoNotBlockParentPager() {
        val (parent, view) = touchFixture()
        val guard = MamlPickerTouchGuard()
        assertFalse(guard.afterTouch(view, MotionEvent.ACTION_DOWN, false, true))
        assertFalse(guard.afterTouch(view, MotionEvent.ACTION_MOVE, true, true))
        assertFalse(guard.afterTouch(view, MotionEvent.ACTION_DOWN, true, false))
        assertTrue(parent.requests.isEmpty())
    }

    @Test
    fun parentPagerCannotStealFirstMoveButWorksAgainAfterPickerSelection() {
        val context = RuntimeEnvironment.getApplication()
        val guard = MamlPickerTouchGuard()
        var pickerVisible = true
        var interceptedMoves = 0
        val childEvents = mutableListOf<Int>()
        val parent = object : android.widget.FrameLayout(context) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    interceptedMoves++
                    return true
                }
                return false
            }

            override fun onTouchEvent(event: MotionEvent) = true
        }
        val child = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                childEvents += event.actionMasked
                // Native MAML handles DOWN before its parent-disallow flag is observed.
                guard.afterTouch(this, event.actionMasked, pickerVisible, true)
                return true
            }
        }
        parent.addView(child, android.widget.FrameLayout.LayoutParams(200, 200))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(
                200,
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(
                200,
                View.MeasureSpec.EXACTLY
            )
        )
        parent.layout(0, 0, 200, 200)
        fun dispatch(action: Int, time: Long, y: Float) {
            val event = MotionEvent.obtain(0, time, action, 100f, y, 0)
            try {
                parent.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        dispatch(MotionEvent.ACTION_DOWN, 0, 100f)
        dispatch(MotionEvent.ACTION_MOVE, 16, 40f)
        dispatch(MotionEvent.ACTION_UP, 32, 40f)
        assertEquals(0, interceptedMoves)
        assertEquals(
            listOf(
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP
            ), childEvents
        )
        pickerVisible = false
        dispatch(MotionEvent.ACTION_DOWN, 64, 100f)
        dispatch(MotionEvent.ACTION_MOVE, 80, 40f)
        dispatch(MotionEvent.ACTION_UP, 96, 40f)
        assertEquals(1, interceptedMoves)
        assertTrue(childEvents.contains(MotionEvent.ACTION_CANCEL))
    }

    @Test
    fun pickerCancelDetachAndCloseAllReleaseInterception() {
        for (end in listOf("cancel", "detach", "close")) {
            val (parent, view) = touchFixture()
            val guard = MamlPickerTouchGuard()
            guard.afterTouch(view, MotionEvent.ACTION_DOWN, true, true)
            when (end) {
                "cancel" -> guard.afterTouch(
                    view,
                    MotionEvent.ACTION_CANCEL,
                    false,
                    false
                )

                "detach" -> guard.release(view)
                else -> guard.close()
            }
            assertEquals(listOf(true, false), parent.requests)
            assertFalse(guard.afterTouch(view, MotionEvent.ACTION_MOVE, false, true))
            if (end == "close") {
                assertFalse(
                    guard.afterTouch(
                        view,
                        MotionEvent.ACTION_DOWN,
                        true,
                        true
                    )
                )
            }
        }
    }

    @Test
    fun backgroundCloseFreezesLateTouchesAndReleasesParentOnMain() {
        val (parent, view) = touchFixture()
        val guard = MamlPickerTouchGuard()
        guard.afterTouch(view, MotionEvent.ACTION_DOWN, true, true)
        Thread { guard.close() }.apply { start(); join() }
        assertFalse(guard.afterTouch(view, MotionEvent.ACTION_MOVE, false, true))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false), parent.requests)
    }

    @Test
    fun rootQueueOwnsInitializationAndErrorsBlockRealWidgetVisibility() {
        val bridge = bridge()
        val root = root()
        bridge.loading(root)
        bridge.enqueue(root) { bridge.fail(root, "No independent identity") }
        assertEquals("", root.variables.values["rw_error"])
        root.queue.removeAt(0).run()
        assertEquals(0.0, root.variables.values["rw_ready"])
        assertEquals("No independent identity", root.variables.values["rw_error"])
    }
}

class CardFixtureContext(@JvmField val mContext: Context)
class CardFixtureVariables {
    val values = mutableMapOf<String, Any?>()
    val writes = mutableListOf<Pair<String, Any?>>()
    fun put(name: String, value: Double) {
        values[name] = value; writes += name to value
    }

    fun put(name: String, value: Any?) {
        values[name] = value; writes += name to value
    }

    fun getDouble(name: String): Double = (values[name] as? Number)?.toDouble() ?: 0.0
    fun get(name: String): Any? = values[name]
}

class CardFixtureRoot(private val context: Context) {
    @JvmField
    var mConfig: CardFixtureConfig? = null

    @JvmField
    var mConfigPath: String? = null

    @JvmField
    var mLoaded = true

    @JvmField
    var mTouchable = true
    var finished = false
    var sessionCloses = 0
    var scale = 0.5f
    var gesture: Any? = CardFixtureGesture()
    var loads = 0
    fun isFinished() = finished
    fun findElement(name: String): Any? = if (name == "gesture") gesture else null
    val variables = CardFixtureVariables()
    val queue = mutableListOf<Runnable>()
    private val mutex = Any()
    var picker = true
    var activityPicker = false
    fun getContext() = CardFixtureContext(context)
    fun getRawAttr(name: String): String? = when {
        name == "rearWidgetPicker" && picker -> "true"
        name == "rearActivityPicker" && activityPicker -> "true"
        else -> null
    }

    fun getRenderMutex() = mutex
    fun init() {}
    fun setConfig(path: String?) {
        mConfigPath = path
    }

    fun setSaveConfigViaProvider(value: Boolean) {
        check(!value)
    }

    fun loadConfig() {
        loads++
        mConfig = CardFixtureConfig().also { cfg ->
            cfg.load(mConfigPath!!)
            cfg.values.forEach { (name, value) ->
                if (name == "rw_selected") variables.put(name, value.toDouble()) else variables.put(
                    name,
                    value
                )
            }
        }
    }

    fun saveVar(name: String, value: String) {
        mConfig?.putString(name, value)
    }

    fun saveVar(name: String, value: Double?) {
        value?.let { mConfig?.putNumber(name, it) }
    }

    fun postRunnable(task: Runnable) {
        queue += task
    }

    fun requestUpdate() {}
    fun pause() {
        mConfig?.save(mConfigPath!!, context)
    }

    fun finish() {
        if (finished) return
        pause()
        sessionCloses++
        finished = true
        variables.values.clear()
    }
}

class CardFixtureGesture {
    @JvmField
    var mTouchable = true
    var visible = true
    fun isVisible() = visible
}

class CardFixtureView(@JvmField var mRoot: CardFixtureRoot?) {
    var oneConfig: Any? = null
    fun setConfig(value: Any?) {
        oneConfig = value
    }

    fun onDestroy() {
        mRoot?.finish(); mRoot = null
    }
}

class CardFixtureEdit {
    companion object {
        @JvmStatic
        fun setLocalId(view: CardFixtureView, id: String) {
            view.mRoot?.variables?.put("customEditLocalId", id)
        }
    }
}

class CardFixtureConfig {
    val values = mutableMapOf<String, String>()
    fun getVariable(name: String): String? = values[name]
    fun putString(name: String, value: String) {
        values[name] = value
    }

    fun putNumber(name: String, value: Double) {
        values[name] = value.toString()
    }

    fun load(path: String): Boolean {
        if (!File(path).isFile) return false
        return runCatching {
            val props = Properties()
            File(path).inputStream().use(props::loadFromXML)
            props.forEach { name, value -> values[name.toString()] = value.toString() }
            true
        }.getOrDefault(false)
    }

    fun save(path: String, context: Context): Boolean = runCatching {
        val props = Properties().apply { putAll(this@CardFixtureConfig.values) }
        File(path).outputStream().use { props.storeToXML(it, "Native ConfigFile fixture") }
        true
    }.getOrDefault(false)
}
