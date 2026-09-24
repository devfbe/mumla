/*
 * Copyright (C) 2026 The Mumla authors
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

package se.lublin.mumla

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.Toolbar
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.testing.ThemedActivity

/** Every layout's images are described or hidden from accessibility, and their buttons are big enough. */
@RunWith(RobolectricTestRunner::class)
class LayoutAccessibilityTest {
    private val context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val minTarget =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, context.resources.displayMetrics)

    private fun inflateAll(): Map<String, View> = R.layout::class.java.fields.associate { field ->
        field.name to LayoutInflater.from(context).inflate(field.getInt(null), FrameLayout(context), false)
    }

    private fun View.tree(): Sequence<View> = sequence {
        yield(this@tree)
        if (this@tree is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).tree())
    }

    private fun View.name() = if (id == View.NO_ID) javaClass.simpleName else resources.getResourceEntryName(id)

    @Test
    fun everyImageIsDescribedOrHidden() {
        for ((layout, root) in inflateAll()) {
            // A toolbar's own buttons are described once it becomes the action bar.
            for (image in root.tree().filterIsInstance<ImageView>().filter { it.parent !is Toolbar }) {
                val described = image.contentDescription != null ||
                    image.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO
                assertWithMessage("${image.name()} in $layout").that(described).isTrue()
            }
        }
    }

    @Test
    fun iconButtonsAreAtLeast48dpWide() {
        val layouts = inflateAll()
        for ((layout, ids) in ICON_BUTTONS) {
            for (id in ids) {
                val button = layouts.getValue(layout).findViewById<View>(id)
                assertWithMessage("${button.name()} in $layout")
                    .that(button.layoutParams.width.toFloat()).isAtLeast(minTarget)
                val height = button.layoutParams.height
                assertWithMessage("${button.name()} height in $layout")
                    .that(height == ViewGroup.LayoutParams.MATCH_PARENT || height >= minTarget).isTrue()
            }
        }
    }

    private companion object {
        val ICON_BUTTONS = mapOf(
            "channel_row" to listOf(R.id.channel_row_expand, R.id.channel_row_join, R.id.channel_row_more),
            "channel_user_row" to listOf(R.id.user_row_more),
            "fragment_channel" to listOf(R.id.target_panel_cancel),
            "server_list_row" to listOf(R.id.server_row_more),
            "public_server_list_row" to listOf(R.id.server_row_more),
            "overlay" to listOf(R.id.overlay_talk, R.id.overlay_close, R.id.overlay_drag),
            "dialog_image_viewer" to listOf(R.id.image_viewer_share, R.id.image_viewer_close),
            "token_row" to listOf(R.id.tokenItemDelete),
            "fragment_tokens" to listOf(R.id.tokenAddButton),
        )
    }
}
