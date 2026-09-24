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

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension

// AGP 9 compiles Kotlin itself; never apply org.jetbrains.kotlin.android.
// The Android plugins are loaded here, once, so that build-logic and protobuf share them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.protobuf) apply false
}

// Both modules must read the toolchain versions from gradle.properties: a drifted ndkVersion does
// not fail the build, it only ships unstripped native libraries.
gradle.projectsEvaluated {
    val modules = listOf(":app", ":libraries:humla")
        .associateWith { project(it).extensions.getByType<CommonExtension>() }
    for ((name, resolve) in listOf<Pair<String, (CommonExtension) -> String?>>(
        "ndkVersion" to { it.ndkVersion },
        "buildToolsVersion" to { it.buildToolsVersion },
    )) {
        val expected = providers.gradleProperty(name).get()
        modules.forEach { (path, android) ->
            val resolved = resolve(android)
            check(resolved == expected) {
                "Toolchain version drift: $path resolves $name '$resolved' but gradle.properties says '$expected'."
            }
        }
    }

    val expectedCmake = providers.gradleProperty("cmakeVersion").get()
    val resolvedCmake = project(":libraries:humla").extensions.getByType<LibraryExtension>()
        .externalNativeBuild.cmake.version
    check(resolvedCmake == expectedCmake) {
        "Toolchain version drift: :libraries:humla resolves cmakeVersion '$resolvedCmake' but gradle.properties says '$expectedCmake'."
    }
}
