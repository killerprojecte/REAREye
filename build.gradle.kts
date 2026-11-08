import hk.uwu.build.RelocateMochiArtifact
import org.gradle.api.attributes.Attribute
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    id("com.mikepenz.aboutlibraries.plugin") version "14.1.0" apply false
}

// Only Mochi's Maven core requires the MaterialKolor 4 namespace compatibility transform.
// Apply the attribute to consumers too, so app/runtime never reselects the original AAR.
val mochiCompatible = Attribute.of("hk.uwu.mochi.materialkolor4-isolated", Boolean::class.javaObjectType)
subprojects {
    configurations.configureEach {
        if (name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath")) {
            attributes.attribute(mochiCompatible, true)
        }
    }
    dependencies {
        artifactTypes.maybeCreate("aar").attributes.attribute(mochiCompatible, false)
        registerTransform(RelocateMochiArtifact::class) {
            from.attribute(mochiCompatible, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
            to.attribute(mochiCompatible, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
        }
    }
}
