plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.serialization)
}

android {
    namespace = "hk.uwu.reareye.agent"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    // Native graph + only the two protocol clients; do not pull koog-agents/all providers.
    api(libs.koog.agents.core)
    api(libs.koog.prompt.executor.model)
    implementation(libs.koog.prompt.executor.openai)
    implementation(libs.koog.prompt.executor.anthropic)
    implementation(libs.koog.http.client.ktor)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlin.logging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
