import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/** Settings shared by the app and the humla library. */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<JavaCompile>().configureEach {
        // TODO include deprecations at some point, but currently they are *many*
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-deprecation", "-Xlint:-dep-ann"))
    }

    android.apply {
        compileSdk = 37
        // Single-sourced from gradle.properties; the root build script checks nothing overrides them.
        buildToolsVersion = providers.gradleProperty("buildToolsVersion").get()
        ndkVersion = providers.gradleProperty("ndkVersion").get()

        defaultConfig.minSdk = 31

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        buildFeatures.buildConfig = true

        testOptions.unitTests.apply {
            isIncludeAndroidResources = true
            // Plain JVM tests may reach android.util.Log; without Robolectric it would throw.
            isReturnDefaultValues = true
            all { test ->
                // Robolectric reaches jdk.internal.access, which JDK 21 does not export.
                test.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
                // Robolectric keeps every SDK's framework resources it has loaded, and native themes
                // until their finalizers run; the app suite outgrew 1g.
                test.maxHeapSize = "2g"
            }
        }

        lint.apply {
            abortOnError = true
            // InvalidPackage: BouncyCastle references javax.naming / java.awt classes Android lacks
            // and never reaches. Translations are managed on Weblate.
            disable += setOf("InvalidPackage", "MissingTranslation")
            explainIssues = true
            ignoreWarnings = false
            quiet = false
        }
    }
}
