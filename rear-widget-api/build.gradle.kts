import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
    signing
}

val apiVersion = "1.0.1"

android {
    namespace = "hk.uwu.reareye.widgetapi"
    compileSdk {
        version =
            release(gropify.project.android.compileSdk) {
                minorApiLevel = gropify.project.android.compileSdkMinor
            }
    }

    defaultConfig {
        minSdk = gropify.project.android.minSdk
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "hk.uwu.reareye"
                artifactId = "rear-widget-api"
                version = apiVersion
                from(components["release"])

                pom {
                    name.set("rear-widget-api")
                    description.set("REAREye rear widget API")
                    inceptionYear.set("2026")
                    url.set("https://github.com/killerprojecte/REAREye")
                    licenses {
                        license {
                            name.set("GNU Lesser General Public License v3.0")
                            url.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                            distribution.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                        }
                    }
                    developers {
                        developer {
                            id.set("killerprojecte")
                            name.set("killerprojecte")
                            url.set("https://github.com/killerprojecte")
                        }
                    }
                    scm {
                        url.set("https://github.com/killerprojecte/REAREye")
                        connection.set("scm:git:git://github.com/killerprojecte/REAREye.git")
                        developerConnection.set("scm:git:ssh://git@github.com/killerprojecte/REAREye.git")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "fastmcmirror"
                url = uri("https://repo.fastmcmirror.org/content/repositories/releases/")
                credentials {
                    username = providers.gradleProperty("fastmcmirrorUsername")
                        .orElse(providers.environmentVariable("FASTMCMIRROR_USERNAME"))
                        .orNull
                    password = providers.gradleProperty("fastmcmirrorPassword")
                        .orElse(providers.environmentVariable("FASTMCMIRROR_PASSWORD"))
                        .orNull
                }
            }
        }
    }

    signing {
        val needSign = gradle.startParameter.taskNames.any {
            it.contains("Fastmcmirror", ignoreCase = true) ||
                    it.contains("sign", ignoreCase = true)
        }
        isRequired = needSign
        useGpgCmd()
        sign(publishing.publications)
    }
}
