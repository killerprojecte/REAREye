package hk.uwu.reareye.internal.appembed

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import hk.uwu.reareye.hook.scopes.systemui.modules.appembed.AppEmbedTaskDensityCalculator
import kotlin.math.max
import kotlin.math.min

/** Initial task geometry transported by the broker before any target Activity is started. */
internal data class AppEmbedInitialLayout(val bounds: Rect, val densityDpi: Int) {
    init {
        require(!bounds.isEmpty) { "AppEmbed initial bounds are empty" }
        require(densityDpi >= 0) { "AppEmbed initial density is negative" }
    }

    /** Adds broker-owned parameters after the user's launch Intent has been validated. */
    fun writeTo(intent: Intent) {
        intent.putExtra(EXTRA_BOUNDS, Rect(bounds))
        intent.putExtra(EXTRA_DENSITY, densityDpi)
        intent.putExtra(EXTRA_CUSTOM_BOUNDS_OWNERSHIP, true)
    }

    /** A zero density inherits the task display; bounds resolution then derives the logical size. */
    fun densityOverride(): Configuration = Configuration().apply {
        if (this@AppEmbedInitialLayout.densityDpi > 0) {
            val density = AppEmbedTaskDensityCalculator.calculate(
                bounds,
                this@AppEmbedInitialLayout.densityDpi
            )
            densityDpi = density.densityDpi
            screenWidthDp = density.screenWidthDp
            screenHeightDp = density.screenHeightDp
            smallestScreenWidthDp = density.smallestScreenWidthDp
            orientation = if (density.screenWidthDp <= density.screenHeightDp) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }
            val shortDp = min(density.screenWidthDp, density.screenHeightDp)
            val size = when {
                shortDp < 320 -> Configuration.SCREENLAYOUT_SIZE_SMALL
                shortDp < 480 -> Configuration.SCREENLAYOUT_SIZE_NORMAL
                shortDp < 600 -> Configuration.SCREENLAYOUT_SIZE_LARGE
                else -> Configuration.SCREENLAYOUT_SIZE_XLARGE
            }
            screenLayout = (screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK.inv()) or size
        }
    }

    companion object {
        private const val EXTRA_BOUNDS = "hk.uwu.reareye.appembed.INITIAL_BOUNDS"
        private const val EXTRA_DENSITY = "hk.uwu.reareye.appembed.INITIAL_DENSITY"
        const val EXTRA_CUSTOM_BOUNDS_OWNERSHIP = "hk.uwu.reareye.appembed.CUSTOM_BOUNDS_OWNERSHIP"

        /** Must only be read after authenticating the ActivityRecord's real launch provenance. */
        fun readFrom(intent: Intent): AppEmbedInitialLayout? {
            val bounds = intent.getParcelableExtra(EXTRA_BOUNDS, Rect::class.java) ?: return null
            require(intent.hasExtra(EXTRA_DENSITY)) { "AppEmbed initial density is missing" }
            return AppEmbedInitialLayout(Rect(bounds), intent.getIntExtra(EXTRA_DENSITY, -1))
        }
    }
}
