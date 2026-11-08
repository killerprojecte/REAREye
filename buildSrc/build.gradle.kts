plugins { `java-library` }
repositories { mavenCentral() }
dependencies {
    implementation(gradleApi())
    implementation("org.ow2.asm:asm-commons:9.8")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
