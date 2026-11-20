plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.welles.notes.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.welles.notes"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("notes.versionCode").get().toInt()
        versionName = providers.gradleProperty("notes.versionName").get()
    }

    // Release signing from environment (CI secrets); never stored in the repository.
    val keystoreFile = providers.environmentVariable("NOTES_KEYSTORE_FILE").orNull
    signingConfigs {
        if (keystoreFile != null) create("release") {
            storeFile = file(keystoreFile)
            storePassword = providers.environmentVariable("NOTES_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("NOTES_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("NOTES_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.app)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.androidx.datastore)
    implementation(libs.tink.android)
    implementation(libs.code.scanner)
    implementation(libs.lifecycle.process)
}

// Release APK named for Obtainium's filter: notes-<version>.apk
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set("notes-${output.versionName.get()}.apk")
        }
    }
}
