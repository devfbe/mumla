import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

plugins {
    id("mumla.android.library")
}

android {
    namespace = "se.lublin.humla"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = providers.gradleProperty("cmakeVersion").get()
        }
    }

    testFixtures {
        enable = true
    }

    // bcprov, bcpkix and bcutil each ship one; the (empty) instrumented test APK packages them.
    packaging.resources.merges += "META-INF/LICENSE.md"

    defaultConfig {
        testApplicationId = "se.lublin.humla.test"
        // Device tests run the real libhumla_native.so (src/androidTest).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Static libc++ is safe because libhumla_native.so is the only native library and
                // exports nothing but JNI_OnLoad (enforced by src/main/cpp/check_library.cmake).
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }
}

kotlin {
    // What the app does not use stays internal; what is public is so on purpose.
    explicitApi()
}

// humla-protocol is the platform-free half of this library, not a separate API: its internal
// declarations are visible here as if they were this module's own.
val protocol = project(":libraries:humla-protocol")
tasks.withType<KotlinCompile>().configureEach {
    friendPaths.from(
        protocol.layout.buildDirectory.dir("classes/kotlin/main"),
        protocol.layout.buildDirectory.dir("classes/kotlin/testFixtures"),
        protocol.layout.buildDirectory.dir("libs"),
    )
}

// Coroutine debug mode, on under -ea, renames threads while a coroutine runs; devices run without it.
tasks.withType<Test>().configureEach {
    systemProperty("kotlinx.coroutines.debug", "off")
}

dependencies {
    api(project(":libraries:humla-protocol"))

    // Flows are part of the public API.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
    // Persistent maps keep each model snapshot O(change) instead of O(server).
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.minidns.android23)

    testImplementation(libs.bundles.unit.test)
    androidTestImplementation(libs.bundles.android.test)
    testFixturesApi(testFixtures(project(":libraries:humla-protocol")))
    testFixturesImplementation(libs.robolectric)
}
