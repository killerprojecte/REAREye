package hk.uwu.reareye.ui.components

import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.provider.ChatEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaryChatControlsTest {
    @Test
    fun composerGeometryKeepsOneActionPairAtBothEndpoints() {
        assertEquals(MaryComposerGeometry(44, 44, 0), maryComposerGeometry(300, 44, 44, 44, 0f))
        assertEquals(MaryComposerGeometry(88, 0, 44), maryComposerGeometry(300, 44, 44, 44, 1f))
        // A taller multiline/large-font input centers compact actions, not the entire bar.
        assertEquals(MaryComposerGeometry(120, 44, 38), maryComposerGeometry(220, 120, 44, 44, 0f))
        assertEquals(MaryComposerGeometry(164, 0, 120), maryComposerGeometry(220, 120, 44, 44, 1f))
    }

    @Test
    fun composerCollapseGeometryIsMonotonicAndKeepsFullTouchTargetsInside() {
        listOf(44, 68, 120, 208).forEach { inputHeight ->
            val frames =
                (0..220).map { step -> maryComposerGeometry(220, inputHeight, 44, 44, step / 220f) }
            frames.forEach { frame ->
                assertTrue(frame.actionY >= 0)
                assertTrue(frame.actionY + 44 <= frame.height)
                assertTrue(frame.inputInset >= 0 && 220 - 2 * frame.inputInset > 0)
            }
            frames.zipWithNext().forEach { (before, after) ->
                assertTrue(after.height >= before.height)
                assertTrue(after.inputInset <= before.inputInset)
                assertTrue(after.actionY >= before.actionY)
                // No tail-frame branch swaps rows or jumps their positions.
                assertTrue(after.height - before.height <= 1)
                assertTrue(after.actionY - before.actionY <= 1)
            }
        }
    }

    @Test
    fun composerGeometryReversesAndClampsItsViewport() {
        val progress = listOf(0f, 0.2f, 0.55f, 0.8f, 1f)
        val forward = progress.map { maryComposerGeometry(196, 96, 44, 44, it) }
        val reversed = progress.reversed().map { maryComposerGeometry(196, 96, 44, 44, it) }
        assertEquals(forward, reversed.reversed())
        forward.forEach { frame ->
            val viewportWidth = 196 - 2 * frame.inputInset
            assertEquals(196, viewportWidth + 2 * frame.inputInset)
        }
        assertEquals(forward.first(), maryComposerGeometry(196, 96, 44, 44, -1f))
        assertEquals(forward.last(), maryComposerGeometry(196, 96, 44, 44, 2f))
        assertEquals(20, maryComposerGeometry(40, 44, 44, 44, 0f).inputInset)
    }

    @Test
    fun emptyUnfocusedComposerIsCompact() {
        assertFalse(isMaryComposerExpanded(false, ""))
        assertTrue(isMaryComposerExpanded(true, ""))
    }

    @Test
    fun contentKeepsComposerExpandedWithoutKeyboard() {
        assertTrue(isMaryComposerExpanded(false, "hello"))
        assertTrue(isMaryComposerExpanded(false, " "))
        assertTrue(isMaryComposerExpanded(false, "\n"))
    }

    @Test
    fun pickerKeepsEmptyComposerMountedUntilDismissed() {
        assertTrue(isMaryComposerExpanded(false, "", pickerOpen = true))
        assertFalse(isMaryComposerExpanded(false, "", pickerOpen = false))
    }

    @Test
    fun keyboardClosingTargetReleasesFocusBeforeInsetsReachZero() {
        // The target is already zero while the current IME inset is still animating down.
        assertTrue(
            shouldReleaseMaryComposerFocus(
                imeWasVisible = true,
                imeTargetBottom = 0,
                pickerOpen = false
            )
        )
        assertFalse(
            shouldReleaseMaryComposerFocus(
                imeWasVisible = true,
                imeTargetBottom = 320,
                pickerOpen = false
            )
        )
    }

    @Test
    fun hardwareKeyboardAndInitialFocusDoNotCollapseComposer() {
        assertFalse(
            shouldReleaseMaryComposerFocus(
                imeWasVisible = false,
                imeTargetBottom = 0,
                pickerOpen = false
            )
        )
        assertTrue(isMaryComposerExpanded(true, ""))
    }

    @Test
    fun pickerKeyboardDismissalIsNotReplayedWhenTheSheetCloses() {
        assertFalse(
            shouldReleaseMaryComposerFocus(
                imeWasVisible = true,
                imeTargetBottom = 0,
                pickerOpen = true
            )
        )
        // Closing the sheet before IME animation finishes still must preserve editing.
        assertFalse(shouldReleaseMaryComposerFocus(true, 0, false, pickerHidingIme = true))
        // The picker consumes imeWasVisible; once hidden, there is no stale dismissal.
        assertFalse(shouldReleaseMaryComposerFocus(false, 0, false))
        // A later independent keyboard dismissal keeps the normal compact behavior.
        assertTrue(shouldReleaseMaryComposerFocus(true, 0, false))
    }

    @Test
    fun restoredEditingFocusIsNotStickyAfterAnExplicitFocusMove() {
        assertTrue(isMaryComposerExpanded(true, "", pickerOpen = false))
        assertFalse(isMaryComposerExpanded(false, "", pickerOpen = false))
        assertTrue(isMaryComposerExpanded(false, "draft", pickerOpen = false))
    }

    @Test
    fun reasoningSliderPositionIsContinuousAndClamped() {
        assertEquals(1.25f, reasoningSliderProgressAt(45f, 140f, 20f, 5, false), 0.0001f)
        assertEquals(0f, reasoningSliderProgressAt(-100f, 140f, 20f, 5, false), 0f)
        assertEquals(5f, reasoningSliderProgressAt(500f, 140f, 20f, 5, false), 0f)
    }

    @Test
    fun reasoningSliderMirrorsRtlAndSupportsDynamicLevelCounts() {
        assertEquals(3.75f, reasoningSliderProgressAt(45f, 140f, 20f, 5, true), 0.0001f)
        assertEquals(0.5f, reasoningSliderProgressAt(70f, 140f, 20f, 1, false), 0.0001f)
        assertEquals(0f, reasoningSliderProgressAt(0f, 0f, 20f, 0, false), 0f)
    }

    @Test
    fun dismissedKeyboardKeepsNonemptyDraftExpanded() {
        val releaseFocus = shouldReleaseMaryComposerFocus(true, 0, false)
        assertFalse(isMaryComposerExpanded(focused = !releaseFocus, value = ""))
        assertTrue(isMaryComposerExpanded(focused = !releaseFocus, value = "draft"))
        assertTrue(isMaryComposerExpanded(focused = !releaseFocus, value = " "))
    }

    @Test
    fun gaugesHaveOrderedSemanticAnglesAndUprightMedium() {
        val angles = ReasoningEffort.entries.map(::reasoningNeedleAngle)
        assertTrue(angles.zipWithNext().all { (left, right) -> left < right })
        assertEquals(270f, reasoningNeedleAngle(ReasoningEffort.MEDIUM), 0f)
    }

    @Test
    fun contextRingIsGrayWithoutUsageAndSweepsClockwiseFromTop() {
        assertEquals(0f, contextProgressSweep(null), 0f)
        assertEquals(0f, contextProgressSweep(0.0), 0f)
        assertEquals(0f, contextProgressSweep(Double.NaN), 0f)
        assertEquals(0f, contextProgressSweep(Double.POSITIVE_INFINITY), 0f)
        assertEquals(0f, contextProgressSweep(Double.NEGATIVE_INFINITY), 0f)
        assertEquals(90f, contextProgressSweep(0.25), 0f)
        assertEquals(180f, contextProgressSweep(0.5), 0f)
        assertEquals(270f, contextProgressSweep(0.75), 0f)
        assertEquals(360f, contextProgressSweep(1.0), 0f)
        assertEquals(360f, contextProgressSweep(2.0), 0f)
        assertEquals(0f, contextProgressSweep(-1.0), 0f)
    }

    @Test
    fun contextEstimateIsDistinctFromLatestBilledInputAndCache() {
        val summary =
            MaryUsageSummary(250, 1000, ChatEvent.Usage(inputTokens = 200, cachedInputTokens = 100))
        assertEquals(0.25, summary.contextRatio!!, 0.0001)
        assertEquals(0.5, summary.cacheRatio!!, 0.0001)
        assertEquals(1.25, MaryUsageSummary(1250, 1000, null).contextRatio!!, 0.0001)
    }

    @Test
    fun absentOrInvalidTelemetryDoesNotInventPercentages() {
        assertNull(MaryUsageSummary(null, 1000, null).contextRatio)
        assertNull(MaryUsageSummary(-1, 1000, null).contextRatio)
        assertNull(MaryUsageSummary(50, 0, null).contextRatio)
        listOf(
            ChatEvent.Usage(), ChatEvent.Usage(inputTokens = 0, cachedInputTokens = 0),
            ChatEvent.Usage(inputTokens = 100, cachedInputTokens = -1),
            ChatEvent.Usage(inputTokens = 100, cachedInputTokens = 101)
        ).forEach {
            assertNull(MaryUsageSummary(50, 100, it).cacheRatio)
        }
        assertEquals(
            0.0,
            MaryUsageSummary(
                0,
                100,
                ChatEvent.Usage(inputTokens = 100, cachedInputTokens = 0)
            ).cacheRatio!!,
            0.0
        )
    }
}
