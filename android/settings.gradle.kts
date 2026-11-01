pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vtlo-3o3"
include(":engine", ":desktop")
// -PdesktopOnly builds just the desktop app (no Android SDK needed), e.g. on Windows CI.
if (!providers.gradleProperty("desktopOnly").isPresent) {
    include(":core", ":drums", ":sampler", ":app")
}
