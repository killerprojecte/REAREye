import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    id("com.mikepenz.aboutlibraries.plugin")
    alias(libs.plugins.roxyhook)
}

val gitCommitCount: Int by lazy { runGitCommand("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0 }
val gitVersionCode: Int by lazy { 5 + gitCommitCount }
val baseVersionName = gropify.project.app.versionName.replace("\"", "")
val buildSuffix = project.findProperty("buildSuffix") as? String ?: "dev"
val finalVersionName = "$baseVersionName-$buildSuffix"
val isPublicBetaBuild = gradle.extra["isPublicBeta"] as? Boolean ?: false

roxy {
    autoDependencies.set(false)
    minApiVersion.set(102)
    targetApiVersion.set(102)
    staticScope.set(true)
    autoHotReload.set(true)
    scope.set(
        listOf(
            "system",
            "com.xiaomi.subscreencenter",
            "com.android.thememanager",
            "com.android.systemui",
            "com.miui.personalassistant",
            "com.miui.weather2",
            "com.miui.gallery"
        )
    )
    nativeEntries.set(
        listOf(
            "libreareye_weather_hook.so",
            "libreareye_gallery_hook.so",
        ),
    )
    entryPackage.set("hk.uwu.reareye.hook")
}

fun runGitCommand(vararg args: String): String? = runCatching {
    ProcessBuilder(listOf("git") + args)
        .redirectErrorStream(true)
        .start()
        .let { process ->
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotBlank()) output else null
        }
}.getOrNull()

android {
    buildToolsVersion = gropify.project.android.buildToolsVersion
    namespace = gropify.project.app.packageName
    compileSdk {
        version =
            release(gropify.project.android.compileSdk) {
                minorApiLevel = gropify.project.android.compileSdkMinor
            }
    }

    defaultConfig {
        applicationId = gropify.project.app.packageName
        minSdk = gropify.project.android.minSdk
        targetSdk = gropify.project.android.targetSdk
        versionName = gropify.project.app.versionName
        versionCode = gitVersionCode
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUILD_CHANNEL", "\"$buildSuffix\"")

        if (!isPublicBetaBuild) {
            @Suppress("UnstableApiUsage")
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                }
            }
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties().apply {
                val file = rootProject.file("local.properties")
                if (file.exists()) {
                    file.inputStream().use { load(it) }
                }
            }

            fun getProp(key: String): String? =
                localProperties.getProperty(key) ?: (project.findProperty(key) as? String)
                ?: System.getenv(key)

            storeFile = file(getProp("androidStoreFile") ?: "../release-key.jks")
            storePassword = getProp("KEYSTORE_PASSWORD")
            keyAlias = getProp("KEY_ALIAS")
            keyPassword = getProp("KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            versionNameSuffix = gradle.extra["versionSuffix"].toString()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
        aidl = true
    }
    if (isPublicBetaBuild) {
        packaging {
            jniLibs {
                excludes += "**/libreareye_*_hook.so"
            }
        }
    } else {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }
    lint { checkReleaseBuilds = false }
}

tasks.register("assemblePublicBetaRelease") {
    group = "build"
    description = "Assembles the public beta release without REAREye native hook libraries."
    dependsOn("assembleRelease")
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val exportApk = tasks.register<Sync>("export${variantName}Apk") {
            from(variant.artifacts.get(SingleArtifact.APK))
            include("*arm64-v8a*.apk")
            rename { "REAREye-v${finalVersionName}.apk" }
            into(layout.buildDirectory.dir("outputs/renamed-apk/${variant.name}"))
        }

        tasks.matching { it.name == "assemble${variantName}" }.configureEach {
            finalizedBy(exportApk)
        }
    }
}

