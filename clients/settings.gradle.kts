rootProject.name = "notes-clients"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
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

include(
    ":core:model",
    ":core:data",
    ":core:sync",
    ":core:ui",
    ":feature:stickies",
    ":app",
    ":desktopApp",
)
// The Nix package builds the desktop app only (no Android SDK): -Pnotes.desktopOnly=true
if (providers.gradleProperty("notes.desktopOnly").orNull != "true") include(":androidApp")
