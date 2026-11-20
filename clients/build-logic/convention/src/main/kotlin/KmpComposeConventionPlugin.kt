import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Shared module with Compose Multiplatform UI (Material 3). */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("notes.kmp.library")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun lib(name: String) = libs.findLibrary(name).get()

        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("commonMain").dependencies {
                implementation(lib("cmp-runtime"))
                implementation(lib("cmp-foundation"))
                implementation(lib("cmp-ui"))
                implementation(lib("cmp-material3"))
                implementation(lib("cmp-icons-extended"))
                implementation(lib("cmp-lifecycle-viewmodel"))
                implementation(lib("cmp-lifecycle-runtime"))
                implementation(lib("koin-compose"))
                implementation(lib("koin-compose-viewmodel"))
            }
        }
    }
}
