package hk.uwu.mixui.components

import org.junit.Assert.*
import org.junit.Test

class NavigationSelectionGateTest {
    @Test fun accessibilityAndKeyboardSelectImmediately() {
        assertEquals(2, NavigationSelectionGate().select(2))
    }

    @Test fun tapCommitsOnceAfterNativeReleaseMain() {
        val gate = NavigationSelectionGate()
        gate.begin()
        assertNull(gate.select(2)) // MIUIX down, or Mochi release/Main.
        assertNull(gate.select(2))
        assertEquals(NavigationSelectionGate.Result(2, false), gate.finish())
        assertEquals(NavigationSelectionGate.Result(null, false), gate.finish())
    }

    @Test fun verticalDragDiscardsQueuedDownEvenWhenItReturnsInside() {
        val gate = NavigationSelectionGate()
        gate.begin()
        gate.select(2)
        gate.move(beyondSlop = true, outside = true, multiplePointers = false)
        gate.move(beyondSlop = false, outside = false, multiplePointers = false)
        assertFalse(gate.openSecondary())
        assertNull(gate.select(2)) // Late release must not resurrect the tap.
        assertEquals(NavigationSelectionGate.Result(null, true), gate.finish())
    }

    @Test fun verticalLeadSuppressesTapAndHold() {
        val gate = NavigationSelectionGate()
        gate.begin()
        gate.select(0)
        gate.move(beyondSlop = true, outside = false, multiplePointers = false)
        assertNull(gate.select(1))
        assertFalse(gate.openSecondary())
        assertEquals(NavigationSelectionGate.Result(null, true), gate.finish())
    }

    @Test fun inBoundsVerticalLeadMayBecomeHorizontalNavigation() {
        val gate = NavigationSelectionGate()
        gate.begin()
        gate.select(2) // Native down callback must not survive the vertical lead.
        gate.move(beyondSlop = true, outside = false, multiplePointers = false)
        assertEquals(NavigationSelectionGate.Intent.AwaitingHorizontal, gate.intent)
        assertNull(gate.select(2))
        assertFalse(gate.openSecondary())
        gate.move(beyondSlop = true, outside = false, multiplePointers = false, horizontalDrag = true)
        assertEquals(NavigationSelectionGate.Intent.Drag, gate.intent)
        assertNull(gate.select(1)) // The adapter supplies the actual release target.
        assertEquals(NavigationSelectionGate.Result(1, false), gate.finish())
    }

    @Test fun returningToOriginAfterVerticalLeadDoesNotRestoreClickOrHold() {
        val gate = NavigationSelectionGate()
        gate.begin(); gate.select(2)
        gate.move(beyondSlop = true, outside = false, multiplePointers = false)
        gate.move(beyondSlop = false, outside = false, multiplePointers = false)
        assertEquals(NavigationSelectionGate.Intent.AwaitingHorizontal, gate.intent)
        assertNull(gate.select(2)) // Native up/click cannot restore the discarded tap.
        assertFalse(gate.openSecondary())
        assertFalse(gate.resumeNavigationDrag())
        assertEquals(NavigationSelectionGate.Result(null, true), gate.finish())
        assertEquals(1, gate.select(1)) // Keyboard/accessibility remains available afterward.
    }

    @Test fun recoveringDirectionDoesNotRestoreDiscardedNativeDown() {
        val gate = NavigationSelectionGate()
        gate.begin(); gate.select(2)
        gate.move(beyondSlop = true, outside = false, multiplePointers = false)
        gate.move(beyondSlop = true, outside = false, multiplePointers = false, horizontalDrag = true)
        assertEquals(NavigationSelectionGate.Result(null, true), gate.finish())
    }

