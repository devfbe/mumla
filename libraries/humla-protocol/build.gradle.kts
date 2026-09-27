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
    id("mumla.jvm.library")
    `java-test-fixtures`
    alias(libs.plugins.protobuf)
}

kotlin {
    explicitApi()
}

sourceSets {
    main {
        // Mumble.proto lives next to src/main, not in the plugin's default src/main/proto.
        (this as ExtensionAware).extensions.configure<SourceDirectorySet>("proto") {
            srcDir("src")
            include("*.proto")
        }
    }
}

// Only the generated classes are needed; the plugin would also put the .proto files in the jar.
tasks.named<ProcessResources>("processResources") {
    exclude("*.proto")
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
            }
        }
    }
}

dependencies {
    api(libs.protobuf.javalite)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    // Persistent maps keep each model snapshot O(change) instead of O(server).
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.minidns.hla)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesApi(libs.junit)
    testFixturesImplementation(libs.bouncycastle.pkix)
    testFixturesImplementation(libs.truth)
}
