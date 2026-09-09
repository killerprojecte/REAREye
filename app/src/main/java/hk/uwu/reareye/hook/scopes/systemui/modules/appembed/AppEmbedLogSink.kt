package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

/** Severity selected by AppEmbed business code before a diagnostic reaches the hook logger. */
internal enum class AppEmbedLogLevel {
    /** High-frequency state changes and expected late Binder/task callbacks. */
    DEBUG,

    /** Successful lifecycle milestones useful when diagnosing module startup. */
    INFO,

    /** Rejected external input or a recoverable cleanup/callback anomaly. */
    WARN,

    /** A failed session, hook operation, or resource invariant requiring investigation. */
    ERROR,
}

/** Typed boundary used by the SystemUI broker without coupling it to a concrete logger. */
internal fun interface AppEmbedLogSink {
    /** Writes one already-classified diagnostic while preserving its optional failure cause. */
    fun write(level: AppEmbedLogLevel, message: String, error: Throwable?)
}
