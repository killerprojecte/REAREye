package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

/** Sender identity exposed by BroadcastReceiver when the publisher shares its identity. */
internal data class AppEmbedReadySenderIdentity(
    /** UID attributed by the Android broadcast dispatcher. */
    val uid: Int,
    /** Package attributed by the Android broadcast dispatcher. */
    val packageName: String?,
)

/** Exact sender-identity policy for the exported broker-ready receiver. */
internal object AppEmbedReadySenderPolicy {
    /** Accepts only the installed SystemUI package speaking from that package's resolved UID. */
    fun isAuthorized(sentFromUid: Int, sentFromPackage: String?, systemUiUid: Int): Boolean =
        sentFromUid >= 0 && systemUiUid >= 0 &&
                sentFromPackage == AppEmbedContract.SYSTEM_UI_PACKAGE && sentFromUid == systemUiUid
}
