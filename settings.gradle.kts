pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "hk.uwu.roxyhook") {
                useModule("com.github.NekoStash.RoxyHook:roxy-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        maven("https://jitpack.io")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        maven("https://jitpack.io")
        maven("https://repo.fastmcmirror.org/content/repositories/releases/")
        google()
        mavenCentral()
    }
}

plugins {
    id("com.highcapable.gropify") version "1.0.1"
}

val gitHash: String by lazy { runGitCommand("rev-parse", "--short", "HEAD") ?: "unknown" }
val gitBranch: String by lazy {
    val url = runGitCommand("remote", "get-url", "origin")
        ?: "https://github.com/killerprojecte/REAREye.git"
    val branch = runGitCommand("branch", "--show-current") ?: "master"
    """github\.com[:/](.+?)(\.git)?$""".toRegex().find(url)?.groupValues?.get(1)
        .orEmpty() + "/" + branch
}
val gitCommitCount: Int by lazy { runGitCommand("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0 }
val gitVersionCode: Int by lazy { 5 + gitCommitCount }
fun runGitCommand(vararg args: String): String? = runCatching {
    ProcessBuilder(listOf("git") + args)
        .redirectErrorStream(true)
        .start()
        .let { process ->
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotBlank()) output else null
        }
}.getOrNull()

val versionCode = gitVersionCode
val branch = gitBranch
val hash = gitHash
val buildSuffix = providers.gradleProperty("buildSuffix").orNull ?: "dev"
val isPublicBeta = providers.gradleProperty("isPublicBeta")
    .map { it.toBoolean() }
    .orNull
    ?: gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("PublicBeta", ignoreCase = true)
    }

gradle.extra["versionSuffix"] = "-$hash-r$versionCode-$buildSuffix"
gradle.extra["isPublicBeta"] = isPublicBeta

gropify {
    rootProject {
        common {
            isEnabled = false
        }
    }

    projects(":app") {
        android {
            isEnabled = true

            permanentKeyValues(
                "git.hash" to hash,
                "git.branch" to branch,
                "build.number" to versionCode,
                "build.channel" to buildSuffix,
                "isPublicBeta" to isPublicBeta
            )
        }
    }
}

rootProject.name = "REAREye"

include(":app")
include(":rear-widget-api")
include(":rear-script-runtime")

