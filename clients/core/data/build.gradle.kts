plugins {
    id("notes.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.room.runtime)
            api(libs.koin.core)
        }
    }
}
