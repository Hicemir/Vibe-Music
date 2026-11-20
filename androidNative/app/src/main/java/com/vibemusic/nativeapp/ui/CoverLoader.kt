package com.vibemusic.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.vibemusic.nativeapp.R
import com.vibemusic.nativeapp.data.VibeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

object CoverLoader {
    private val maxCacheKb = ((Runtime.getRuntime().maxMemory() / 1024L).toInt() / 8)
        .coerceIn(8 * 1024, 32 * 1024)

    private val cache = object : LruCache<String, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val missingCovers = ConcurrentHashMap.newKeySet<String>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var inSampleSize = 1
            if (maxDim > 0 && (bounds.outHeight > maxDim || bounds.outWidth > maxDim)) {
                val halfH = bounds.outHeight / 2
                val halfW = bounds.outWidth / 2
                while ((halfH / inSampleSize) >= maxDim && (halfW / inSampleSize) >= maxDim) {
                    inSampleSize *= 2
                }
            }

            val opts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    fun loadInto(
        imageView: ImageView,
        store: VibeStore,
        trackId: String?,
        maxDim: Int,
        placeholderRes: Int = R.drawable.ic_music_note
    ) {
        if (trackId.isNullOrBlank()) {
            imageView.tag = null
            imageView.setImageResource(placeholderRes)
            return
        }
        val cacheKey = "$trackId@$maxDim"
        imageView.tag = cacheKey

        cache.get(cacheKey)?.let { cached ->
            imageView.setImageBitmap(cached)
            return
        }
        if (missingCovers.contains(trackId)) {
            imageView.setImageResource(placeholderRes)
            return
        }

        imageView.setImageResource(placeholderRes)
        val viewRef = WeakReference(imageView)
        ioScope.launch {
            val cachedAgain = cache.get(cacheKey)
            if (cachedAgain != null) {
                mainHandler.post {
                    val iv = viewRef.get()
                    if (iv != null && iv.tag == cacheKey) {
                        iv.setImageBitmap(cachedAgain)
                    }
                }
                return@launch
            }
            if (missingCovers.contains(trackId)) return@launch

            val bmp = decodeSampled(store.getCoverFile(trackId), maxDim)
            if (bmp == null) {
                missingCovers.add(trackId)
                return@launch
            }
            cache.put(cacheKey, bmp)
            mainHandler.post {
                val iv = viewRef.get()
                if (iv != null && iv.tag == cacheKey) {
                    iv.setImageBitmap(bmp)
                }
            }
        }
    }

    suspend fun loadBitmap(store: VibeStore, trackId: String, maxDim: Int): Bitmap? {
        if (trackId.isBlank()) return null
        val cacheKey = "$trackId@$maxDim"
        cache.get(cacheKey)?.let { return it }
        if (missingCovers.contains(trackId)) return null

        return withContext(Dispatchers.IO) {
            cache.get(cacheKey)?.let { return@withContext it }
            if (missingCovers.contains(trackId)) return@withContext null

            val bmp = decodeSampled(store.getCoverFile(trackId), maxDim)
            if (bmp == null) {
                missingCovers.add(trackId)
                null
            } else {
                cache.put(cacheKey, bmp)
                bmp
            }
        }
    }

    fun getCachedOrDecodeSync(store: VibeStore, trackId: String, maxDim: Int): Bitmap? {
        if (trackId.isBlank()) return null
        val cacheKey = "$trackId@$maxDim"
        cache.get(cacheKey)?.let { return it }
        if (missingCovers.contains(trackId)) return null

        val bmp = decodeSampled(store.getCoverFile(trackId), maxDim)
        if (bmp == null) {
            missingCovers.add(trackId)
            return null
        }
        cache.put(cacheKey, bmp)
        return bmp
    }

    fun preload(store: VibeStore, trackId: String, maxDim: Int) {
        if (trackId.isBlank()) return
        val cacheKey = "$trackId@$maxDim"
        if (cache.get(cacheKey) != null || missingCovers.contains(trackId)) return
        ioScope.launch {
            getCachedOrDecodeSync(store, trackId, maxDim)
        }
    }

    fun invalidate(trackId: String) {
        if (trackId.isBlank()) return
        missingCovers.remove(trackId)
        val prefix = "$trackId@"
        for (k in cache.snapshot().keys) {
            if (k.startsWith(prefix)) {
                cache.remove(k)
            }
        }
    }
}
