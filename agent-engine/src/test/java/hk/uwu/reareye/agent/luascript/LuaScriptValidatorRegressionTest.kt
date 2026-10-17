package hk.uwu.reareye.agent.luascript

import org.junit.Assert.assertTrue
import org.junit.Test

class LuaScriptValidatorRegressionTest {
    @Test
    fun businessDataIdsAndOptionValuesDoNotRejectValidLua() {
        val result = LuaScriptValidator.validate(
            """
            local function data() return { id = "订单 123" } end
            return {
                providers = { data = data },
                configs = { { id = "choice", type = "OPTIONS", options = {
                    { id = "任意 选项", name = "选项" }
                } } }
            }
        """.trimIndent()
        )
        assertTrue(result.report(), result.passed)
        assertTrue(result.warnings.any { it.code == "unverified_id" })
        assertTrue(result.report().contains("unverified_id"))
    }

    @Test
    fun dynamicallyBuiltDescriptorWarnsWithoutBeingRejected() {
        val result = LuaScriptValidator.validate(
            """
            local descriptor = {}
            descriptor["pro" .. "viders"] = { ping = function() return "pong" end }
            return descriptor
        """.trimIndent()
        )
        assertTrue(result.report(), result.passed)
        assertTrue(result.warnings.any { it.code == "missing_providers" })
        assertTrue(result.report().contains("runtime"))
    }

    @Test
    fun longBracketCommentsCannotSupplyTheRequiredReturn() {
        val result = LuaScriptValidator.validate("--[=[ return { providers = {} } ]=]\nlocal x = 1")
        assertTrue(result.errors.any { it.code == "missing_return" })
    }

    @Test
    fun modelReportsAreEnglishWhileIdsRemainUnchanged() {
        val result = LuaScriptValidator.validate("return { providers = {}, id = '用户 数据' }")
        assertTrue(result.report().contains("用户 数据"))
        assertTrue(result.report().contains("runtime descriptor validation"))
        assertTrue(result.warnings.any { it.code == "unverified_id" })
    }
}
