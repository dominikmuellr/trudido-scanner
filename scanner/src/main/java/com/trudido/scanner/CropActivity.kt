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

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * Shown after the user captures a photo in ScannerActivity.
 * Displays the still image with auto-detected corners that
 * the user can drag to adjust, then confirm.
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        private const val TAG = "CropActivity"
    }

    private lateinit var cropOverlay: CropOverlayView
    private val nativeScanner = NativeScanner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        OpenCVLoader.initLocal()

        val imageView = findViewById<ImageView>(R.id.capturedImage)
        cropOverlay = findViewById(R.id.cropOverlay)

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        if (imagePath == null) {
            Toast.makeText(this, "No image provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Load bitmap
        val bitmap = BitmapFactory.decodeFile(imagePath)
        if (bitmap == null) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        imageView.setImageBitmap(bitmap)

        // Run corner detection on the captured image once the overlay is laid out
        cropOverlay.post {
            // Compute fitCenter mapping once (used by both success and fallback)
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val viewW = cropOverlay.width.toFloat()
            val viewH = cropOverlay.height.toFloat()
            val imgAspect = w / h
            val viewAspect = viewW / viewH

            val drawW: Float
            val drawH: Float
            val offsetX: Float
            val offsetY: Float
            if (imgAspect > viewAspect) {
                drawW = viewW
                drawH = viewW / imgAspect
                offsetX = 0f
                offsetY = (viewH - drawH) / 2f
            } else {
                drawH = viewH
                drawW = viewH * imgAspect
                offsetX = (viewW - drawW) / 2f
                offsetY = 0f
            }

            // Always pass bitmap + matrix for the magnifying glass
            cropOverlay.sourceBitmap = bitmap
            cropOverlay.imageToViewMatrix = Matrix().apply {
                setTranslate(offsetX, offsetY)
                preScale(drawW / w, drawH / h)
            }

            // Show default corners immediately while detection runs
            cropOverlay.setDefaultCorners()

            // Run detection off the main thread to avoid ANR
            Thread {
                try {
                    val mat = Mat()
                    Utils.bitmapToMat(bitmap, mat)
                    Log.d(TAG, "Mat created: ${mat.cols()}x${mat.rows()} ch=${mat.channels()}")
                    val corners = nativeScanner.findDocumentCornersColor(mat.nativeObjAddr)
                    mat.release()
                    Log.d(TAG, "Detection result: ${corners?.contentToString()}")

                    if (corners != null && corners.size == 8) {
                        runOnUiThread {
                            val pts = Array(4) { i ->
                                PointF(
                                    offsetX + (corners[i * 2] / w) * drawW,
                                    offsetY + (corners[i * 2 + 1] / h) * drawH
                                )
                            }
                            Log.d(TAG, "Mapped corners: ${pts.map { "(${it.x}, ${it.y})" }}")
                            cropOverlay.corners = pts
                            cropOverlay.invalidate()
                        }
                    } else {
                        Log.d(TAG, "No corners detected, keeping default")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Corner detection failed", e)
                }
            }.start()
        }

        // Retake → go back to camera
        findViewById<Button>(R.id.retakeButton).setOnClickListener {
            finish()
        }

        // Confirm → for now just show a toast (perspective transform will be added later)
        findViewById<Button>(R.id.confirmButton).setOnClickListener {
            Toast.makeText(this, "Document confirmed!", Toast.LENGTH_SHORT).show()
            // TODO: perform perspective transform and return result
        }
    }
}
