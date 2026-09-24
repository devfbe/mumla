/*
 * Copyright (C) 2026 The Mumla Authors
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

package se.lublin.humla.audio.native

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import org.junit.Test

/**
 * `JNI_OnLoad` registers the native methods by name and signature, and one mismatch fails
 * `System.loadLibrary` on the device. The host ctest pins the C++ side to
 * `src/main/cpp/jni_registrations.txt`; this pins the Kotlin `external fun`s to the same file.
 */
class JniRegistrationsTest {
    @Test
    fun `every external fun is registered by JNI_OnLoad with its signature, and nothing else is`() {
        val listed = File("src/main/cpp/jni_registrations.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

        assertThat(declaredExternalFunctions()).containsExactlyElementsIn(listed)
    }

    /** Every native method in this package, as `<class> <method> <JNI signature>`. */
    private fun declaredExternalFunctions(): List<String> {
        val anchor = HumlaNativeLibrary::class.java
        val packagePath = anchor.packageName.replace('.', '/')
        // The main classes, a jar or a directory depending on the build; the test classes live
        // elsewhere.
        val location = File(anchor.protectionDomain.codeSource.location.toURI())
        val classFiles = if (location.isDirectory) {
            location.resolve(packagePath).list()!!.toList()
        } else {
            JarFile(location).use { jar ->
                jar.entries().toList().map { it.name }
                    .filter { it.startsWith("$packagePath/") && it.indexOf('/', packagePath.length + 1) < 0 }
                    .map { it.substringAfterLast('/') }
            }
        }
        // Loaded without initialising, so no library is loaded.
        val classes = classFiles.filter { it.endsWith(".class") }.map {
            Class.forName("${anchor.packageName}.${it.removeSuffix(".class")}", false, anchor.classLoader)
        }
        assertThat(classes).contains(OpusDecoderNative::class.java)
        return classes.flatMap { cls ->
            cls.declaredMethods.filter { Modifier.isNative(it.modifiers) }.map { method ->
                val parameters = method.parameterTypes.joinToString("") { descriptor(it) }
                "${cls.name.replace('.', '/')} ${method.name} ($parameters)${descriptor(method.returnType)}"
            }
        }
    }

    private fun descriptor(type: Class<*>): String = when {
        type == Void.TYPE -> "V"
        type == java.lang.Boolean.TYPE -> "Z"
        type == java.lang.Byte.TYPE -> "B"
        type == java.lang.Short.TYPE -> "S"
        type == java.lang.Integer.TYPE -> "I"
        type == java.lang.Long.TYPE -> "J"
        type == java.lang.Float.TYPE -> "F"
        type == java.lang.Double.TYPE -> "D"
        type.isArray -> "[" + descriptor(type.componentType)
        else -> "L${type.name.replace('.', '/')};"
    }
}
