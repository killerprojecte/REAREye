import hk.uwu.build.RelocateMaterialKolor
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "hk.uwu.mixui"
    buildToolsVersion = "37.0.0"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { minSdk = 36 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.withType<KotlinJvmCompile>().configureEach { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

// Resolve the original v4 artifact independently; normal dependency conflict resolution
// must not silently upgrade it to MIUIX's v6 before namespace isolation.
val mochiMaterialKolor4 = configurations.create("mochiMaterialKolor4") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val isolateMochiMaterialKolor = tasks.register<RelocateMaterialKolor>("isolateMochiMaterialKolor") {
    artifacts.from(mochiMaterialKolor4)
    outputJar.set(layout.buildDirectory.file("compat/materialkolor4-private.jar"))
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation("androidx.compose.animation:animation")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.glass)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.shader)
    implementation("io.github.feice11.mochi-compose:mochi-ios:${libs.versions.mochi.get()}") {
        exclude(group = "com.materialkolor")
    }
    add(mochiMaterialKolor4.name, "com.materialkolor:material-color-utilities-android:4.1.1@aar")
    implementation(files(isolateMochiMaterialKolor.flatMap { it.outputJar }))
    implementation("com.materialkolor:material-color-utilities:6.0.0")
    testImplementation(libs.junit)
}
