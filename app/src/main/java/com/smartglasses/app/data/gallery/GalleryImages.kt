package com.smartglasses.app.data.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Phone photos are several MB; the glasses have a few MB in all. Each imported photo becomes a
 * JPEG of at most [MAX_SIDE] px and about [TARGET_BYTES], upright and on white (no transparency).
 */
object GalleryImages {
    const val MAX_SIDE = 640
    const val TARGET_BYTES = 100 * 1024
    private const val START_QUALITY = 85
    private const val MIN_QUALITY = 45
    private const val QUALITY_STEP = 10
    private const val TAG = "GalleryImages"

    /** The JPEG for [uri], or null when it is not a readable image. */
    fun prepare(resolver: ContentResolver, uri: Uri): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE) }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            decoded?.let { encode(opaque(upright(fit(it, MAX_SIDE), orientation))) }
        }
    } catch (e: IOException) {
        Log.w(TAG, "cannot read $uri", e)
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "no access to $uri", e)
        null
    }

    /** Same picture, same name: importing it twice does not store it twice. */
    fun nameFor(jpeg: ByteArray): String {
        val crc = CRC32().apply { update(jpeg) }.value
        return "IMG_%08X.jpg".format(crc)
    }

    /** A small bitmap of a stored photo for the grid, or null when it cannot be read. */
    fun thumbnail(file: File, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, side) }
        return BitmapFactory.decodeFile(file.path, options)
    }

    /** Largest power of two that keeps the longer side at or above [side]. */
    internal fun sampleSize(width: Int, height: Int, side: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= side) sample *= 2
        return sample
    }

    private fun fit(bitmap: Bitmap, side: Int): Bitmap {
        val longer = max(bitmap.width, bitmap.height)
        if (longer <= side) return bitmap
        val scale = side.toFloat() / longer
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
    }

    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** JPEG has no transparency: transparent parts would turn black. */
    private fun opaque(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return out
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        var quality = START_QUALITY
        while (true) {
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (bytes.size <= TARGET_BYTES || quality <= MIN_QUALITY) return bytes
            quality -= QUALITY_STEP
        }
    }
}
