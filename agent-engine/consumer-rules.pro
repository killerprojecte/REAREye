# Rules shipped with :agent-engine, applied to any consumer's release build.
#
# The engine serialises conversation history, provider settings and the audit log with
# kotlinx-serialization. R8 renames the `Companion` and the synthetic `$$serializer` fields those
# classes rely on, which turns every read into a SerializationException at runtime while debug
# builds keep working. These keeps are required, not optional.

# Keep the serializers attached to @Serializable classes.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the engine's own serialisable model types reachable by name.
-keep class hk.uwu.reareye.agent.model.** { *; }

# kotlinx.serialization runtime internals reached reflectively.
-dontwarn kotlinx.serialization.**

# Ktor 3.3.3's IntellijIdeaDebugDetectorJvm probes JMX inside try/catch(Throwable)
# and returns false when unavailable. Android has no JMX; these two references are
# optional debugger detection, not networking requirements. Do not suppress all Ktor warnings.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
