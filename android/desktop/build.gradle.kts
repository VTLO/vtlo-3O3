// VTLO 3O3 for Windows (and any desktop with Java): the same three instruments
// and engines as the Android app, with a Swing UI and Java Sound audio.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.vtlo.desktop.MainKt")
    applicationName = "vtlo-3o3"
}

sourceSets {
    // The drum kits ship once, in the Android module's assets (kits/606/Bass.wav, …).
    main { resources.srcDir("../drums/src/main/assets") }
}

dependencies {
    implementation(project(":engine"))
    testImplementation("junit:junit:4.13.2")
}

tasks.jar {
    manifest { attributes("Main-Class" to "com.vtlo.desktop.MainKt") }
}
