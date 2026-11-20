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
        androidMain.dependencies {
            implementation(libs.androidx.glance)
            implementation(libs.androidx.activity.compose)
            implementation(libs.koin.android)
        }
    }
    // Widgets need Android resources (appwidget-provider XML).
    android { androidResources { enable = true } }
}
