package hk.uwu.reareye.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionGateTest {
    private val requirements = RequiredAppVersions.requirements
    private val rear = requirements.single { it.packageName == "com.xiaomi.subscreencenter" }
    private val companions = requirements.filter { it != rear }

    private fun installed(requirement: RequiredAppVersion, versionCode: Long = requirement.requiredVersionCode) =
        InstalledAppVersion(requirement.packageName, requirement.packageName, versionCode, "test")

    private fun evaluate(vararg apps: InstalledAppVersion): List<AppVersionViolation> {
        val byPackage = apps.associateBy { it.packageName }
        return AppVersionGate.evaluate(requirements, byPackage::get)
    }

    @Test
    fun missingTargetsDoNotBlockStartup() {
        assertTrue(evaluate().isEmpty())
        assertTrue(evaluate(installed(rear)).isEmpty())
    }

    @Test
    fun outdatedCompanionsWithoutRearScreenDoNotBlockStartup() {
        val queried = mutableListOf<String>()
        val apps = companions.associate { it.packageName to installed(it, 1) }
        val violations = AppVersionGate.evaluate(requirements) { packageName ->
            queried += packageName
            apps[packageName]
        }
        assertTrue(violations.isEmpty())
        // Absence is cached and companion versions are not queried at all.
        assertEquals(listOf(rear.packageName), queried)
    }

    @Test
    fun eachInstalledCompanionIsCheckedWhenRearScreenExists() {
        companions.forEach { companion ->
            val violations = evaluate(installed(rear), installed(companion, companion.requiredVersionCode - 1))
            assertEquals(listOf(companion.packageName), violations.map { it.packageName })
        }
    }

    @Test
    fun minimumAndNewerVersionsAreAccepted() {
        assertTrue(evaluate(*requirements.map { installed(it) }.toTypedArray()).isEmpty())
        assertTrue(evaluate(*requirements.map { installed(it, it.requiredVersionCode + 1) }.toTypedArray()).isEmpty())
    }

    @Test
    fun outdatedRearScreenIsStillBlocked() {
        assertEquals(listOf(rear.packageName), evaluate(installed(rear, 1)).map { it.packageName })
    }

    @Test
    fun outdatedPrerequisiteStillEnablesCompanionChecks() {
        val violations = evaluate(*requirements.map { installed(it, 1) }.toTypedArray())
        assertEquals(requirements.map { it.packageName }, violations.map { it.packageName })
    }

    @Test
    fun allPresenceAndVersionCombinationsRespectPrerequisites() {
        val theme = requirements.single { it.packageName == "com.android.thememanager" }
        val assistant = requirements.single { it.packageName == "com.miui.personalassistant" }
        fun choices(requirement: RequiredAppVersion) = listOf(
            null,
            installed(requirement, requirement.requiredVersionCode - 1),
            installed(requirement),
            installed(requirement, requirement.requiredVersionCode + 1),
        )
        for (rearApp in choices(rear)) {
            for (themeApp in choices(theme)) {
                for (assistantApp in choices(assistant)) {
                    val apps = listOfNotNull(rearApp, themeApp, assistantApp)
                    val expected = if (rearApp == null) emptyList() else apps.filter { app ->
                        app.versionCode < requirements.single { it.packageName == app.packageName }.requiredVersionCode
                    }.map { it.packageName }
                    assertEquals(expected, evaluate(*apps.toTypedArray()).map { it.packageName })
                }
            }
        }
    }

    @Test
    fun unrelatedTargetsAreCheckedWithoutRearScreen() {
        val requirement = RequiredAppVersion("other.app", 10)
        val violations = AppVersionGate.evaluate(listOf(requirement)) { installed(requirement, 10) }
        assertEquals(listOf("other.app"), violations.map { it.packageName })
        assertEquals("> 10", violations.single().requiredVersionText)
        assertEquals("10 (test)", violations.single().currentVersionText)
    }
}
