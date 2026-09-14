package com.nsfr.myjournal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min

internal data class PhotoRequest(val path: String, val width: Int, val height: Int)

internal fun photoTarget(size: IntSize, zoom: Float): IntSize {
    fun bucket(value: Int) = (ceil(value * zoom / 128.0).toInt() * 128).coerceAtLeast(128)
    return IntSize(bucket(size.width), bucket(size.height))
}

internal fun photoNearViewport(top: Float, bottom: Float, viewportHeight: Int): Boolean =
    viewportHeight > 0 && bottom >= -viewportHeight && top <= viewportHeight * 2f

/** Local files are immutable UUID assets; display requests never rewrite originals. */
internal class PhotoLoader(
    val budgetBytes: Int = min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(1),
    private val decode: (PhotoRequest) -> Bitmap? = ::decodePhoto
) {
    private val cache = object : LruCache<PhotoRequest, Bitmap>(budgetBytes) {
        override fun sizeOf(key: PhotoRequest, value: Bitmap) = value.allocationByteCount
    }
    private val permits = Semaphore(2)
    internal val cachedBytes: Int get() = cache.size()

    suspend fun load(request: PhotoRequest): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(request)?.let { return@withContext it }
        permits.withPermit {
            coroutineContext.ensureActive()
            cache.get(request)?.let { return@withPermit it }
            val bitmap = decode(request)
            try { coroutineContext.ensureActive() } catch (e: kotlinx.coroutines.CancellationException) {
                bitmap?.recycle()
                throw e
            }
            bitmap?.also {
                // Cache eviction only drops a reference: a visible Image may still own the bitmap.
                if (it.allocationByteCount <= budgetBytes) cache.put(request, it)
            }
        }
    }

    fun clear() = cache.evictAll()
}

private fun decodePhoto(request: PhotoRequest): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(request.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val scale = min(1.0, min(request.width.toDouble() / bounds.outWidth, request.height.toDouble() / bounds.outHeight))
    val targetWidth = (bounds.outWidth * scale).toInt().coerceAtLeast(1)
    val targetHeight = (bounds.outHeight * scale).toInt().coerceAtLeast(1)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2
    val decoded = BitmapFactory.decodeFile(request.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    if (decoded.width == targetWidth && decoded.height == targetHeight) return decoded
    return Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also { if (it !== decoded) decoded.recycle() }
}

internal object SharedPhotos { val loader = PhotoLoader() }

@Composable internal fun LocalPhoto(
    path: String, description: String, modifier: Modifier,
    nearViewport: () -> Boolean = { true }, zoom: () -> Float = { 1f },
    loader: PhotoLoader = SharedPhotos.loader
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentNear by rememberUpdatedState(nearViewport)
    val currentZoom by rememberUpdatedState(zoom)
    val request by remember(path) { derivedStateOf {
        if (size.width <= 0 || size.height <= 0 || !currentNear()) null
        else photoTarget(size, currentZoom()).let { PhotoRequest(path, it.width, it.height) }
    } }
    Box(modifier.onSizeChanged { size = it }, contentAlignment = Alignment.Center) {
        // Leaving the prefetch window disposes the bitmap state and cancels its request.
        request?.let { target -> key(target) {
            var bitmap by remember { mutableStateOf<Bitmap?>(null) }
            var failed by remember { mutableStateOf(false) }
            LaunchedEffect(target, loader) {
                try { bitmap = loader.load(target); failed = bitmap == null }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { failed = true }
            }
            bitmap?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                ?: Text(if (failed) "Не удалось загрузить фото" else "Загрузка фотографии…")
        } }
    }
}
