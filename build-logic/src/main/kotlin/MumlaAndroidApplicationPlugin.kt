import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** `com.android.application` plus the configuration shared with the library. */
class MumlaAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            val android = extensions.getByType<ApplicationExtension>()
            configureAndroidCommon(android)
            android.defaultConfig.targetSdk = 36
        }
    }
}
