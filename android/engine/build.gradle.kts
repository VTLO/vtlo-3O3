// Platform-independent sound engines and sequencers, shared by the Android app
// and the desktop (Windows) app. Plain Kotlin/JVM: no Android APIs allowed here.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The drum tests load the bundled kits from the Android module's assets.
    workingDir = projectDir
}
