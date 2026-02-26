/*
 * TrudidoScannerSDK
 * Copyright (C) 2026 Dominik
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.trudido.scanner

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of the camera preview.
 * Shows the auto-detected document outline (green quad + light fill).
 */
class DocumentOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = Color.argb(40, 0, 255, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var viewCorners: Array<PointF>? = null
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var rotationDegrees: Int = 0

    fun updateCorners(newCorners: FloatArray?, imgWidth: Int, imgHeight: Int, rotation: Int) {
        this.imageWidth = imgWidth
        this.imageHeight = imgHeight
        this.rotationDegrees = rotation
        viewCorners = if (newCorners != null && newCorners.size == 8) {
            Array(4) { i -> mapPoint(newCorners[i * 2], newCorners[i * 2 + 1]) }
        } else null
        invalidate()
    }

    private fun mapPoint(x: Float, y: Float): PointF {
        return when (rotationDegrees) {
            90 -> PointF(
                (y / imageHeight) * width,
                ((imageWidth - x) / imageWidth) * height
            )
            270 -> PointF(
                ((imageHeight - y) / imageHeight) * width,
                (x / imageWidth) * height
            )
            180 -> PointF(
                ((imageWidth - x) / imageWidth) * width,
                ((imageHeight - y) / imageHeight) * height
            )
            else -> PointF(
                (x / imageWidth) * width,
                (y / imageHeight) * height
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = viewCorners ?: return

        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        path.lineTo(pts[1].x, pts[1].y)
        path.lineTo(pts[2].x, pts[2].y)
        path.lineTo(pts[3].x, pts[3].y)
        path.close()

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
