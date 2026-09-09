package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

/** Verified broadcast sender used to mint one private broker Binder capability. */
internal data class AuthorizedAppEmbedCaller(
    val uid: Int,
    val packageName: String,
)

/**
 * Caller checks shared by bootstrap and every Binder entry point.
 *
 * A shared UID is allowed when Android reports the expected package and the broadcast identity names
 * that exact package. The returned service Binder is private to that verified bootstrap request; the
 * numeric session id is therefore an index inside a capability, never the capability itself.
 */
internal class AppEmbedCallerPolicy(
    private val expectedPackage: String,
    private val expectedUid: () -> Int,
    private val packagesForUid: (Int) -> Array<String>?,
) {
    init {
        require(expectedPackage.isNotBlank()) { "expectedPackage must not be blank" }
    }

    fun authorizeBootstrap(sentUid: Int, sentPackage: String?): AuthorizedAppEmbedCaller {
        if (sentUid < 0) throw SecurityException("Broadcast sender did not share its uid")
        if (sentPackage != expectedPackage) {
            throw SecurityException(
                "Unexpected app embed bootstrap package: expected=$expectedPackage actual=$sentPackage"
            )
        }
        val resolvedUid = expectedUid()
        if (resolvedUid != sentUid) {
            throw SecurityException(
                "Unexpected app embed bootstrap uid: expected=$resolvedUid actual=$sentUid"
            )
        }
        val uidPackages = packagesForUid(sentUid)?.toSet().orEmpty()
        if (expectedPackage !in uidPackages) {
            throw SecurityException(
                "Expected package $expectedPackage is absent from uid=$sentUid packages=$uidPackages"
            )
        }
        return AuthorizedAppEmbedCaller(sentUid, expectedPackage)
    }

    fun enforceBinderCaller(caller: AuthorizedAppEmbedCaller, callingUid: Int) {
        if (callingUid != caller.uid) {
            throw SecurityException(
                "App embed capability belongs to uid=${caller.uid}, caller=$callingUid"
            )
        }
    }
}
