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
package se.lublin.mumla.drawable

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import se.lublin.mumla.R
import se.lublin.mumla.util.dp

/** [bitmap] cropped to a circle with a thin outline, e.g. a user's avatar. */
class CircleDrawable(private val resources: Resources, private val bitmap: Bitmap) : Drawable() {

    private val paint = Paint().apply {
        isDither = true
        isAntiAlias = true
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private val strokePaint = Paint().apply {
        isDither = true
        isAntiAlias = true
        color = ResourcesCompat.getColor(resources, R.color.ripple_talk_state_disabled, null)
        strokeWidth = resources.dp(STROKE_WIDTH_DP)
        style = Paint.Style.STROKE
    }

    private val constantState = object : ConstantState() {
        override fun newDrawable(): Drawable = CircleDrawable(resources, bitmap)
        override fun getChangingConfigurations(): Int = 0
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val matrix = Matrix()
        matrix.setRectToRect(
            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            RectF(bounds),
            Matrix.ScaleToFit.CENTER,
        )
        paint.shader.setLocalMatrix(matrix)
    }

    override fun draw(canvas: Canvas) {
        val imageRect = RectF(bounds)
        // A stroke is drawn half inside and half outside its path; keep it within the bounds.
        val strokeRect = RectF(bounds).apply { inset(strokePaint.strokeWidth / 2, strokePaint.strokeWidth / 2) }
        canvas.drawOval(imageRect, paint)
        canvas.drawOval(strokeRect, strokePaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.UNKNOWN

    override fun getConstantState(): ConstantState = constantState

    private companion object {
        const val STROKE_WIDTH_DP = 1f
    }
}
