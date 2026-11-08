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
    implementation(libs.mochi.compose)
    implementation("com.materialkolor:material-color-utilities:6.0.0")
    testImplementation(libs.junit)
}
