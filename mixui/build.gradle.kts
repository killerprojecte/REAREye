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
    defaultConfig {
        minSdk = 36
        consumerProguardFiles("vendor/mochi-compose/consumer-rules.pro")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.withType<KotlinJvmCompile>().configureEach { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

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
    // Recompile the pinned core source against shared MaterialKolor 6 and Backdrop 2.
    // Exclude the old 0.2 binary; it contains missing class/method descriptors.
    implementation("io.github.feice11.mochi-compose:mochi-ios:${libs.versions.mochi.get()}") {
        exclude(group = "io.github.feice11.mochi-compose", module = "mochi-compose")
    }
    implementation(libs.capsule)
    implementation(libs.backdrop)
    implementation(libs.androidx.activity.compose)
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    implementation("com.materialkolor:material-color-utilities:6.0.0")
    testImplementation(libs.junit)
}
