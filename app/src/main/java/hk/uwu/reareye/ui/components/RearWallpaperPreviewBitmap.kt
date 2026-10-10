package hk.uwu.reareye.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal val LocalRearWallpaperPreviewProvider =
    staticCompositionLocalOf<((String) -> ImageBitmap?)?> { null }

private object RearWallpaperPreviewBitmapCache {
    private val cache = LruCache<String, ImageBitmap>(64)

    fun get(path: String): ImageBitmap? = cache.get(path)

    fun put(path: String, bitmap: ImageBitmap) {
        cache.put(path, bitmap)
    }
}

@Composable
fun rememberRearWallpaperPreviewBitmap(cachePath: String?): ImageBitmap? {
    return rememberRearWallpaperPreviewBitmap(
        cachePath = cachePath,
        requestedWidthPx = 0,
        requestedHeightPx = 0,
    )
}

@Composable
fun rememberRearWallpaperPreviewBitmap(
    cachePath: String?,
    requestedWidthPx: Int,
    requestedHeightPx: Int,
): ImageBitmap? {
    LocalRearWallpaperPreviewProvider.current?.let { return it(cachePath.orEmpty()) }
    val normalizedWidth = requestedWidthPx.coerceAtLeast(0)
    val normalizedHeight = requestedHeightPx.coerceAtLeast(0)
    val cacheKey = cachePath?.let {
        it + "|" + normalizedWidth + "x" + normalizedHeight
    }
    val bitmap by produceState(
        initialValue = cacheKey?.let(RearWallpaperPreviewBitmapCache::get),
        key1 = cacheKey,
    ) {
        val path = cachePath?.takeIf { it.isNotBlank() } ?: return@produceState
        if (value != null) return@produceState

        val loadedBitmap = withContext(Dispatchers.IO) {
            loadPreviewBitmap(
                path = path,
                requestedWidthPx = normalizedWidth,
                requestedHeightPx = normalizedHeight,
            )
        }

        if (loadedBitmap != null) {
            cacheKey?.let { RearWallpaperPreviewBitmapCache.put(it, loadedBitmap) }
            value = loadedBitmap
        }
    }
    return bitmap
}

private fun loadPreviewBitmap(
    path: String,
    requestedWidthPx: Int,
    requestedHeightPx: Int,
): ImageBitmap? {
    val file = File(path)
    if (!file.isFile || file.length() <= 0L) return null
    return runCatching {
        if (requestedWidthPx <= 0 || requestedHeightPx <= 0) {
            return@runCatching BitmapFactory.decodeFile(path)?.asImageBitmap()
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                requestedWidth = requestedWidthPx,
                requestedHeight = requestedHeightPx,
            )
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        centerCropAndScale(
            bitmap = decoded,
            requestedWidth = requestedWidthPx,
            requestedHeight = requestedHeightPx,
        ).asImageBitmap()
    }.getOrNull()
}

private fun calculateInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    requestedWidth: Int,
    requestedHeight: Int,
): Int {
    var sampleSize = 1
    while (
        sourceWidth / (sampleSize * 2) >= requestedWidth &&
        sourceHeight / (sampleSize * 2) >= requestedHeight
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun centerCropAndScale(
    bitmap: Bitmap,
    requestedWidth: Int,
    requestedHeight: Int,
): Bitmap {
    val targetRatio = requestedWidth.toFloat() / requestedHeight
    val sourceRatio = bitmap.width.toFloat() / bitmap.height
    val cropWidth: Int
    val cropHeight: Int
    if (sourceRatio > targetRatio) {
        cropHeight = bitmap.height
        cropWidth = (cropHeight * targetRatio).toInt()
            .coerceIn(1, bitmap.width)
    } else {
        cropWidth = bitmap.width
        cropHeight = (cropWidth / targetRatio).toInt()
            .coerceIn(1, bitmap.height)
    }
    val cropX = ((bitmap.width - cropWidth) / 2).coerceAtLeast(0)
    val cropY = ((bitmap.height - cropHeight) / 2).coerceAtLeast(0)
    val cropped = if (cropWidth == bitmap.width && cropHeight == bitmap.height) {
        bitmap
    } else {
        Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight).also {
            bitmap.recycle()
        }
    }
    if (cropped.width == requestedWidth && cropped.height == requestedHeight) {
        return cropped
    }
    return Bitmap.createScaledBitmap(
        cropped,
        requestedWidth,
        requestedHeight,
        true,
    ).also {
        if (it !== cropped) cropped.recycle()
    }
}