// Gropify normally generates this source during project evaluation. Keep a
// deterministic fallback so clean command-line builds do not race that
// incremental deployer and lose AppProperties from the Kotlin source set.
val ensureGropifyAppProperties = tasks.register("ensureGropifyAppProperties") {
    val outputFile = layout.buildDirectory.file(
        "generated/gropify/src/main/hk/uwu/reareye/generated/AppProperties.kt",
    )
    val fallbackGitHash = runGitCommand("rev-parse", "--short", "HEAD") ?: "unknown"
    val fallbackGitBranch = runGitCommand("branch", "--show-current") ?: "master"
    val fallbackBuildNumber = gitVersionCode
    val fallbackChannel = buildSuffix
    val fallbackVersionName = baseVersionName
    val fallbackCodename = gropify.project.app.versionCodename.replace("\"", "")
    val fallbackIsPublicBeta = isPublicBetaBuild
    outputs.file(outputFile)
    outputs.upToDateWhen { false }
    doLast {
        val file = outputFile.get().asFile
        val isFallback = file.exists() && file.useLines { lines ->
            lines.firstOrNull()?.contains("Fallback generated by the app build") == true
        }
        if (!file.exists() || isFallback) {
            file.parentFile.mkdirs()
            file.writeText(
                """
                // Fallback generated by the app build when Gropify's incremental deployer has no output.
                package hk.uwu.reareye.generated

                public object AppProperties {
                    public const val GIT_HASH: String = "$fallbackGitHash"
                    public const val GIT_BRANCH: String = "$fallbackGitBranch"
                    public const val BUILD_NUMBER: Int = $fallbackBuildNumber
                    public const val BUILD_CHANNEL: String = "$fallbackChannel"
                    public const val IS_PUBLIC_BETA: Boolean = $fallbackIsPublicBeta
                    public const val PROJECT_APP_VERSION_NAME: String = "$fallbackVersionName"
                    public const val PROJECT_APP_VERSION_CODENAME: String = "$fallbackCodename"
                }
                """.trimIndent(),
            )
        }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    dependsOn(ensureGropifyAppProperties)
}
tasks.matching { it.name.startsWith("ksp") }.configureEach {
    dependsOn(ensureGropifyAppProperties)
}

aboutLibraries {
    offlineMode = false
    collect {
        configPath.file("config") // TODO(ASAP) libraries json ignored
        fetchRemoteLicense.set(false)
    }
    export {
        // Remove the "generated" timestamp to allow for reproducible builds
        prettyPrint.set(true)
    }
    license {
        // TODO https://github.com/mikepenz/AboutLibraries/issues/1190
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicensesMap.put("Other", listOf("com.github.bumptech.glide:glide"))
        allowedLicenses.addAll(
            "Apache-2.0",
            "LGPL",
            "GNU Lesser General Public License v2.1",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "CC0-1.0",
            "MIT",
            "EPL-1.0",
            "GPL-3.0-only",
            "GNU Lesser General Public License v3.0"
        )
    }
    library {
        // Enable the duplication mode, allows to merge, or link dependencies which relate
        duplicationMode.set(com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE)
        // Configure the duplication rule, to match "duplicates" with
        duplicationRule.set(com.mikepenz.aboutlibraries.plugin.DuplicateRule.GROUP)
    }
}
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

dependencies {
    implementation(project(":rear-widget-api"))
    implementation(libs.androidx.compose.foundation.layout)

    implementation(libs.roxy.libxposed)
    implementation(libs.roxy.annotations)
    compileOnly(libs.libxposed.api)
    ksp(libs.roxy.ksp)
    implementation(libs.libxposed.service)

    // Optional: KavaRef (https://github.com/HighCapable/KavaRef)
    implementation(platform(libs.kavaref.bom))
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.android)
    implementation(libs.kavaref.extension)

    implementation(libs.dexkit)
    implementation(libs.mmkv)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.libxposed.api)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.components)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.compose.icons.material.symbols.outlined.cmp)
    implementation(libs.compose.icons.material.symbols.rounded.cmp)
    implementation(libs.compose.icons.material.symbols.sharp.cmp)
    implementation(libs.compose.icons.material.symbols.outlined.filled.cmp)
    implementation(libs.compose.icons.material.symbols.rounded.filled.cmp)
    implementation(libs.compose.icons.material.symbols.sharp.filled.cmp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.shader)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.backdrop)
    implementation(libs.capsule)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.lyricon.provider)
    implementation(libs.lyricon.central)
    implementation(libs.lyricon.subscriber)
    implementation(libs.superlyric)
    implementation(libs.kotlinx.serialization.json)
}
