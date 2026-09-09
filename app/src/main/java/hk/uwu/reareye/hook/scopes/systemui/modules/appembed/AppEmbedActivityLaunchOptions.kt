package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.app.ActivityOptions
import android.os.Bundle

/**
 * Separates the options used to create an AppEmbed [android.app.PendingIntent] from the options
 * supplied later by TaskView when it sends that PendingIntent.
 *
 * Android treats the creator and sender background-activity-start grants as different authority.
 * Keeping both option sets in one typed value prevents a creator grant from being sent in the
 * sender bundle, which Android rejects.
 */
internal class AppEmbedActivityLaunchOptions private constructor(
    /** Options stored when SystemUI creates the PendingIntent. */
    val pendingIntentCreationOptions: Bundle,
    /** Options TaskView supplies when it sends the PendingIntent on the target display. */
    val taskViewSenderOptions: ActivityOptions,
) {
    /** Builds a complete, display-specific pair of creator and sender option sets. */
    class Builder {
        /** Physical display on which TaskView must launch the embedded activity. */
        private var launchDisplayId: Int? = null

        /** Selects the display owned by the AppEmbed session. */
        fun setLaunchDisplayId(displayId: Int) = apply {
            require(displayId >= 0) { "launchDisplayId must identify a physical display" }
            launchDisplayId = displayId
        }

        /** Creates independent bundles with their respective background-start grants. */
        fun build(): AppEmbedActivityLaunchOptions {
            val displayId = checkNotNull(launchDisplayId) { "launchDisplayId is required" }
            val creatorOptions = ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
                )
            }
            val senderOptions = ActivityOptions.makeBasic().apply {
                setLaunchDisplayId(displayId)
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
                )
            }
            return AppEmbedActivityLaunchOptions(
                pendingIntentCreationOptions = creatorOptions.toBundle(),
                taskViewSenderOptions = senderOptions,
            )
        }
    }
}
