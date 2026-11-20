package com.vibemusic.nativeapp.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.vibemusic.nativeapp.data.CanonicalKey
import com.vibemusic.nativeapp.data.Track
import com.vibemusic.nativeapp.data.VibeStore
import com.vibemusic.nativeapp.genre.GenreTaxonomy
import com.vibemusic.nativeapp.ui.CoverLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class LibraryScanner(
    private val context: Context,
    private val store: VibeStore,
    private val onProgress: ((done: Int, total: Int, isScanning: Boolean) -> Unit)? = null
) {
    private val scanMutex = Mutex()
    private val audioExts = setOf(
        "mp3", "m4a", "opus", "ogg", "oga", "flac", "wav", "aac", "wma", "webm", "mka", "mp4", "alac"
    )

    suspend fun scan(dirs: List<String>) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            onProgress?.invoke(0, 0, true)

            val allExisting = store.getAllTracks(includeHidden = true)
            val existingTracks = allExisting.associateBy {
                try {
                    if (it.resolvedPath.isNotEmpty()) File(it.resolvedPath).canonicalPath else it.resolvedPath
                } catch (_: Exception) {
                    it.resolvedPath
                }
            }
            val liveRoots = mutableSetOf<String>()
            val audioFiles = mutableListOf<File>()

            for (dirPath in dirs) {
                val entry = File(dirPath)
                if (!entry.exists()) continue
                if (entry.isDirectory) {
                    val rootKey = store.ensureRoot(entry.canonicalPath)
                    liveRoots.add(rootKey)

                    entry.walkTopDown().forEach { file ->
                        if (file.isFile && audioExts.contains(file.extension.lowercase())) {
                            audioFiles.add(file)
                        }
                    }
                } else if (entry.isFile && audioExts.contains(entry.extension.lowercase())) {
                    val parentDir = entry.parentFile
                    if (parentDir != null && parentDir.exists()) {
                        store.ensureRoot(parentDir.canonicalPath)
                        audioFiles.add(entry)
                    }
                }
            }

            val total = audioFiles.size
            var done = 0
            val scannedIds = mutableSetOf<String>()

            for (file in audioFiles) {
                try {
                    val canonicalFilePath = file.canonicalPath
                    val (rootKey, relPath, ok) = store.splitPath(canonicalFilePath)
                    if (!ok) continue

                    val mtime = file.lastModified() / 1000
                    val size = file.length()
                    val existing = existingTracks[canonicalFilePath]

                    if (existing != null && existing.mtimeUnix == mtime && existing.fileSize == size) {
                        scannedIds.add(existing.id)
                        done++
                        if (done % 20 == 0 || done == total) {
                            onProgress?.invoke(done, total, true)
                        }
                        continue
                    }

                    // Need full metadata probe
                    val track = extractTrack(file, rootKey, relPath, mtime, size)
                    if (track != null) {
                        val savedId = store.upsertTrack(track)
                        if (savedId.isNotEmpty()) {
                            scannedIds.add(savedId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LibraryScanner", "Failed to scan file ${file.path}", e)
                } finally {
                    done++
                    if (done % 10 == 0 || done == total) {
                        onProgress?.invoke(done, total, true)
                    }
                }
            }

            // Clean vanished tracks under live roots or whose files no longer exist on disk
            val toDelete = mutableListOf<String>()
            for (track in allExisting) {
                if (!scannedIds.contains(track.id)) {
                    val f = if (track.resolvedPath.isNotEmpty()) File(track.resolvedPath) else null
                    if (f == null || !f.exists() || liveRoots.contains(track.rootKey)) {
                        toDelete.add(track.id)
                    }
                }
            }
            if (toDelete.isNotEmpty()) {
                store.deleteTracks(toDelete)
            }

            onProgress?.invoke(done, total, false)
        }
    }

    private fun extractTrack(
        file: File,
        rootKey: String,
        relPath: String,
        mtime: Long,
        size: Long
    ): Track? {
        val retriever = MediaMetadataRetriever()
        var durationSec = 0.0
        var title = ""
        var artist = ""
        var album = ""
        var year = 0
        var bitrate = 0
        var genre = ""
        var genreSource = ""
        var hasCover = false
        var embeddedPic: ByteArray? = null

        try {
            retriever.setDataSource(file.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (!durStr.isNullOrEmpty()) {
                durationSec = (durStr.toLongOrNull() ?: 0L) / 1000.0
            }

            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""

            val rawGenre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
            val mappedGenres = GenreTaxonomy.tagGenres(rawGenre)
            if (mappedGenres.isNotEmpty()) {
                genre = GenreTaxonomy.joinGenres(mappedGenres)
                genreSource = "tag"
            }

            val yearStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (!yearStr.isNullOrEmpty()) {
                val yearDigits = Regex("\\d{4}").find(yearStr)?.value
                year = yearDigits?.toIntOrNull() ?: 0
            }

            val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            if (!brStr.isNullOrEmpty()) {
                bitrate = (brStr.toIntOrNull() ?: 0) / 1000
            }

            embeddedPic = retriever.embeddedPicture
        } catch (e: Exception) {
            Log.w("LibraryScanner", "Retriever error on ${file.name}: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        // Clean tags
        if (title.isBlank()) {
            title = file.nameWithoutExtension.replace(Regex("\\[[A-Za-z0-9_-]{11}\\]$"), "").trim()
        }
        artist = CanonicalKey.cleanArtistString(artist)

        // Resolve Canonical Track ID
        val ytId = CanonicalKey.ytIdFromName(relPath)
        val id = if (ytId != null) {
            "yt:$ytId"
        } else {
            CanonicalKey.localContentId(file)
        }

        // Save cover image if present
        hasCover = extractAndSaveCover(file, id, embeddedPic)

        val track = Track(
            id = id,
            ytId = ytId,
            rootKey = rootKey,
            relPath = relPath,
            title = title,
            artist = artist,
            album = album,
            duration = durationSec,
            year = year,
            addedAt = System.currentTimeMillis() / 1000,
            hasCover = hasCover,
            format = file.extension.lowercase(),
            bitrate = bitrate,
            fileSize = size,
            mtimeUnix = mtime,
            genre = genre,
            genreSource = genreSource
        )
        return track
    }

    fun probeFileGenres(absPath: String): List<String> {
        if (absPath.isBlank()) return emptyList()
        val f = File(absPath)
        if (!f.exists()) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(absPath)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
            GenreTaxonomy.tagGenres(raw)
        } catch (_: Exception) {
            emptyList()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun extractAndSaveCover(file: File, trackId: String, embedded: ByteArray?): Boolean {
        val targetCover = store.getCoverFile(trackId)
        if (targetCover.exists() && targetCover.length() > 0) {
            return true
        }

        try {
            if (embedded != null && embedded.isNotEmpty()) {
                val bmp = BitmapFactory.decodeByteArray(embedded, 0, embedded.size)
                if (bmp != null) {
                    saveScaledCover(bmp, targetCover)
                    bmp.recycle()
                    CoverLoader.invalidate(trackId)
                    return true
                }
            }

            // Check sidecar images: <name>.jpg, <name>.png, <name>.webp, cover.jpg, folder.jpg
            val baseName = file.nameWithoutExtension
            val parent = file.parentFile
            if (parent != null) {
                val sidecars = listOf(
                    File(parent, "$baseName.jpg"),
                    File(parent, "$baseName.png"),
                    File(parent, "$baseName.webp"),
                    File(parent, "cover.jpg"),
                    File(parent, "folder.jpg")
                )
                for (sc in sidecars) {
                    if (sc.exists() && sc.length() > 0) {
                        val bmp = BitmapFactory.decodeFile(sc.absolutePath)
                        if (bmp != null) {
                            saveScaledCover(bmp, targetCover)
                            bmp.recycle()
                            CoverLoader.invalidate(trackId)
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("LibraryScanner", "Failed to extract cover for $trackId: ${e.message}")
        }
        return false
    }

    private fun saveScaledCover(source: Bitmap, target: File) {
        val maxDim = 600
        val width = source.width
        val height = source.height
        val scaled = if (width > maxDim || height > maxDim) {
            val scale = min(maxDim.toFloat() / width, maxDim.toFloat() / height)
            Bitmap.createScaledBitmap(source, (width * scale).toInt(), (height * scale).toInt(), true)
        } else {
            source
        }

        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        if (scaled != source) {
            scaled.recycle()
        }
    }
}
