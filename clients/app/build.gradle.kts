plugins {
    id("notes.kmp.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.data)
            api(projects.core.ui)
            api(projects.feature.stickies)
            api(projects.core.sync)
            implementation(libs.sqlite.bundled)
        }
    }
}

room {
    // Exported and committed: basis for migration tests from every published schema.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
}
