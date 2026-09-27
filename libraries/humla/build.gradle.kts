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
    alias(libs.plugins.protobuf)
}

android {
    namespace = "se.lublin.humla"

    sourceSets {
        named("main") {
            // Mumble.proto lives next to src/main, not in the plugin's default src/main/proto.
            (this as ExtensionAware).extensions.configure<SourceDirectorySet>("proto") {
                srcDir("src")
                include("*.proto")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = providers.gradleProperty("cmakeVersion").get()
        }
    }

    testFixtures {
        enable = true
    }

    defaultConfig {
        testApplicationId = "se.lublin.humla.test"
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

// The plugin would copy Mumble.proto into the AAR's Java resources and so into the APK; only the
// generated classes are needed.
tasks.configureEach {
    if (name.endsWith("ProtoResources")) enabled = false
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                maybeCreate("java").option("lite")
            }
        }
    }
}

// Coroutine debug mode, on under -ea, renames threads while a coroutine runs; devices run without it.
tasks.withType<Test>().configureEach {
    systemProperty("kotlinx.coroutines.debug", "off")
}

dependencies {
    api(libs.protobuf.javalite)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    // Flows are part of the public API.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
    // Persistent maps keep each model snapshot O(change) instead of O(server).
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.minidns.hla)
    implementation(libs.minidns.android23)

    testImplementation(libs.bundles.unit.test)
}
