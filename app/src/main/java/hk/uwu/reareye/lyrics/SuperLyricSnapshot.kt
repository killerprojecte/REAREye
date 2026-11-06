package hk.uwu.reareye.lyrics

/** Ownership is independent of the optional publisher provided by SuperLyric 4.0. */
internal data class SuperLyricSession(
    val publisher: String?,
    val lyricId: String?,
) {
    /** Missing identifiers are unknown, not an instruction to stop a different known session. */
    fun acceptsStop(stop: SuperLyricSession): Boolean {
        val currentPublisher = publisher?.takeIf { it.isNotBlank() }
        val stoppedPublisher = stop.publisher?.takeIf { it.isNotBlank() }
        if (currentPublisher != null && stoppedPublisher != null && currentPublisher != stoppedPublisher) {
            return false
        }
        val currentId = lyricId?.takeIf { it.isNotBlank() }
        val stoppedId = stop.lyricId?.takeIf { it.isNotBlank() }
        return currentId == null || stoppedId == null || currentId == stoppedId
    }
}

internal data class SuperLyricSnapshot(
    val session: SuperLyricSession,
    val text: String?,
    val translation: String?,
) {
    /** Keep the existing original/translation preference, including embedded second-line fallback. */
    fun displayText(showTranslation: Boolean): String {
        val original = text.orEmpty().replace("\r\n", "\n").replace('\r', '\n')
        if (!showTranslation) return original.substringBefore('\n').takeUnless { it.isBlank() }.orEmpty()
        return translation?.takeIf { it.isNotBlank() }
            ?: original.split('\n').getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: original.takeUnless { it.isBlank() }.orEmpty()
    }
}
