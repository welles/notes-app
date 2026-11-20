plugins {
    id("notes.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.data)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        findByName("androidMain")?.dependencies { implementation(libs.ktor.client.okhttp) }
        getByName("desktopMain").dependencies { implementation(libs.ktor.client.okhttp) }
        commonTest.dependencies { implementation(libs.ktor.client.mock) }
    }
}