    @Test fun hardCancellationAfterVerticalLeadCannotRecoverAsHorizontal() {
        for (reason in listOf("bounds", "multiple pointers", "ancestor or system")) {
            val gate = NavigationSelectionGate()
            gate.begin(); gate.select(2)
            gate.move(beyondSlop = true, outside = false, multiplePointers = false)
            when (reason) {
                "bounds" -> gate.move(beyondSlop = true, outside = true, multiplePointers = false)
                "multiple pointers" -> gate.move(beyondSlop = false, outside = false, multiplePointers = true)
                else -> gate.cancel()
            }
            assertEquals(reason, NavigationSelectionGate.Intent.Cancelled, gate.intent)
            gate.move(beyondSlop = true, outside = false, multiplePointers = false, horizontalDrag = true)
            assertEquals(reason, NavigationSelectionGate.Intent.Cancelled, gate.intent)
            assertNull(gate.select(1))
            assertFalse(gate.openSecondary())
            assertFalse(gate.resumeNavigationDrag())
            assertEquals(reason, NavigationSelectionGate.Result(null, true), gate.finish())
        }
    }

    @Test fun horizontalDragSuppressesHoldAndCommitsReleaseTarget() {
        val gate = NavigationSelectionGate()
        gate.begin(); gate.select(0)
        gate.move(beyondSlop = true, outside = false, multiplePointers = false, horizontalDrag = true)
        assertEquals(NavigationSelectionGate.Intent.Drag, gate.intent)
        assertFalse(gate.openSecondary())
        gate.select(2)
        assertEquals(NavigationSelectionGate.Result(2, false), gate.finish())
    }

    @Test fun leavingBarEvenWithinSlopCancels() {
        val gate = NavigationSelectionGate()
        gate.begin()
        gate.select(2)
        gate.move(beyondSlop = false, outside = true, multiplePointers = false)
        assertEquals(NavigationSelectionGate.Result(null, true), gate.finish())
    }

    @Test fun secondaryDragCanLeaveBarButNeverSelectsRoute() {
        val gate = NavigationSelectionGate()
        gate.begin()
        gate.select(2)
        assertTrue(gate.openSecondary())
        gate.move(beyondSlop = true, outside = true, multiplePointers = false)
        assertEquals(NavigationSelectionGate.Intent.Secondary, gate.intent)
        assertNull(gate.select(3)) // Mochi has already released indicator to this index.
        assertEquals(NavigationSelectionGate.Result(null, true), gate.finish())
    }

    @Test fun onlyAnActiveSecondaryCanReturnToNavigation() {
        val gate = NavigationSelectionGate()
        assertFalse(gate.resumeNavigationDrag())
        gate.begin()
        assertFalse(gate.resumeNavigationDrag())
        gate.openSecondary()
        assertTrue(gate.resumeNavigationDrag())
        gate.select(1)
        assertEquals(NavigationSelectionGate.Result(1, false), gate.finish())
        gate.begin(); gate.openSecondary(); gate.cancel()
        assertFalse(gate.resumeNavigationDrag())
        gate.select(1)
        assertEquals(NavigationSelectionGate.Result(null, true), gate.finish())
    }

    @Test fun multitouchCancelsSecondaryAndSuppressesLateRelease() {
        val gate = NavigationSelectionGate()
        gate.begin()
        gate.openSecondary()
        gate.move(beyondSlop = false, outside = false, multiplePointers = true)
        assertEquals(NavigationSelectionGate.Intent.Cancelled, gate.intent)
        assertNull(gate.select(1))
        assertEquals(NavigationSelectionGate.Result(null, true), gate.finish())
    }

    @Test fun cancellationResetsNativeWithoutLeakingIntoNextTap() {
        val gate = NavigationSelectionGate()
        gate.begin()
        gate.select(2)
        gate.cancel()
        assertEquals(NavigationSelectionGate.Result(null, true), gate.finish())
        gate.begin()
        gate.select(1)
        assertEquals(NavigationSelectionGate.Result(1, false), gate.finish())
        assertEquals(3, gate.select(3))
    }

    @Test fun missingNativeCallbackAlsoRequestsIndicatorReset() {
        val gate = NavigationSelectionGate()
        gate.begin()
        assertEquals(NavigationSelectionGate.Result(null, true), gate.finish())
    }
}
