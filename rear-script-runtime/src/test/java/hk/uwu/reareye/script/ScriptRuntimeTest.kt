package hk.uwu.reareye.script

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptRuntimeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun project(source: String, standalone: Boolean = false): ScriptProject {
        val root = folder.newFolder()
        val main = root.resolve("main.lua").apply { writeText(source) }
        return ScriptProject(
            "test", "Test", root, main,
            if (standalone) ScriptProjectKind.STANDALONE else ScriptProjectKind.PROJECT
        )
    }

    @Test
    fun functionReferencesAndMetadataExecute() {
        val project = project(
            """
            local function greet(params, config, context)
                context:log("started")
                return { message = "Hello " .. params.name, count = config.count, project = context.projectId }
            end
            return { id = "greeting", name = "Greeting", providers = {
                greeting = { handler = greet, mimeType = "application/json" }
            }, configs = { { id = "count", type = "NUMBER", default = 3, min = 0, max = 5 } } }
        """.trimIndent()
        )
        ScriptRuntime().open(project).use { session ->
            assertEquals("Greeting", session.descriptor.name)
            val result = session.execute("greeting", mapOf("name" to "Lua"), emptyMap())
            assertEquals(
                mapOf("message" to "Hello Lua", "count" to 3.0, "project" to "test"),
                result.data
            )
            assertEquals("application/json", result.mimeType)
            assertEquals(listOf("started"), result.logs)
        }
    }

    @Test
    fun projectCanRequireLocalModulesAndReadLocalData() {
        val project = project(
            """
            local helper = require("lib.helper")
            return { providers = { value = function(_, _, context)
                return helper() .. context:readText("data.txt")
            end } }
        """.trimIndent()
        )
        project.root.resolve("lib").mkdirs()
        project.root.resolve("lib/helper.lua").writeText("return function() return 'module:' end")
        project.root.resolve("data.txt").writeText("data")
        ScriptRuntime().open(project)
            .use { assertEquals("module:data", it.execute("value", emptyMap(), emptyMap()).data) }
    }

    @Test
    fun standaloneHasNoFileOrJavaAccess() {
        val project = project(
            """
            return { providers = { check = function(_, _, context)
                return require == nil and io == nil and luajava == nil and java == nil and context.readText == nil
            end } }
        """.trimIndent(), standalone = true
        )
        ScriptRuntime().open(project)
            .use { assertEquals(true, it.execute("check", emptyMap(), emptyMap()).data) }
    }

    @Test
    fun projectCannotReadOutsideRoot() {
        val project =
            project("return { providers = { read = function(_, _, ctx) return ctx:readText('../secret') end } }")
        ScriptRuntime().open(project).use { session ->
            val error =
                runCatching { session.execute("read", emptyMap(), emptyMap()) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains("Path escapes project"))
        }
    }

    @Test(timeout = 5000)
    fun infiniteLoopIsInterrupted() {
        val project = project("return { providers = { loop = function() while true do end end } }")
        ScriptRuntime().open(project, timeoutMillis = 50).use { session ->
            assertNotNull(runCatching {
                session.execute(
                    "loop",
                    emptyMap(),
                    emptyMap()
                )
            }.exceptionOrNull())
        }
    }
}
