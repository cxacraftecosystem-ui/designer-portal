package com.designprototype.workshop.data

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

object DwSketchPlate {

    fun greyPlaneOf(bitmap: Bitmap): GreyPlane? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 1 || height < 1) return null
        val data = ByteArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val converted = DwImageQuality.greyPlaneFromArgb(row, width, 1)
            System.arraycopy(converted.data, 0, data, y * width, width)
        }
        return GreyPlane(data, width, height)
    }

    fun bitmapOf(plane: GreyPlane): Bitmap? {
        if (plane.width < 1 || plane.height < 1) return null
        val bitmap = runCatching {
            Bitmap.createBitmap(plane.width, plane.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val row = IntArray(plane.width)
        for (y in 0 until plane.height) {
            val offset = y * plane.width
            for (x in 0 until plane.width) {
                val value = plane.at(offset + x)
                row[x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
            bitmap.setPixels(row, 0, plane.width, 0, y, plane.width, 1)
        }
        return bitmap
    }

    fun platePng(bitmap: Bitmap, file: File): Boolean = runCatching {
        FileOutputStream(file).use { out ->
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.fd.sync()
            ok
        }
    }.getOrDefault(false)
}
