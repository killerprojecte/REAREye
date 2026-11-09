package hk.uwu.reareye.ui.components

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.MaryController
import hk.uwu.reareye.agent.MaryViewModel
import hk.uwu.reareye.agent.data.AgentSessionStore
import hk.uwu.reareye.agent.data.SettingsSnapshot
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.ui.capture.captureWindowBitmap
import hk.uwu.reareye.ui.screen.MaryScreen
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Actual MaryScreen, MaryViewModel, controller, session store, body and native materials.
 * Only credentials/configuration and stored conversations are isolated local fixtures.
 * There is no replacement CompactGlassTopBarScene, injected UI state, fake scrolled flag,
 * programmatic list scrolling, network send or production diagnostic/test semantics.
 *
 * Native consumer inspection prevents a false pixel pass when clear controls merely reveal
 * moving content. These read-only probes deliberately match the pinned native dependencies.
 * Native Robolectric captures are supplementary evidence, never physical Android GPU proof.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaryScreenGlassLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var controller: MaryController
    private lateinit var store: AgentSessionStore
    private lateinit var backend: MixUiBackend
    private var darkMode = false
    private val height = mutableStateOf(420.dp)
    private lateinit var evidence: File

    @Before fun requireResources() {
        assumeTrue("Run with -PmaryUiResources=true", java.lang.Boolean.getBoolean("mary.ui.resources"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }

    @Test fun miuixRealLandingScrollAndReturnDriveNativeMaterial() = landing(MixUiBackend.Miuix)
    @Test fun mochiRealLandingScrollAndReturnDriveNativeMaterial() = landing(MixUiBackend.Mochi)
    @Test fun miuixShortLandingDoesNotEnableGlassOnAnUnconsumedDrag() = shortLanding(MixUiBackend.Miuix)
    @Test fun mochiShortLandingDoesNotEnableGlassOnAnUnconsumedDrag() = shortLanding(MixUiBackend.Mochi)
    @Test fun miuixRealEmptyConversationTransitionsKeepTheirOwnScrollSource() = transitions(MixUiBackend.Miuix)
    @Test fun mochiRealEmptyConversationTransitionsKeepTheirOwnScrollSource() = transitions(MixUiBackend.Mochi)

    @Test fun miuixShortConversationElasticMotionDrivesMaterial() = shortConversationOverscroll(MixUiBackend.Miuix)
    @Test fun mochiShortConversationElasticMotionDrivesMaterial() = shortConversationOverscroll(MixUiBackend.Mochi)

    @Test fun miuixDarkShortConversationElasticMotionDrivesMaterial() {
        darkMode = true
        shortConversationOverscroll(MixUiBackend.Miuix)
    }
    @Test fun mochiDarkShortConversationElasticMotionDrivesMaterial() {
        darkMode = true
        shortConversationOverscroll(MixUiBackend.Mochi)
    }

    private fun shortConversationOverscroll(value: MixUiBackend) {
        height.value = 980.dp
        host(value)
        val fixture = store.create("Short actual conversation").copy(messages = listOf(
            ChatMessage(MessageRole.USER, "你好"),
            ChatMessage(MessageRole.ASSISTANT,
                "你好！我是 Mary，REAREye 的内置助手。我可以帮你管理：\n\n" +
                    "• 卡片：查看、启用/停用、调整顺序\n" +
                    "• 应用卡片：启用应用后停、重命名、排序\n" +
                    "• 插件：切换、轮换、删除\n" +
                    "• 组件：创建与修改、场景路由\n" +
                    "• 脚本：查看、编辑 Lua 脚本\n\n请问需要我做什么？"),
        ))
        store.save(fixture)
        compose.runOnUiThread { controller.loadSession(fixture.id) }
        awaitSettled(entries = 2)
        assertEquals("The video's two-message transcript must fit, not be a long-list substitute", 0f, maximum(), 0f)
        assertEquals(0f, offset(), 0f)
        assertNativeMaterial(false)
        val first = compose.onNodeWithText("你好", useUnmergedTree = true)
        val restTop = first.fetchSemanticsNode().boundsInRoot.top
        val controls = controlGeometry()
        val headerBottom = node("mary-open-sidebar").fetchSemanticsNode().boundsInRoot.bottom
        val viewport = scrollNode().fetchSemanticsNode().boundsInRoot
        val composerTop = node("mary-composer").fetchSemanticsNode().boundsInRoot.top
        val root = compose.onRoot(useUnmergedTree = true)
        val start = Offset(viewport.left + viewport.width * 0.15f,
            minOf(viewport.bottom - 16f, composerTop - 32f))
        val end = Offset(start.x, headerBottom + 12f)
        save("short-transcript-before-elastic")
        root.performTouchInput { down(start) }
        var sawRealOverlap = false
        repeat(18) { step ->
            val point = start + (end - start) * ((step + 1f) / 18f)
            root.performTouchInput { moveTo(point, delayMillis = 32) }
            repeat(3) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
            assertEquals("Elastic motion must not be mistaken for real list scroll", 0f, offset(), 0f)
            val actualTop = first.fetchSemanticsNode().boundsInRoot.top
            if (actualTop < headerBottom - 2f) {
                sawRealOverlap = true
                assertNativeMaterial(true)
            }
            assertEquals("The pinned controls must not move with elastic content", controls, controlGeometry())
            if (step % 3 == 2) save("short-transcript-held-up-$step")
        }
        assertTrue("The pointer sequence must visibly move actual text behind the bar before UP", sawRealOverlap)
        root.performTouchInput { up() }
        frames()
        assertEquals("Native elastic spring must restore content", restTop,
            first.fetchSemanticsNode().boundsInRoot.top, 1.1f)
        assertNativeMaterial(false)
        save("short-transcript-elastic-restored")
        // A downward pull moves content away from the bar and must not enable its material.
        val downStart = Offset(start.x, headerBottom + 32f)
        root.performTouchInput { down(downStart) }
        repeat(8) { step ->
            root.performTouchInput { moveTo(downStart + Offset(0f, (step + 1f) * 25f), delayMillis = 32) }
            repeat(2) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
            assertEquals(0f, offset(), 0f)
            assertNativeMaterial(false)
        }
        root.performTouchInput { up() }
        frames()
        assertNativeMaterial(false)
        compose.runOnUiThread { controller.newSession() }
        awaitSettled(entries = 0)
        assertNativeMaterial(false)
    }

    private fun host(value: MixUiBackend) {
        backend = value
        evidence = File(requireNotNull(System.getProperty("mary.ui.screenshotDir")),
            "actual-mary-scroll/${backend.name}${if (darkMode) "-dark" else ""}").apply { mkdirs() }
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val owner = activity!!.get()
            owner.enableEdgeToEdge()
            // Seed only this test application's canonical, credential-free settings.
            owner.getSharedPreferences("reareye_agent_secrets", 0).edit().clear()
                .putString("settings_snapshot_v1", Json.encodeToString(
                    SettingsSnapshot.serializer(), SettingsSnapshot(AgentConfig())))
                .commit()
            store = AgentSessionStore(owner.applicationContext.filesDir)
            assertTrue("A test must not inherit another test's history", store.list().isEmpty())
            controller = ViewModelProvider(owner,
                ViewModelProvider.AndroidViewModelFactory(owner.application))[MaryViewModel::class.java].controller
            owner.setContent {
                MixUiHost(backend = backend, colorMode = if (darkMode) MixColorSchemeMode.Dark else MixColorSchemeMode.Light,
                    // The vendor's iOS style supplies real control glass and edge blur.
                    mochiStyle = MixMochiStyle.Ios) {
                    Box(Modifier.fillMaxWidth().height(height.value)) {
                        MaryScreen(bottomInnerPadding = 32.dp)
                    }
                }
            }
        }
        awaitSettled(entries = 0)
    }

    private fun landing(value: MixUiBackend) {
        host(value)
        assertEquals(0f, offset(), 0f)
        assertTrue("Real short-viewport landing must overflow", maximum() > 0f)
        val geometry = controlGeometry()
        assertNativeMaterial(false)
        save("landing-top")
        drag(up = true)
        assertTrue("The actual landing ScrollState must move", offset() > 0f)
        assertNativeMaterial(true)
        assertEquals("Pinned controls must not collapse or shift", geometry, controlGeometry())
        save("landing-scrolled")
        returnToTop()
        assertNativeMaterial(false)
        assertEquals(geometry, controlGeometry())
        assertEquals(0, controller.state.value.entries.size)
        save("landing-restored")
    }

    private fun shortLanding(value: MixUiBackend) {
        height.value = 980.dp
        host(value)
        assertEquals("Landing must fit before this is a short-content test", 0f, maximum(), 0f)
        val geometry = controlGeometry()
        repeat(2) { drag(up = it == 0) }
        assertEquals(0f, offset(), 0f)
        assertNativeMaterial(false)
        assertEquals(geometry, controlGeometry())
        assertEquals(0, controller.state.value.entries.size)
        save("short-landing-after-drags")
    }

    private fun transitions(value: MixUiBackend) {
        host(value)
        drag(up = true)
        assertTrue(offset() > 0f)
        assertNativeMaterial(true)
        val geometry = controlGeometry()
        val fixture = store.create("Local glass regression").copy(messages = List(24) { index ->
            ChatMessage(if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                "Local row $index. Reading position and native glass must follow the visible transcript.\n".repeat(3))
        })
        store.save(fixture)
        assertEquals("Stored actual history must round-trip before loading", 24, store.load(fixture.id)?.messages?.size)
        assertEquals("Application store must observe the same history", 24,
            AgentSessionStore(activity!!.get().applicationContext.filesDir).load(fixture.id)?.messages?.size)
        compose.runOnUiThread { controller.loadSession(fixture.id) }
        awaitSettled(entries = 24)
        assertTrue("Existing production tail-follow must show the conversation tail", offset() > 0f)
        assertNativeMaterial(true)
        returnToTop()
        assertNativeMaterial(false)
        save("diagnostic-conversation-top")
        println("MARY_REAL_SCROLL before up offset=${offset()} maximum=${maximum()} bounds=${scrollNode().fetchSemanticsNode().boundsInRoot}")
        drag(up = true)
        save("diagnostic-conversation-after-up")
        println("MARY_REAL_SCROLL after up offset=${offset()} maximum=${maximum()}")
        assertTrue("The actual conversation LazyColumn must move", offset() > 0f)
        assertNativeMaterial(true)
        save("conversation-scrolled")
        assertEquals(geometry, controlGeometry())
        // Leave a scrolled LazyListState behind. Empty content must not inherit its glass flag.
        compose.runOnUiThread { controller.newSession() }
        awaitSettled(entries = 0)
        assertEquals("A fresh landing must keep its former branch-local lifetime", 0f, offset(), 0f)
        assertNativeMaterial(false)
        save("new-empty-after-scrolled-conversation")
        // Mount the list again, exercise real tail-follow again, then load a short transcript.
        compose.runOnUiThread { controller.loadSession(fixture.id) }
        awaitSettled(entries = 24)
        assertTrue(offset() > 0f)
        assertNativeMaterial(true)
        compose.runOnIdle { height.value = 980.dp }
        val short = store.create("Local short conversation").copy(messages = listOf(ChatMessage(MessageRole.USER, "Short local row")))
        store.save(short)
        compose.runOnUiThread { controller.loadSession(short.id) }
        awaitSettled(entries = 1)
        assertEquals("Short conversation must be at its real top", 0f, offset(), 0f)
        assertNativeMaterial(false)
        save("short-conversation")
    }

    private fun awaitSettled(entries: Int) {
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.mainClock.advanceTimeBy(16)
                compose.waitForIdle()
                val state = controller.state.value
                state.entries.size == entries && !state.running && !state.configuring
            }
        } catch (failure: Throwable) {
            val state = controller.state.value
            throw AssertionError("Real Mary initialization: expected entries=$entries, session=${controller.sessionId}, actual=${state.entries}, running=${state.running}, configuring=${state.configuring}", failure)
        }
        frames()
        compose.onNodeWithTag("mary-open-sidebar").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("mary-context-usage").assertIsDisplayed().assertHasClickAction()
    }

    private fun scrollNode() = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            !hasAnyAncestor(hasTestTag("mary-composer")) and !hasTestTag("mary-composer-input"),
        useUnmergedTree = true)
    private fun offset() = scrollNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun maximum() = scrollNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun controlGeometry() = node("mary-open-sidebar").getUnclippedBoundsInRoot() to
        node("mary-context-usage").getUnclippedBoundsInRoot()
    private fun drag(up: Boolean) {
        val target = scrollNode()
        val bounds = target.fetchSemanticsNode().boundsInRoot
        val top = maxOf(bounds.top + 8f, node("mary-open-sidebar").fetchSemanticsNode().boundsInRoot.bottom + 12f)
        val bottom = minOf(bounds.bottom - 8f, node("mary-composer").fetchSemanticsNode().boundsInRoot.top - 12f)
        assertTrue("There must be a real pointer corridor between header and composer", bottom - top > 24f)
        target.performTouchInput {
            // Use actual viewport/composer geometry, not a bottom coordinate hidden by the footer.
            // Keep the gesture away from the real centered jump-to-tail floating button.
            val corridorX = width * 0.15f
            val first = Offset(corridorX, (if (up) bottom else top) - bounds.top)
            val last = Offset(corridorX, (if (up) top else bottom) - bounds.top)
            swipe(first, last, 650)
        }
        frames()
    }
    private fun returnToTop() {
        repeat(100) { if (offset() > 0f) drag(up = false) }
        assertEquals("Only real pointer scrolling may restore the first row", 0f, offset(), 0f)
    }
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }

    private fun assertNativeMaterial(expected: Boolean) {
        val origin = if (backend == MixUiBackend.Miuix) node("mary-open-sidebar") else scrollNode()
        val info = origin.fetchSemanticsNode().layoutInfo
        val actual = compose.runOnIdle {
            val type = if (backend == MixUiBackend.Miuix) "top.yukonga.miuix.kmp.blur.DrawBackdropElement"
                else "ui.feice.mochi.effect.mask.ScrollEdgeBlurElement"
            val element = generateSequence<LayoutInfo>(info) { it.parentInfo }
                .flatMap { it.getModifierInfo().asSequence() }.map { it.modifier }
                .firstOrNull { it.javaClass.name == type }
                ?: error("Real MaryScreen native consumer is missing: $type")
            if (backend == MixUiBackend.Miuix) {
                element.javaClass.getDeclaredField("enabled").apply { isAccessible = true }.getBoolean(element)
            } else {
                @Suppress("UNCHECKED_CAST")
                val alpha = element.javaClass.getDeclaredField("alpha").apply { isAccessible = true }
                    .get(element) as () -> Float
                alpha() > 0f
            }
        }
        assertEquals("$backend must enable native material from the currently visible scroll source", expected, actual)
    }
    private fun save(name: String) {
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(evidence, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
