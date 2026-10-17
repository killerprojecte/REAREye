package hk.uwu.reareye.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MaryToolCardsTest {
    private val arguments =
        """{"question":"Pick one","option_ids":["a","b"],"option_labels":["Alpha","Beta"]}"""

    @Test
    fun ordinaryCardsDefaultCollapsedAndKeepManualChoice() {
        val state = ToolCardExpansion()
        assertFalse(state.isExpanded("row"))
        state.toggle("row")
        state.synchronize("row", null)
        assertTrue(state.isExpanded("row"))
        assertFalse(state.isExpanded("different-row"))
    }

    @Test
    fun pendingAutoExpandsButAllowsManualCollapseAndReopen() {
        val state = ToolCardExpansion()
        assertTrue(state.isExpanded("row", "ask:one"))
        state.synchronize("row", "ask:one")
        state.toggle("row", "ask:one")
        state.synchronize("row", "ask:one")
        assertFalse(state.isExpanded("row", "ask:one"))
        state.toggle("row", "ask:one")
        assertTrue(state.isExpanded("row", "ask:one"))
    }

    @Test
    fun acceptedCollapsesImmediatelyAndEndingCollapsesOnlyOnce() {
        val state = ToolCardExpansion()
        state.synchronize("row", "ask:one")
        state.accepted("row", "ask:one")
        assertFalse(state.isExpanded("row", "ask:one"))
        assertTrue(state.isSubmitted("row", "ask:one"))
        state.toggle("row", "ask:one")
        assertTrue(state.isExpanded("row", "ask:one"))
        assertFalse(state.isExpanded("row", null))
        state.synchronize("row", null)
        state.toggle("row")
        state.synchronize("row", null)
        assertTrue(state.isExpanded("row"))
        assertFalse(state.isSubmitted("row", null))
    }

    @Test
    fun cancelledOffscreenPendingCollapsesAndNewRequestHasFreshDraft() {
        val state = ToolCardExpansion()
        state.synchronize("row", "ask:one")
        state.draft("ask:one").select("a", false)
        assertFalse(state.isExpanded("row", null))
        state.synchronize("row", null)
        assertTrue(state.draft("ask:one").selected.isEmpty())
        assertTrue(state.isExpanded("row", "ask:two"))
        state.synchronize("row", "ask:two")
        assertTrue(state.draft("ask:two").selected.isEmpty())
        assertFalse(ToolCardExpansion().isExpanded("row"))
    }

    @Test
    fun rejectedSubmitRetainsSelectionCustomTextAndExpandedState() {
        val state = ToolCardExpansion()
        state.synchronize("row", "ask:one")
        val draft = state.draft("ask:one")
        draft.select("a", true)
        draft.editCustom("More context", true)
        var callbacks = 0
        draft.submit(draft.answer(true)) { ids, text ->
            callbacks++
            assertEquals(listOf("a"), ids)
            assertEquals("More context", text)
            false
        }
        assertEquals(1, callbacks)
        assertTrue(draft.failed)
        assertTrue(state.isExpanded("row", "ask:one"))
        assertEquals(listOf("a"), state.draft("ask:one").selected)
        assertEquals("More context", draft.custom)
        assertFalse(state.isSubmitted("row", "ask:one"))
    }

    @Test
    fun headerToggleDoesNotSubmitOrDiscardFailedDraft() {
        val state = ToolCardExpansion()
        state.synchronize("row", "ask:one")
        val draft = state.draft("ask:one")
        draft.select("a", true)
        draft.editCustom("Keep this answer", true)
        draft.submit(draft.answer(true)) { _, _ -> false }

        state.toggle("row", "ask:one")
        assertFalse(state.isExpanded("row", "ask:one"))
        assertFalse(state.isSubmitted("row", "ask:one"))
        state.toggle("row", "ask:one")
        assertTrue(state.isExpanded("row", "ask:one"))
        assertSame(draft, state.draft("ask:one"))
        assertTrue(draft.failed)
        assertEquals(listOf("a"), draft.selected)
        assertEquals("Keep this answer", draft.custom)
    }

    @Test
    fun singleChoiceAndCustomAreExclusiveButMultipleCanCombineThem() {
        val draft = ToolAnswerDraft()
        draft.select("a", false)
        draft.editCustom("custom", false)
        assertTrue(draft.selected.isEmpty())
        draft.select("b", false)
        assertEquals("", draft.custom)
        draft.select("a", true)
        draft.editCustom("custom", true)
        assertEquals(listOf("b", "a"), draft.selected)
        assertEquals("custom", draft.answer(true).customText)
        assertNull(draft.answer(false).customText)
        draft.select("a", true)
        assertEquals(listOf("b"), draft.selected)
    }

    @Test
    fun historyRestoresSingleMultipleAndCustomWithoutCallbacks() {
        val single = parseMaryAskHistory(arguments, """{"selected_ids":["b"]}""")!!
        assertEquals("Pick one", single.request.question)
        assertEquals(listOf("Alpha", "Beta"), single.request.options.map { it.label })
        assertEquals(listOf("b"), single.answer!!.selectedIds)
        val multiArgs = arguments.dropLast(1) + ",\"multiple\":true}"
        val multiple = parseMaryAskHistory(
            multiArgs,
            """{"selected_ids":["a","b"],"custom_text":"More"}"""
        )!!
        assertEquals(listOf("a", "b"), multiple.answer!!.selectedIds)
        assertEquals("More", multiple.answer.customText)
        assertNull(parseMaryAskHistory(arguments, null)!!.answer)
        assertEquals(
            "Only custom", parseMaryAskHistory(
                """{"question":"Explain"}""",
                """{"selected_ids":[],"custom_text":"Only custom"}"""
            )!!.answer!!.customText
        )
    }

    @Test
    fun malformedHistoryFallsBackRatherThanInventingAnAnswer() {
        assertNull(parseMaryAskHistory("not json", null))
        assertNull(parseMaryAskHistory("""{"question":"Q","option_ids":["a"]}""", null))
        assertNull(parseMaryAskHistory("""{"question":"Q","purpose":"custom"}""", null))
        assertNull(parseMaryAskHistory(arguments, "tool interrupted"))
        assertNull(parseMaryAskHistory(arguments, """{"selected_ids":["missing"]}"""))
        assertNull(parseMaryAskHistory(arguments, """{"selected_ids":["a","b"]}"""))
        assertNull(
            parseMaryAskHistory(
                arguments,
                """{"selected_ids":["a"],"custom_text":"two answers"}"""
            )
        )
        assertNull(parseMaryAskHistory(arguments, """{"selected_ids":[]}"""))
    }
}
