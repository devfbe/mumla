import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** `com.android.library` plus the configuration shared with the app. */
class MumlaAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            val android = extensions.getByType<LibraryExtension>()
            configureAndroidCommon(android)
            configureDetekt()
            android.testOptions.targetSdk = 36
            android.lint.targetSdk = 36
        }
    }
}
