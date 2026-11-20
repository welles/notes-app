import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.app)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.koin.core)
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.cmp.material3)
            implementation(libs.cmp.icons.extended)
            implementation(libs.cmp.lifecycle.runtime)
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.welles.notes.desktop.MainKt"
        jvmArgs += listOf("--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED", "-Dnotes.version=" + providers.gradleProperty("notes.versionName").get())
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "notes"
            packageVersion = providers.gradleProperty("notes.versionName").get().substringBefore('-')
        }
    }
}

// All runtime jars in one directory – used by the Nix package (flake.nix), which wraps them with the system JDK.
tasks.register<Sync>("nixDistribution") {
    from(tasks.named("jvmJar"))
    from(configurations.named("jvmRuntimeClasspath"))
    into(layout.buildDirectory.dir("nix/lib"))
    // Different artifacts can share a file name (androidx vs. org.jetbrains.androidx) – prefix with the Maven group.
    eachFile {
        val path = file.absolutePath
        val marker = "/files-2.1/"
        val i = path.indexOf(marker)
        if (i >= 0) name = path.substring(i + marker.length).substringBefore('/') + "__" + name
    }
}
