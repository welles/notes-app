plugins {
    id("notes.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.data)
            implementation(libs.cmp.ui.backhandler)
        }
    }
}
