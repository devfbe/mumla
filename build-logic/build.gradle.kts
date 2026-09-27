plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: the plugins themselves are put on the build classpath once, by the root build script.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "mumla.android.application"
            implementationClass = "MumlaAndroidApplicationPlugin"
        }
        register("androidLibrary") {
            id = "mumla.android.library"
            implementationClass = "MumlaAndroidLibraryPlugin"
        }
        register("jvmLibrary") {
            id = "mumla.jvm.library"
            implementationClass = "MumlaJvmLibraryPlugin"
        }
    }
}
