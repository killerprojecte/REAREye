package hk.uwu.reareye.internal.appembed

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import hk.uwu.reareye.hook.scopes.systemui.modules.appembed.AppEmbedTaskDensityCalculator

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
        }
    }

    companion object {
        private const val EXTRA_BOUNDS = "hk.uwu.reareye.appembed.INITIAL_BOUNDS"
        private const val EXTRA_DENSITY = "hk.uwu.reareye.appembed.INITIAL_DENSITY"

        /** Must only be read after authenticating the ActivityRecord's real launch provenance. */
        fun readFrom(intent: Intent): AppEmbedInitialLayout? {
            val bounds = intent.getParcelableExtra(EXTRA_BOUNDS, Rect::class.java) ?: return null
            require(intent.hasExtra(EXTRA_DENSITY)) { "AppEmbed initial density is missing" }
            return AppEmbedInitialLayout(Rect(bounds), intent.getIntExtra(EXTRA_DENSITY, -1))
        }
    }
}
