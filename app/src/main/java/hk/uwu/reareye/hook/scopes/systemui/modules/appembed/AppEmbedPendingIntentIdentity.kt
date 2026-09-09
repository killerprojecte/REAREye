package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import android.content.Intent

/** Builds a filter-unique launch Intent for each embedded session's PendingIntent. */
internal object AppEmbedPendingIntentIdentity {
    /**
     * Copies the validated activity Intent and adds the full positive session ID as its identifier.
     *
     * PendingIntent request codes are only 32 bits, so namespaced 64-bit session IDs can collide.
     * Intent identifiers participate in filter identity and prevent FLAG_CANCEL_CURRENT from
     * cancelling another capability's embedded launch.
     */
    fun launchIntent(validatedLaunchIntent: Intent, sessionId: Long): Intent {
        require(sessionId > 0L) { "AppEmbed PendingIntent session ID must be positive" }
        return Intent(validatedLaunchIntent).setIdentifier("$IDENTIFIER_PREFIX$sessionId")
    }

    /** Produces the request code used alongside the full-ID Intent identifier. */
    fun requestCode(sessionId: Long): Int {
        require(sessionId > 0L) { "AppEmbed PendingIntent session ID must be positive" }
        return (sessionId xor (sessionId ushr 32)).toInt()
    }

    private const val IDENTIFIER_PREFIX = "hk.uwu.reareye.appembed.session."
}
