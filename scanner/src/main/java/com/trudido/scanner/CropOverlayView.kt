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
import android.view.MotionEvent
import android.view.View

/**
 * Drawn on top of the captured image in CropActivity.
 * Shows 4 draggable corner handles that the user can adjust.
 * When dragging, a magnifying-glass loupe appears so the user
 * can place corners with pixel-precision.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Paints ---
    private val linePaint = Paint().apply {
        color = Color.parseColor("#00C853")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = Color.argb(50, 0, 200, 80)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val handleFill = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(8f, 0f, 2f, Color.argb(140, 0, 0, 0))
    }
    private val handleStroke = Paint().apply {
        color = Color.parseColor("#00C853")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val dimPaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // Magnifying glass paints
    private val loupeBorderPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        setShadowLayer(10f, 0f, 4f, Color.argb(160, 0, 0, 0))
    }
    private val loupeCrosshairPaint = Paint().apply {
        color = Color.parseColor("#00C853")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val handleRadius = 36f
    private val touchSlop = 90f

    // Loupe settings
    private val loupeRadius = 100f     // radius of the magnifying circle in dp-ish pixels
    private val loupeZoom = 3.0f       // zoom factor
    private val loupeOffsetY = -200f   // how far above the finger the loupe sits

    // 4 corner points in VIEW coordinates (TL, TR, BR, BL)
    var corners: Array<PointF> = arrayOf(
        PointF(0f, 0f), PointF(0f, 0f), PointF(0f, 0f), PointF(0f, 0f)
    )

    private var dragIndex = -1
    private var dragPoint: PointF? = null  // current drag position for loupe

    /** The captured image bitmap — set by CropActivity so the loupe can zoom into it. */
    var sourceBitmap: Bitmap? = null

    /** Image-to-view mapping (set by CropActivity after layout). */
    var imageToViewMatrix: Matrix? = null

    fun setNormalisedCorners(pts: Array<PointF>) {
        corners = Array(4) {
            PointF(pts[it].x * width, pts[it].y * height)
        }
        invalidate()
    }

    fun setDefaultCorners() {
        val m = 0.15f
        corners = arrayOf(
            PointF(width * m, height * m),
            PointF(width * (1 - m), height * m),
            PointF(width * (1 - m), height * (1 - m)),
            PointF(width * m, height * (1 - m))
        )
        invalidate()
    }

    fun getNormalisedCorners(): Array<PointF> = Array(4) {
        PointF(corners[it].x / width, corners[it].y / height)
    }

    // --- Touch ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                var minDist = Float.MAX_VALUE
                dragIndex = -1
                for (i in corners.indices) {
                    val dx = event.x - corners[i].x
                    val dy = event.y - corners[i].y
                    val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (d < minDist && d < touchSlop) {
                        minDist = d
                        dragIndex = i
                    }
                }
                if (dragIndex >= 0) {
                    dragPoint = PointF(event.x, event.y)
                }
                return dragIndex >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIndex >= 0) {
                    corners[dragIndex].x = event.x.coerceIn(0f, width.toFloat())
                    corners[dragIndex].y = event.y.coerceIn(0f, height.toFloat())
                    dragPoint = PointF(corners[dragIndex].x, corners[dragIndex].y)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragIndex = -1
                dragPoint = null
                invalidate()
                return true
            }
        }
        return false
    }

    // --- Drawing ---
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Build quad path
        val quadPath = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            lineTo(corners[1].x, corners[1].y)
            lineTo(corners[2].x, corners[2].y)
            lineTo(corners[3].x, corners[3].y)
            close()
        }

        // Dim area outside the quad
        canvas.save()
        canvas.clipPath(quadPath, Region.Op.DIFFERENCE)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.restore()

        // Fill + outline
        canvas.drawPath(quadPath, fillPaint)
        canvas.drawPath(quadPath, linePaint)

        // Drag handles
        for (pt in corners) {
            canvas.drawCircle(pt.x, pt.y, handleRadius, handleFill)
            canvas.drawCircle(pt.x, pt.y, handleRadius, handleStroke)
        }

        // Edge midpoints
        for (i in corners.indices) {
            val j = (i + 1) % 4
            val mx = (corners[i].x + corners[j].x) / 2
            val my = (corners[i].y + corners[j].y) / 2
            canvas.drawCircle(mx, my, 8f, handleFill)
            canvas.drawCircle(mx, my, 8f, handleStroke)
        }

        // --- Magnifying glass ---
        drawLoupe(canvas)
    }

    private fun drawLoupe(canvas: Canvas) {
        val dp = dragPoint ?: return
        val bmp = sourceBitmap ?: return
        val viewMatrix = imageToViewMatrix ?: return

        // Invert the view matrix to go from view coords → bitmap coords
        val inverse = Matrix()
        if (!viewMatrix.invert(inverse)) return

        // Map the drag point to bitmap pixel coords
        val pts = floatArrayOf(dp.x, dp.y)
        inverse.mapPoints(pts)
        val bmpX = pts[0]
        val bmpY = pts[1]

        // Position loupe directly above the finger; flip down if near top edge
        val loupeX = dp.x
        var loupeY = dp.y + loupeOffsetY
        if (loupeY - loupeRadius < 0) {
            loupeY = dp.y + 200f  // show below instead
        }

        // How many bitmap pixels the loupe window covers
        val srcRadius = loupeRadius / loupeZoom
        // Compute scale from bitmap to view using the matrix
        val matValues = FloatArray(9)
        viewMatrix.getValues(matValues)
        val viewScaleX = matValues[Matrix.MSCALE_X]
        val bmpSrcRadius = srcRadius / viewScaleX  // radius in bitmap pixels

        // Source rect in bitmap coords
        val srcLeft = (bmpX - bmpSrcRadius).coerceIn(0f, bmp.width.toFloat())
        val srcTop = (bmpY - bmpSrcRadius).coerceIn(0f, bmp.height.toFloat())
        val srcRight = (bmpX + bmpSrcRadius).coerceIn(0f, bmp.width.toFloat())
        val srcBottom = (bmpY + bmpSrcRadius).coerceIn(0f, bmp.height.toFloat())
        val srcRect = Rect(srcLeft.toInt(), srcTop.toInt(), srcRight.toInt(), srcBottom.toInt())

        if (srcRect.width() <= 0 || srcRect.height() <= 0) return

        // Destination rect for the loupe circle
        val dstRect = RectF(
            loupeX - loupeRadius, loupeY - loupeRadius,
            loupeX + loupeRadius, loupeY + loupeRadius
        )

        // Clip to circle and draw the zoomed bitmap portion
        canvas.save()
        val clipPath = Path().apply {
            addCircle(loupeX, loupeY, loupeRadius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
        canvas.drawBitmap(bmp, srcRect, dstRect, null)
        canvas.restore()

        // Loupe border
        canvas.drawCircle(loupeX, loupeY, loupeRadius, loupeBorderPaint)

        // Crosshair at center of loupe
        val crossSize = 16f
        canvas.drawLine(
            loupeX - crossSize, loupeY, loupeX + crossSize, loupeY, loupeCrosshairPaint
        )
        canvas.drawLine(
            loupeX, loupeY - crossSize, loupeX, loupeY + crossSize, loupeCrosshairPaint
        )
    }
}
