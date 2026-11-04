package hk.uwu.reareye.version

import android.content.Context
import android.content.pm.PackageManager
import hk.uwu.reareye.version.RequiredAppVersions.requirements

/**
 * One target app REAREye depends on, together with the numeric `versionCode` the installed copy
 * must satisfy.
 *
 * The check is intentionally expressed in `versionCode` (not `versionName`) because only the code
 * is guaranteed to be numeric and monotonic across MIUI releases.
 *
 * @param packageName installed package to inspect, e.g. `com.xiaomi.subscreencenter`.
 * @param requiredVersionCode the lowest acceptable `versionCode`, see [inclusive].
 * @param inclusive `true` when [requiredVersionCode] itself is acceptable, `false` when the
 * installed code must be strictly greater.
 * @param prerequisitePackageName package that must be installed for this requirement to apply.
 */
data class RequiredAppVersion(
    val packageName: String,
    val requiredVersionCode: Long,
    val inclusive: Boolean = false,
    val prerequisitePackageName: String? = null,
) {
    fun isSatisfiedBy(versionCode: Long): Boolean =
        if (inclusive) versionCode >= requiredVersionCode else versionCode > requiredVersionCode
}

/**
 * Registry of target apps REAREye depends on.
 *
 * Adding a new entry is the only change needed to gate another target app: the startup check and
 * the reminder dialog are driven entirely by [requirements].
 *
 * @see AppVersionGate
 */
object RequiredAppVersions {
    private const val SUBSCREEN_CENTER_PACKAGE = "com.xiaomi.subscreencenter"

    val requirements: List<RequiredAppVersion> = listOf(
        // The rear screen container. Older builds lack the hooks and template fields this module
        // writes, so running against them silently corrupts cards / wallpapers.
        RequiredAppVersion(
            packageName = SUBSCREEN_CENTER_PACKAGE,
            requiredVersionCode = 426_091_816L,
            inclusive = true
        ),
        RequiredAppVersion(
            packageName = "com.android.thememanager",
            requiredVersionCode = 11_531L,
            inclusive = true,
            prerequisitePackageName = SUBSCREEN_CENTER_PACKAGE
        ),
        RequiredAppVersion(
            packageName = "com.miui.personalassistant",
            requiredVersionCode = 2_541_820L,
            inclusive = true,
            prerequisitePackageName = SUBSCREEN_CENTER_PACKAGE
        ),
    )
}

/** Version of an installed target app, resolved with the current system locale. */
data class InstalledAppVersion(
    val packageName: String,
    /** Localized application label taken from the system package manager. */
    val label: String,
    val versionCode: Long,
    val versionName: String?,
)

/** A target app whose installed version does not satisfy [RequiredAppVersion]. */
data class AppVersionViolation(
    val label: String,
    val packageName: String,
    val currentVersionCode: Long,
    val currentVersionName: String?,
    val requiredVersionCode: Long,
    val inclusive: Boolean,
) {
    /** Minimum acceptable version, formatted for display next to the current one. */
    val requiredVersionText: String
        get() = buildString {
            if (!inclusive) append("> ")
            append(requiredVersionCode)
        }

    /** Current version, with `versionName` appended when the system exposes one. */
    val currentVersionText: String
        get() = buildString {
            append(currentVersionCode)
            currentVersionName?.takeIf { it.isNotBlank() }?.let {
                append(" (")
                append(it)
                append(")")
            }
        }
}

/**
 * Evaluates [RequiredAppVersions.requirements] against the packages installed right now.
 *
 * Uninstalled target apps are skipped: there is nothing to gate, and reporting a missing package
 * here would block the module on devices that simply do not ship that component.
 * Rear-screen companion app requirements also need their prerequisite package to be installed;
 * its version does not affect whether those requirements apply.
 */
object AppVersionGate {
    fun evaluate(
        context: Context,
        requirements: List<RequiredAppVersion> = RequiredAppVersions.requirements,
    ): List<AppVersionViolation> {
        val packageManager = context.packageManager
        return evaluate(requirements) { readInstalledAppVersion(packageManager, it) }
    }

    internal fun evaluate(
        requirements: List<RequiredAppVersion>,
        readInstalled: (String) -> InstalledAppVersion?,
    ): List<AppVersionViolation> {
        val installedApps = mutableMapOf<String, InstalledAppVersion?>()
        fun installedApp(packageName: String): InstalledAppVersion? {
            if (!installedApps.containsKey(packageName)) {
                installedApps[packageName] = readInstalled(packageName)
            }
            return installedApps[packageName]
        }

        return requirements.mapNotNull { requirement ->
            requirement.prerequisitePackageName?.let { prerequisite ->
                if (installedApp(prerequisite) == null) return@mapNotNull null
            }
            val installed = installedApp(requirement.packageName)
                ?: return@mapNotNull null
            if (requirement.isSatisfiedBy(installed.versionCode)) return@mapNotNull null
            AppVersionViolation(
                label = installed.label,
                packageName = installed.packageName,
                currentVersionCode = installed.versionCode,
                currentVersionName = installed.versionName,
                requiredVersionCode = requirement.requiredVersionCode,
                inclusive = requirement.inclusive,
            )
        }
    }

    private fun readInstalledAppVersion(
        packageManager: PackageManager,
        packageName: String,
    ): InstalledAppVersion? {
        val packageInfo = runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.getOrNull() ?: return null
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val label = runCatching { applicationInfo.loadLabel(packageManager).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != packageName }
            ?: packageName
        return InstalledAppVersion(
            packageName = packageName,
            label = label,
            versionCode = packageInfo.longVersionCode,
            versionName = packageInfo.versionName,
        )
    }
}
