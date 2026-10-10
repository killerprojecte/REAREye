package hk.uwu.reareye.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ScriptProjectStoreTest {
    @Test
    fun componentInstallReplacesAllPreviousFilesAndUninstallRemovesProject() {
        val context = RuntimeEnvironment.getApplication()
        val store = ScriptProjectStore(context)
        val source = File(context.cacheDir, "script-source-${System.nanoTime()}").apply {
            mkdirs()
            resolve("main.lua").writeText("return { id = 'demo', providers = {} }")
            resolve("old.lua").writeText("old")
        }

        store.installComponentScripts("component_demo", source)
        val installed = ScriptPaths.components(context).resolve("component_demo")
        assertTrue(installed.resolve("main.lua").isFile)
        assertTrue(installed.resolve("old.lua").isFile)

        source.resolve("old.lua").delete()
        source.resolve("new.lua").writeText("new")
        store.installComponentScripts("component_demo", source)
        assertFalse(installed.resolve("old.lua").exists())
        assertEquals("new", installed.resolve("new.lua").readText())

        assertTrue(store.uninstallComponent("component_demo"))
        assertFalse(installed.exists())
        source.deleteRecursively()
    }
}
