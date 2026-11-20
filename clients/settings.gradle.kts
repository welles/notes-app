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
    ":androidApp",
    ":desktopApp",
)
