plugins {
    id("notes.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.data)
            implementation(projects.core.ui)
            implementation(libs.reorderable)
        }
        findByName("androidMain")?.dependencies {
            implementation(libs.androidx.glance)
            implementation(libs.androidx.activity.compose)
            implementation(libs.koin.android)
        }
    }
    // Widgets need Android resources (appwidget-provider XML).
    if (providers.gradleProperty("notes.desktopOnly").orNull != "true") {
        extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("android") { androidResources { enable = true } }
    }
}
