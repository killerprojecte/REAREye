package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.app.ActivityOptions
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppEmbedActivityLaunchOptionsTest {
    @Test
    fun creatorOptionsContainOnlyTheCreatorBackgroundStartGrant() {
        val options = AppEmbedActivityLaunchOptions.Builder()
            .setLaunchDisplayId(7)
            .build()

        val creatorOptions = options.pendingIntentCreationOptions
        assertEquals(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
            creatorOptions.getInt(PENDING_INTENT_CREATOR_BACKGROUND_START_MODE_KEY),
        )
        assertFalse(
            creatorOptions.containsKey(PENDING_INTENT_BACKGROUND_START_MODE_KEY),
        )
        assertFalse(creatorOptions.containsKey(LAUNCH_DISPLAY_ID_KEY))
    }

    @Test
    fun taskViewSenderOptionsContainOnlyTheSenderGrantAndDisplay() {
        val options = AppEmbedActivityLaunchOptions.Builder()
            .setLaunchDisplayId(7)
            .build()

        val senderOptions = options.taskViewSenderOptions
        assertEquals(7, senderOptions.launchDisplayId)
        assertEquals(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
            senderOptions.pendingIntentBackgroundActivityStartMode,
        )
        assertEquals(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED,
            senderOptions.pendingIntentCreatorBackgroundActivityStartMode,
        )
        val senderBundle = senderOptions.toBundle()
        assertEquals(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
            senderBundle.getInt(PENDING_INTENT_BACKGROUND_START_MODE_KEY),
        )
        assertEquals(7, senderBundle.getInt(LAUNCH_DISPLAY_ID_KEY))
        assertFalse(senderBundle.containsKey(PENDING_INTENT_CREATOR_BACKGROUND_START_MODE_KEY))
    }

    @Test
    fun builderRejectsMissingOrInvalidDisplay() {
        assertThrows(IllegalStateException::class.java) {
            AppEmbedActivityLaunchOptions.Builder().build()
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppEmbedActivityLaunchOptions.Builder().setLaunchDisplayId(Display.INVALID_DISPLAY)
        }
    }

    private companion object {
        /** Android 17 ComponentOptions key used by the public sender-mode API. */
        const val PENDING_INTENT_BACKGROUND_START_MODE_KEY =
            "android.pendingIntent.backgroundActivityAllowed"

        /** Android 17 ActivityOptions key used by its public creator-mode setter and toBundle. */
        const val PENDING_INTENT_CREATOR_BACKGROUND_START_MODE_KEY =
            "android.activity.pendingIntentCreatorBackgroundActivityStartMode"

        /** Android 17 ActivityOptions key written when a launch display is explicitly selected. */
        const val LAUNCH_DISPLAY_ID_KEY = "android.activity.launchDisplayId"
    }
}
