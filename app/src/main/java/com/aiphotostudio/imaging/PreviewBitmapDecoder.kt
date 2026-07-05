package com.aiphotostudio.imaging

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import kotlin.math.max

object PreviewBitmapDecoder {
    private const val MAX_PREVIEW_DIMENSION = 1800
    private const val MAX_EXPORT_DIMENSION = 3200

    fun decode(contentResolver: ContentResolver, uri: Uri): Bitmap = decodeScaled(contentResolver, uri, MAX_PREVIEW_DIMENSION)

    fun decodeForExport(contentResolver: ContentResolver, uri: Uri): Bitmap = decodeScaled(contentResolver, uri, MAX_EXPORT_DIMENSION)

    private fun decodeScaled(contentResolver: ContentResolver, uri: Uri, maxDimension: Int): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val width = info.size.width
                val height = info.size.height
                val largest = max(width, height)
                if (largest > maxDimension) {
                    val scale = maxDimension.toFloat() / largest.toFloat()
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                }
            }.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            decodeLegacy(contentResolver, uri, maxDimension)
        }
    }

    private fun decodeLegacy(contentResolver: ContentResolver, uri: Uri, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val largest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateInSampleSize(largest, maxDimension)
        }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Could not decode selected image")
        val orientation = contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        return if (orientation != 0) {
            val matrix = Matrix().apply { postRotate(orientation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap
    }

    private fun calculateInSampleSize(largest: Int, maxDimension: Int): Int {
        var sample = 1
        while (largest / sample > maxDimension) sample *= 2
        return sample
    }
}
