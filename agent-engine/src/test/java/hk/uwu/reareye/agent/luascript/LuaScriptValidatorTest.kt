package hk.uwu.reareye.agent.luascript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The valid examples are taken from SCRIPTING.md, so a change to the documented contract that
 * breaks them shows up here.
 */
class LuaScriptValidatorTest {

    /** The minimal script straight out of SCRIPTING.md. */
    private val minimal = """
        local function hello(params, config, context)
            return "Hello " .. tostring(params.name or "REAREye")
        end

        return {
            id = "hello_script",
            name = "Hello Script",
            description = "A small example provider",
            group = "examples",
            providers = { hello = hello },
        }
    """.trimIndent()

    /** The full provider definition form from SCRIPTING.md. */
    private val withConfigs = """
        return {
            providers = {
                weather = {
                    name = "Weather",
                    description = "Fetch weather data",
                    mimeType = "application/json",
                    mode = "REFRESH",
                    ttlMillis = 300000,
                    handler = function(params, config, context)
                        return { city = params.city or config.city }
                    end,
                },
            },
            configs = {
                { id = "city", type = "STRING", default = "Shanghai", note = "查询城市" },
                { id = "count", type = "NUMBER", default = 3, min = 1, max = 10, step = 1 },
                {
                    id = "unit",
                    type = "OPTIONS",
                    default = "celsius",
                    options = {
                        { id = "celsius", name = "摄氏度" },
                    },
                },
            },
        }
    """.trimIndent()

    private fun codes(source: String) =
        LuaScriptValidator.validate(source).errors.map { it.code }

    @Test
    fun `the documented minimal script passes`() {
        val result = LuaScriptValidator.validate(minimal)
        assertTrue("expected no errors, got ${result.report()}", result.passed)
    }

    @Test
    fun `the documented script with configs passes`() {
        val result = LuaScriptValidator.validate(withConfigs)
        assertTrue("expected no errors, got ${result.report()}", result.passed)
    }

    /**
     * Config entries carry their own `id`, so a check that only looked at the top-level id would
     * miss a malformed one.
     */
    @Test
    fun `an invalid id inside a config entry is caught`() {
        val bad = withConfigs.replace("""id = "count"""", """id = "count total"""")
        assertTrue("invalid_id" in codes(bad))
    }

    @Test
    fun `an invalid declared id is caught`() {
        val bad = minimal.replace("""id = "hello_script"""", """id = "hello script"""")
        assertTrue("invalid_id" in codes(bad))
    }

    @Test
    fun `a script with no return is rejected`() {
        assertTrue("missing_return" in codes("""local x = 1"""))
    }

    @Test
    fun `a script with no providers is rejected`() {
        assertTrue("missing_providers" in codes("""return { id = "x", name = "X" }"""))
    }

    @Test
    fun `empty source is rejected`() {
        assertTrue("empty_source" in codes(""))
        assertTrue("empty_source" in codes("   \n  \n"))
    }

    /** Root and network are documented capabilities, so they warn rather than fail. */
    @Test
    fun `root usage warns but does not fail`() {
        val source = minimal.replace(
            """return "Hello " .. tostring(params.name or "REAREye")""",
            """return context.root:exec("id").stdout""",
        )
        val result = LuaScriptValidator.validate(source)
        assertTrue("must not be an error", result.passed)
        assertTrue("uses_root" in result.warnings.map { it.code })
    }

    @Test
    fun `network usage warns but does not fail`() {
        val source = minimal.replace(
            """return "Hello " .. tostring(params.name or "REAREye")""",
            """return context.http:get("https://example.com").body""",
        )
        val result = LuaScriptValidator.validate(source)
        assertTrue("must not be an error", result.passed)
        assertTrue("uses_network" in result.warnings.map { it.code })
    }

    /**
     * `context` is a normal parameter name; only a member access on it should count. A bare mention
     * must not raise a warning, or every script taking a `context` argument would look suspicious.
     */
    @Test
    fun `merely naming the context parameter does not warn`() {
        val result = LuaScriptValidator.validate(minimal)
        assertEquals(emptyList<String>(), result.warnings.map { it.code })
    }

    /**
     * Comments are blanked before scanning. Without that, the words in this comment would satisfy
     * both keyword checks and a genuinely broken script would be reported as valid.
     */
    @Test
    fun `keywords inside a comment do not mask a real problem`() {
        val source = """local x = 1 -- no return, no providers"""
        val found = codes(source)
        assertTrue("expected missing_return, got $found", "missing_return" in found)
        assertTrue("expected missing_providers, got $found", "missing_providers" in found)
    }

    /**
     * The opposite failure, and the more damaging one: an example id in a comment must not be
     * validated as a real declaration.
     */
    @Test
    fun `an id inside a comment is not validated`() {
        val source = """
            -- 例如 id = "my script" 这样写是不行的
            local function hello() return "hi" end
            return { providers = { hello = hello } }
        """.trimIndent()
        assertFalse("invalid_id" in codes(source))
    }

    /** A `--` inside a string is not a comment start. */
    @Test
    fun `a double dash inside a string does not start a comment`() {
        val source = """
            local sep = "a--b"
            local function hello() return sep end
            return { providers = { hello = hello } }
        """.trimIndent()
        assertFalse("missing_providers" in codes(source))
    }

    /** A long-bracket comment must be skipped whole, not swallow the rest of the file. */
    @Test
    fun `a long bracket comment is skipped`() {
        val source = """
            --[[
            return { providers = { fake = function() end } }
            ]]
            local function hello() return "hi" end
            return { providers = { hello = hello } }
        """.trimIndent()
        val result = LuaScriptValidator.validate(source)
        assertTrue("expected no errors, got ${result.report()}", result.passed)
    }

    @Test
    fun `the report lists errors with their codes`() {
        val report = LuaScriptValidator.validate("""local x = 1""").report()
        assertTrue(report.contains("missing_return"))
        assertTrue(report.contains("missing_providers"))
    }

    @Test
    fun `a wide table return is not mistaken for a missing return`() {
        val source = """
            local M = {}
            M.providers = { ping = function() return "pong" end }
            M.configs = { { id = "n", type = "NUMBER" } }
            return M
        """.trimIndent()
        assertFalse("missing_return" in codes(source))
        assertFalse("missing_providers" in codes(source))
    }
}
