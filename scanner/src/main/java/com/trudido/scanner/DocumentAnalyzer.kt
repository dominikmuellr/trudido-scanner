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

import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat

class DocumentAnalyzer(
    private val nativeScanner: NativeScanner,
    private val overlayView: DocumentOverlayView
) : ImageAnalysis.Analyzer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastAnalysisTime = 0L
    private val analysisIntervalMs = 250L  // throttle to ~4 fps (heavy pipeline)

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTime < analysisIntervalMs) {
            image.close()
            return
        }
        lastAnalysisTime = now
        val imgW = image.width
        val imgH = image.height
        val rotation = image.imageInfo.rotationDegrees

        // The ImageAnalysis is set to OUTPUT_IMAGE_FORMAT_RGBA_8888
        // so planes[0] contains RGBA pixels directly.
        val buffer = image.planes[0].buffer
        val rowStride = image.planes[0].rowStride
        val pixelStride = image.planes[0].pixelStride   // should be 4

        // Build an RGBA Mat — handle row padding if present
        val rgbaMat: Mat
        if (rowStride == imgW * pixelStride) {
            // No padding — fast path
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            rgbaMat = Mat(imgH, imgW, CvType.CV_8UC4)
            rgbaMat.put(0, 0, bytes)
        } else {
            // Row padding — copy row by row
            rgbaMat = Mat(imgH, imgW, CvType.CV_8UC4)
            val rowBytes = ByteArray(imgW * pixelStride)
            for (row in 0 until imgH) {
                buffer.position(row * rowStride)
                buffer.get(rowBytes)
                rgbaMat.put(row, 0, rowBytes)
            }
        }

        // Use colour-aware detection (same pipeline as capture)
        val corners = nativeScanner.findDocumentCornersColor(rgbaMat.nativeObjAddr)

        mainHandler.post {
            overlayView.updateCorners(corners, imgW, imgH, rotation)
        }

        rgbaMat.release()
        image.close()
    }
}