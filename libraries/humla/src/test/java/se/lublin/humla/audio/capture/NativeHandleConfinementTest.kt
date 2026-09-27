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

package se.lublin.humla.audio.capture

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.reflect.Modifier

/**
 * A native handle may only go back to the bridge that issued it: `HandleTable::get()` cannot tell
 * whose handle it is given, so a leaked one could make a bridge process another stage's instance.
 * So the handle lives in one private field of [SingleHandleStage] and no member mentions a long
 * (returned, taken, or in an array) except the three callbacks, which run with the lock held. The
 * whole class chain is walked because `declaredFields`/`declaredMethods` skip inherited members.
 * A subclass can still keep the value it passed to the constructor; see the class KDoc.
 */
@RunWith(Parameterized::class)
class NativeHandleConfinementTest(private val stage: Class<*>) {
    @Test
    fun `the native handle never escapes the stage`() {
        val hierarchy = generateSequence(stage) { it.superclass }.takeWhile { it != Any::class.java }.toList()
        assertWithMessage("the walk must reach the base class")
            .that(hierarchy).contains(SingleHandleStage::class.java)

        val longFields = hierarchy.flatMap { it.declaredFields.asList() }
            .filter { !it.isSynthetic && mentionsLong(it.type) }
        assertWithMessage("the handle must live in exactly one field")
            .that(longFields.map { "${it.declaringClass.simpleName}.${it.name}" }).hasSize(1)
        assertWithMessage("the one handle field must be the base class's private one")
            .that(longFields.single().declaringClass).isEqualTo(SingleHandleStage::class.java)
        assertWithMessage("the one handle field must be private")
            .that(Modifier.isPrivate(longFields.single().modifiers)).isTrue()

        val handleBearing = hierarchy.flatMap { it.declaredMethods.asList() }
            .filter { !it.isSynthetic && !it.isBridge && !Modifier.isPrivate(it.modifiers) }
            .filter { m -> mentionsLong(m.returnType) || m.parameterTypes.any { mentionsLong(it) } }
        assertWithMessage("only the three callbacks, which run with the lock held, may carry the handle")
            .that(handleBearing.map { it.name }.distinct())
            .containsExactly("onCaptureFrame", "onFarEndFrame", "onReleaseHandle")
    }

    /** `long`, `java.lang.Long`, or an array of either (an out-parameter is an escape too). */
    private fun mentionsLong(type: Class<*>): Boolean = when {
        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> true
        type.isArray -> mentionsLong(type.componentType!!)
        else -> false
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun stages(): List<Class<*>> = listOf(
            SpeexPreprocessor::class.java,
            RnnoisePreprocessor::class.java,
            WebRtcApmPreprocessor::class.java,
            SingleHandleStageTest.TestStage::class.java,
            SingleHandleStageTest.CaptureOnlyStage::class.java,
        )
    }
}
