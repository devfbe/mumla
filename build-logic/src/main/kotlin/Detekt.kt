import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Static analysis of the module's Kotlin sources with detekt's default rules. Findings recorded
 * in the module's detekt-baseline.xml are tolerated; `./gradlew detektBaseline` rewrites it.
 */
internal fun Project.configureDetekt() {
    pluginManager.apply("io.gitlab.arturbosch.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        // Every source set (main, test, the app's flavors), not only detekt's default main/test.
        source.setFrom(
            file("src").listFiles { dir -> dir.isDirectory }.orEmpty()
                .flatMap { listOf(it.resolve("java"), it.resolve("kotlin")) }
                .filter { it.isDirectory },
        )
        baseline = file("detekt-baseline.xml")
    }
}
