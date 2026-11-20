plugins {
    id("notes.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.data)
            api(projects.core.sync)
            implementation(libs.cmp.ui.backhandler)
        }
    }
}
