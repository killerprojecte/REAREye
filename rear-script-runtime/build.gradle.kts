plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "hk.uwu.reareye.scriptruntime"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.luajava.luajit)
    runtimeOnly("party.iroiro.luajava:android:${libs.versions.luajava.get()}:luajit@aar")
    testImplementation(libs.junit)
    testRuntimeOnly("party.iroiro.luajava:luajit-platform:4.1.0:natives-desktop")
}
