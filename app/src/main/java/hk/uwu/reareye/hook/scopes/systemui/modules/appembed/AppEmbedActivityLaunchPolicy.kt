package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

/**
 * Applies Android's activity target-security rules to the process that creates the AppEmbed
 * [android.app.PendingIntent].
 *
 * TaskView launches the pending intent under its creator identity. The subscreen client therefore
 * cannot be used for exported or activity-permission decisions; those checks must use SystemUI's
 * actual process UID and permissions.
 */
internal object AppEmbedActivityLaunchPolicy {
    /** Android's stable UID allocation range used to recover an app id for a specific user. */
    private const val PER_USER_RANGE = 100_000

    /** Root and system app ids receive the same unexported-component exemption in ActivityManager. */
    private val UNEXPORTED_COMPONENT_APP_IDS = setOf(0, 1_000)

    /**
     * Validates whether the pending-intent issuer may launch the resolved activity.
     *
     * START_ANY_ACTIVITY and Android's root/system UID exemption bypass both export and declared
     * component-permission restrictions. All other issuers must satisfy both restrictions.
     */
    fun requireAllowed(
        componentDescription: String,
        exported: Boolean,
        requiredPermission: String?,
        issuerUid: Int,
        issuerPackageName: String,
        issuerHasStartAnyActivity: Boolean,
        issuerHasRequiredPermission: Boolean,
    ) {
        require(issuerUid >= 0) { "PendingIntent issuer uid must be non-negative" }
        require(issuerPackageName.isNotBlank()) { "PendingIntent issuer package must not be blank" }
        require(componentDescription.isNotBlank()) { "Activity component must not be blank" }

        if (issuerHasStartAnyActivity || appId(issuerUid) in UNEXPORTED_COMPONENT_APP_IDS) return

        require(exported) {
            "AppEmbed activity $componentDescription is not exported and PendingIntent issuer " +
                    "$issuerPackageName lacks android.permission.START_ANY_ACTIVITY"
        }
        if (!requiredPermission.isNullOrBlank()) {
            require(issuerHasRequiredPermission) {
                "PendingIntent issuer $issuerPackageName lacks activity permission " +
                        requiredPermission
            }
        }
    }

    /** Mirrors [android.os.UserHandle.getAppId] without depending on the hidden SDK method. */
    private fun appId(uid: Int): Int = uid % PER_USER_RANGE
}
