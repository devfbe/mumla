import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

private const val ANDROID_JAVA_API = 11

/**
 * A plain Kotlin/JVM library that the Android modules link into the app: no android.* on its
 * compile classpath, so the compiler keeps platform code out.
 */
class MumlaJvmLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            configureDetekt()
            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(21))
                sourceCompatibility = JavaVersion.toVersion(ANDROID_JAVA_API)
                targetCompatibility = JavaVersion.toVersion(ANDROID_JAVA_API)
            }
            tasks.withType<JavaCompile>().configureEach {
                options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-deprecation", "-Xlint:-dep-ann"))
            }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions.jvmTarget.set(JvmTarget.fromTarget(ANDROID_JAVA_API.toString()))
            }
            // minSdk 31 has the Java 11 library, not the toolchain's: compiled against JDK 21,
            // list.removeFirst() would bind to a List method Android only has from API 35 on.
            // Tests run on the JDK and may use all of it.
            tasks.named<JavaCompile>("compileJava") { options.release.set(ANDROID_JAVA_API) }
            tasks.named<KotlinJvmCompile>("compileKotlin") {
                compilerOptions.freeCompilerArgs.add("-Xjdk-release=$ANDROID_JAVA_API")
            }
            tasks.withType<Test>().configureEach {
                useJUnit()
                // Coroutine debug mode, on under -ea, renames threads while a coroutine runs;
                // devices run without it, and tests assert on thread names.
                systemProperty("kotlinx.coroutines.debug", "off")
            }
        }
    }
}
