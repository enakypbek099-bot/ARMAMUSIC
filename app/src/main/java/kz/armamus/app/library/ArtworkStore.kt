package kz.armamus.app.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class ArtworkStore(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "artwork").apply { mkdirs() }

    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val largeCache = object : LruCache<String, Bitmap>(LARGE_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun sourceFile(trackId: String) = File(directory, "$trackId.src")
    private fun notificationFile(trackId: String) = File(directory, "$trackId.n.jpg")

    fun hasArtwork(trackId: String): Boolean =
        sourceFile(trackId).exists() && notificationFile(trackId).exists()

    fun notificationUri(trackId: String): Uri? =
        notificationFile(trackId).takeIf { it.exists() }?.let { Uri.fromFile(it) }

    fun save(trackId: String, data: ByteArray): Boolean {
        val source = sourceFile(trackId)
        val notification = notificationFile(trackId)
        return try {
            val preview = decodeScaled(NOTIFICATION_MAX_SIDE) { options ->
                BitmapFactory.decodeByteArray(data, 0, data.size, options)
            }
            if (preview == null) {
                false
            } else {
                FileOutputStream(notification).use { out ->
                    preview.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                preview.recycle()
                source.writeBytes(data)
                true
            }
        } catch (e: Exception) {
            source.delete()
            notification.delete()
            false
        }
    }

    fun cachedThumbnail(trackId: String, sizePx: Int): Bitmap? =
        thumbnailCache.get("$trackId@$sizePx")

    fun thumbnail(trackId: String, sizePx: Int): Bitmap? {
        val key = "$trackId@$sizePx"
        thumbnailCache.get(key)?.let { return it }

        val file = notificationFile(trackId)
        if (!file.exists()) return null

        val bitmap = decodeScaled(sizePx) { options ->
            BitmapFactory.decodeFile(file.path, options)
        } ?: return null

        thumbnailCache.put(key, bitmap)
        return bitmap
    }

    fun loadLarge(trackId: String, maxSidePx: Int): Bitmap? {
        val key = "$trackId@$maxSidePx"
        largeCache.get(key)?.let { return it }

        val file = sourceFile(trackId)
        if (!file.exists()) return null

        val bitmap = decodeScaled(maxSidePx) { options ->
            BitmapFactory.decodeFile(file.path, options)
        } ?: return null

        largeCache.put(key, bitmap)
        return bitmap
    }

    fun clearAll() {
        thumbnailCache.evictAll()
        largeCache.evictAll()
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun decodeScaled(
        maxSide: Int,
        decode: (BitmapFactory.Options) -> Bitmap?
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        decode(bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight

        if (width <= 0 || height <= 0) return null

        var sample = 1
        while (max(width, height) / (sample * 2) >= maxSide) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = decode(options) ?: return null

        val side = max(decoded.width, decoded.height)
        if (side <= maxSide) return decoded

        val scale = maxSide.toFloat() / side

        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true
        )

        if (scaled !== decoded) decoded.recycle()

        return scaled
    }

    private companion object {
        const val NOTIFICATION_MAX_SIDE = 1024
        const val THUMBNAIL_CACHE_KB = 8 * 1024
        const val LARGE_CACHE_KB = 16 * 1024
    }
}
