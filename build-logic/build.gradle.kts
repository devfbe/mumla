plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: AGP itself is put on the build classpath once, by the root build script.
    compileOnly(libs.android.gradlePlugin)
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
    }
}
