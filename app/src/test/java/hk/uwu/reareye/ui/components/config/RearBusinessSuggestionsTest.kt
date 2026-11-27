package hk.uwu.reareye.ui.components.config

import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import org.junit.Assert.*
import org.junit.Test

class RearBusinessSuggestionsTest {
    private fun entry(name: String, index: Int) = RearBusinessConfig(
        "id-$index", "fixture.$index", name, "/fixture/$index", defaultPriority = index,
    )

    private fun original(catalog: List<RearBusinessConfig>, value: String): List<RearBusinessConfig> {
        val businesses = catalog.distinctBy { it.business }.sortedBy { it.business.lowercase() }
        val query = value.trim()
        return if (query.isEmpty()) businesses else businesses
            .filter { it.business.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<RearBusinessConfig> {
                when {
                    it.business.equals(query, ignoreCase = true) -> 0
                    it.business.startsWith(query, ignoreCase = true) -> 1
                    else -> 2
                }
            }.thenBy { it.business.lowercase() })
    }

    @Test fun filteringPreservesRankingCaseUnicodeAndOriginalDuplicateSelection() {
        val catalog = listOf("zClock", "clock.z", "CLOCK", "a.clock", "clock", "clock.z",
            "İstanbul", "istanbul", "Σ", "ς", "组件.时钟", "clock.A").mapIndexed { i, name -> entry(name, i) }
        val index = RearBusinessSuggestions(catalog)
        for (query in listOf("", "  ", "clock", " CLOCK ", "clock.", "z", "i", "İ", "σ", "Σ", "时钟", "missing")) {
            val result = index.filter(query)
            assertEquals("query=$query", original(catalog, query), result.entries)
            assertEquals(result.entries.map { it.business }, result.labels)
            assertEquals(result.entries.map { it.packageName }, result.packages)
        }
        assertEquals(1, index.filter("clock.z").entries.single().defaultPriority)
    }

    @Test fun emptyQueryReusesThePreparedCatalogAndNewSnapshotsExposeUpdatedData() {
        val first = entry("clock.alpha", 1)
        val old = RearBusinessSuggestions(listOf(first))
        assertSame(old.filter(""), old.filter(" "))
        val updated = RearBusinessSuggestions(listOf(first.copy(defaultPriority = 700), entry("clock.beta", 2)))
        assertEquals(700, updated.filter("alpha").entries.single().defaultPriority)
        assertEquals(listOf("clock.alpha", "clock.beta"), updated.filter("clock").labels)
        assertEquals(listOf(first), old.filter("").entries)
    }

    @Test fun largeCatalogMatchesTheExistingSelectionContractForContinuousInput() {
        val catalog = (0 until 10_000).reversed().map { entry("widget.${it.toString().padStart(5, '0')}", it) }
        val index = RearBusinessSuggestions(catalog)
        for (query in listOf("w", "wi", "wid", "widget", "widget.", "widget.0", "widget.00", "widget.000", "widget.00042", "", "missing")) {
            assertEquals("query=$query", original(catalog, query), index.filter(query).entries)
        }
    }
}
