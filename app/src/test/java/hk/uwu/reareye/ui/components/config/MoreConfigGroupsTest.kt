package hk.uwu.reareye.ui.components.config

import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.REAREyeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreConfigGroupsTest {
    @Test
    fun groupsCoverEveryOriginalItemExceptFourExistingTabManagers() {
        val originalItems = flattenMoreSourceItems(REAREyeConfig)
        val originalKeys = originalItems.map { it.key }
        val assignedKeys = MoreConfigGroups.flatMap { it.itemKeys }
        val expectedKeys = originalKeys.toSet() - MoreHiddenConfigKeys

        assertEquals(originalKeys.size, originalKeys.distinct().size)
        assertEquals(4, MoreHiddenConfigKeys.size)
        assertTrue(originalKeys.toSet().containsAll(MoreHiddenConfigKeys))
        assertEquals(expectedKeys, assignedKeys.toSet())
        assertEquals(assignedKeys.size, assignedKeys.distinct().size)
        assertTrue((assignedKeys.toSet() - originalKeys.toSet()).isEmpty())
        assertEquals(listOf(14, 2, 10, 4, 4, 16, 7), MoreConfigGroups.map { it.itemKeys.size })
        assertEquals(7, MoreConfigGroups.map { it.icon }.distinct().size)

        val categories = buildMoreCategories(REAREyeConfig)
        val originalByKey = originalItems.associateBy { it.key }
        categories.flatMap { it.category.children }.forEach { node ->
            assertSame(originalByKey[node.key], node)
        }
    }

    @Test
    fun specialItemsBelongToTheirRequiredGroups() {
        val groupByKey = MoreConfigGroups.flatMap { group ->
            group.itemKeys.map { key -> key to group.id }
        }.toMap()

        assertEquals("apps_runtime", groupByKey[ConfigKeys.CFG_CUSTOM_BOUNDS_COMPAT_MANAGER])
        listOf(
            ConfigKeys.HOOK_UNLIMITED_SUBSCREEN_APP_LIST,
            ConfigKeys.HOOK_UNLOCK_VIDEO_RESTRICTIONS,
            ConfigKeys.HOOK_UNLOCK_TEMPLATE_MAXIMUM_LIMIT,
            ConfigKeys.MISC_HOOK_GMS_UNLOCK,
        ).forEach { key -> assertEquals("unlock_limits", groupByKey[key]) }
    }

    @Test
    fun searchUsesOriginalTitleDescriptionAndNewCategoryTitle() {
        val categories = buildMoreCategories(REAREyeConfig)
        val category = categories.first { it.group.id == "apps_runtime" }
        val item = category.category.children.first {
            it.key == ConfigKeys.ALLOW_EXTERNAL_DISPLAY_LAUNCH
        } as ConfigItem
        val entry = MoreSearchEntry(item, category)
        val strings = mapOf(
            item.titleRes to "MixedCase Setting",
            item.descriptionRes to "Background permission",
            category.group.titleRes to "Apps and runtime",
        )
        val resolve: (Int) -> String = { strings[it] ?: "" }

        assertEquals(listOf(entry), searchMoreEntries(listOf(entry), "  mixedcase  ", resolve))
        assertEquals(listOf(entry), searchMoreEntries(listOf(entry), "PERMISSION", resolve))
        assertEquals(listOf(entry), searchMoreEntries(listOf(entry), "RUNTIME", resolve))
        assertTrue(searchMoreEntries(listOf(entry), "  ", resolve).isEmpty())
        assertTrue(searchMoreEntries(listOf(entry), "unknown", resolve).isEmpty())
    }

    @Test
    fun hiddenManagersNeverEnterSearchIndex() {
        val entries = buildMoreCategories(REAREyeConfig).flatMap { category ->
            category.category.children.map { node -> node.key }
        }
        assertTrue(entries.none { it in MoreHiddenConfigKeys })
        assertFalse(entries.isEmpty())
    }
}
