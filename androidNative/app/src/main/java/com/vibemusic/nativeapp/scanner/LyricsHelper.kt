package com.vibemusic.nativeapp.scanner

import android.media.MediaMetadataRetriever
import com.vibemusic.nativeapp.data.LyricLine
import com.vibemusic.nativeapp.data.Lyrics
import com.vibemusic.nativeapp.data.Track
import java.io.File
import java.util.regex.Pattern

object LyricsHelper {

    private val TIME_TAG_RE = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:\\.(\\d{2,3}))?\\]")

    fun getLyrics(track: Track): Lyrics {
        val path = track.resolvedPath
        if (path.isEmpty()) return Lyrics(track.id, false, emptyList(), "")

        val audioFile = File(path)
        val lrcFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
        if (lrcFile.exists() && lrcFile.length() > 0) {
            try {
                val content = lrcFile.readText()
                val parsed = parseLrc(track.id, content)
                if (parsed != null) return parsed
            } catch (_: Exception) {}
        }

        // Try embedded lyrics from MediaMetadataRetriever
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(audioFile.absolutePath)
            // METADATA_KEY_COMPILATION or ID3 tags sometimes hold text
            // Note: MediaMetadataRetriever does not have a standard lyrics key on all Android versions,
            // but we can check if there's any embedded text or return empty
            retriever.release()
        } catch (_: Exception) {}

        return Lyrics(track.id, false, emptyList(), "")
    }

    fun parseLrc(trackId: String, lrcText: String): Lyrics? {
        if (lrcText.isBlank()) return null
        val lines = lrcText.lines()
        val parsedLines = mutableListOf<LyricLine>()

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val matcher = TIME_TAG_RE.matcher(trimmed)
            if (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = matcher.group(3) ?: "0"
                val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                val timeMs = (min * 60 + sec) * 1000 + ms
                val text = trimmed.substring(matcher.end()).trim()
                if (text.isNotEmpty()) {
                    parsedLines.add(LyricLine(timeMs, text))
                }
            }
        }

        parsedLines.sortBy { it.timeMs }
        val isSynced = parsedLines.isNotEmpty()

        return Lyrics(
            trackId = trackId,
            isSynced = isSynced,
            lines = parsedLines,
            raw = lrcText
        )
    }
}
