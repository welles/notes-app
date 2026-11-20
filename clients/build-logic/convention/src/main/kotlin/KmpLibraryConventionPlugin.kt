import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Every shared client module: Kotlin Multiplatform with the two targets Android and desktop (JVM).
 * Namespace is derived from the project path: :feature:stickies → dev.welles.notes.feature.stickies
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun v(name: String) = libs.findVersion(name).get().requiredVersion

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)
            extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = "dev.welles.notes" + path.replace(':', '.').replace('-', '_')
                compileSdk = v("android-compileSdk").toInt()
                minSdk = v("android-minSdk").toInt()
                compilerOptions { jvmTarget.set(JvmTarget.fromTarget(v("jvm-target"))) }
                withHostTest { }
            }
            jvm("desktop") {
                compilerOptions { jvmTarget.set(JvmTarget.fromTarget(v("jvm-target"))) }
            }
            compilerOptions {
                freeCompilerArgs.addAll("-Xexpect-actual-classes", "-opt-in=kotlin.uuid.ExperimentalUuidApi", "-opt-in=kotlin.time.ExperimentalTime")
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
                implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            }
        }
    }
}
