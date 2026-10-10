package hk.uwu.reareye.repository.rearwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RearWidgetManagerRepositoryTest {

    private fun card(
        id: String,
        pkg: String = "com.xiaomi.subscreencenter",
        business: String = "music",
        enabled: Boolean = true,
        priority: Int = 500,
    ) = RearCardConfig(
        id = id,
        title = id,
        packageName = pkg,
        business = business,
        enabled = enabled,
        priority = priority,
    )

    @Test
    fun computeDisabledCardsIncludesCardDisabledWhileSameBusinessStillEnabled() {
        val oldCards = listOf(
            card("card-a", enabled = true),
            card("card-b", enabled = true),
        )
        val newCards = listOf(
            card("card-a", enabled = false),
            card("card-b", enabled = true),
        )

        val disabled = RearWidgetManagerRepository.computeDisabledCards(oldCards, newCards)
        assertEquals(listOf("card-a"), disabled.map { it.id })
    }

    @Test
    fun computeDisabledCardsIgnoresCardsUnchanged() {
        val oldCards = listOf(
            card("card-a", enabled = true),
            card("card-b", enabled = false),
            card("card-c", enabled = true),
        )
        val newCards = listOf(
            card("card-a", enabled = true),
            card("card-b", enabled = false),
            card("card-c", enabled = true),
        )
        val disabled = RearWidgetManagerRepository.computeDisabledCards(oldCards, newCards)
        assertTrue(disabled.isEmpty())
    }

    @Test
    fun computeDisabledCardsHandlesRemovedCard() {
        val oldCards = listOf(
            card("card-a", enabled = true),
            card("card-b", enabled = true),
        )
        val newCards = listOf(card("card-b", enabled = true))
        val disabled = RearWidgetManagerRepository.computeDisabledCards(oldCards, newCards)
        assertEquals(listOf("card-a"), disabled.map { it.id })
    }

    @Test
    fun computeDisabledCardsKeepsEnabledCardAcrossBusinessPair() {
        val oldCards = listOf(
            card("card-a", business = "music", enabled = true),
            card("card-b", business = "music", enabled = true),
            card("card-c", business = "alarm", enabled = true),
        )
        val newCards = listOf(
            card("card-a", business = "music", enabled = false),
            card("card-b", business = "music", enabled = true),
            card("card-c", business = "alarm", enabled = true),
        )
        val disabled = RearWidgetManagerRepository.computeDisabledCards(oldCards, newCards)
        assertEquals(listOf("card-a"), disabled.map { it.id })
    }

    @Test
    fun automaticOrderStartsAt500AtTheBottomAndIncrementsTowardsTheTop() {
        val cards = listOf(card("a"), card("b"), card("c"))
        val settings = cards.associate { it.id to RearCardOrderSetting(automatic = true) }

        val result = RearCardPriorityManager.assignAutomaticPriorities(cards, settings)

        assertEquals(listOf("a", "b", "c"), result.map { it.id })
        assertEquals(listOf(502, 501, 500), result.map { it.priority })
    }

    @Test
    fun manualPriorityRemainsFixedWhileAutomaticCardsAreRecalculated() {
        val cards = listOf(
            card("auto-top"),
            card("manual", priority = 900),
            card("auto-bottom"),
        )
        val settings = mapOf(
            "auto-top" to RearCardOrderSetting(automatic = true),
            "manual" to RearCardOrderSetting(automatic = false),
            "auto-bottom" to RearCardOrderSetting(automatic = true),
        )

        val result = RearCardPriorityManager.assignAutomaticPriorities(cards, settings)

        assertEquals(listOf("manual", "auto-top", "auto-bottom"), result.map { it.id })
        assertEquals(900, result.first { it.id == "manual" }.priority)
        assertEquals(listOf(501, 500), result.filter { it.id != "manual" }.map { it.priority })
    }

    @Test
    fun automaticPrioritiesFollowTheNewDraggedOrder() {
        val draggedOrder =
            listOf(card("c", priority = 500), card("a", priority = 502), card("b", priority = 501))
        val settings = draggedOrder.associate { it.id to RearCardOrderSetting(automatic = true) }

        val result = RearCardPriorityManager.assignAutomaticPriorities(draggedOrder, settings)

        assertEquals(listOf("c", "a", "b"), result.map { it.id })
        assertEquals(listOf(502, 501, 500), result.map { it.priority })
    }

    @Test
    fun manuallyChangedPriorityMovesTheCardToItsEffectivePosition() {
        val cards =
            listOf(card("a", priority = 502), card("b", priority = 501), card("c", priority = 500))
        val settings =
            RearCardPriorityManager.rememberOrder(cards, emptyMap()).toMutableMap().apply {
                this["c"] = getValue("c").copy(automatic = false)
            }
        val edited = cards.map { if (it.id == "c") it.copy(priority = 900) else it }

        val result = RearCardPriorityManager.sorted(edited, settings)

        assertEquals(listOf("c", "a", "b"), result.map { it.id })
        assertEquals(listOf(900, 502, 501), result.map { it.priority })
    }

    @Test
    fun automaticPrioritySkipsAReservedManualValue() {
        val cards = listOf(card("auto", priority = 500), card("manual", priority = 500))
        val settings = mapOf(
            "auto" to RearCardOrderSetting(automatic = true),
            "manual" to RearCardOrderSetting(automatic = false),
        )

        val result = RearCardPriorityManager.assignAutomaticPriorities(cards, settings)

        assertEquals(501, result.first { it.id == "auto" }.priority)
        assertEquals(500, result.first { it.id == "manual" }.priority)
    }

    @Test
    fun tiedPrioritiesKeepTheSavedDragPosition() {
        val cards = listOf(card("a"), card("b"), card("c"))
        val settings =
            RearCardPriorityManager.rememberOrder(listOf(cards[2], cards[0], cards[1]), emptyMap())

        assertEquals(
            listOf("c", "a", "b"),
            RearCardPriorityManager.sorted(cards, settings).map { it.id })
    }
}
