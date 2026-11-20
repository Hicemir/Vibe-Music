package com.vibemusic.nativeapp.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.regex.Pattern

object CanonicalKey {
    private val YT_ID_RE = Pattern.compile("^[A-Za-z0-9_-]{11}$")
    private val YT_PURL_RE = Pattern.compile("(?:youtube\\.com/watch\\?(?:[^#\\s]*&)?v=|youtu\\.be/)([A-Za-z0-9_-]{11})")
    private val YT_COMMENT_RE = Pattern.compile("(?m)^yt:([A-Za-z0-9_-]{11})\\s*$")
    private val FILE_VID_ID_RE = Pattern.compile("\\[([A-Za-z0-9_-]{11})\\]\\.[a-zA-Z0-9]+$")
    private val URI_SCHEME_RE = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val DRIVE_LETTER = Pattern.compile("^[A-Za-z]:")
    private val UNSAFE_ID_CHAR = Pattern.compile("[^A-Za-z0-9_-]")

    private val ARTIST_SPLIT_RE = Pattern.compile(
        "\\s*(?:,|、|;|/|&|\\s+x\\s+|×|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b)\\s*",
        Pattern.CASE_INSENSITIVE
    )
    private val TOPIC_RE = Pattern.compile("(?i)\\s*-\\s*topic\\s*$")
    private val BRACKET_YT_ID_SUFFIX_RE = Regex("\\s*\\[[A-Za-z0-9_-]{11}\\]\\s*$")
    private val PAREN_GROUP_RE = Regex("\\(([^)]*)\\)|\\[([^\\]]*)\\]|【([^】]*)】")
    private val NOISE_CONTENT_RE = Regex(
        "(?i)\\b(?:official|video|audio|music\\s*video|mv|pv|lyrics?|visualizer|topic|remaster(?:ed)?|full\\s*ver(?:sion)?|short\\s*ver(?:sion)?|tv\\s*size|hd|4k|hq|ost|soundtrack|prod\\.?|feat\\.?|ft\\.?)\\b"
    )
    private val NON_ALNUM_UNICODE_RE = Regex("[^\\p{L}\\p{N}\\s]")
    private val MULTI_SPACE_RE = Regex("\\s+")
    private val COMBINING_MARKS_RE = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM_SEP_RE = Regex("[^\\p{L}\\p{N}]+")
    private val TITLE_SEPARATORS = listOf(" - ", " – ", " — ", " | ", ": ")
    private val META_STOP_WORDS = setOf(
        "the", "and", "for", "from", "with", "feat", "ver", "version",
        "original", "soundtrack", "ost", "music", "song", "theme",
        "edit", "mix", "remaster", "remastered"
    )

    fun isValidYtId(id: String): Boolean {
        return YT_ID_RE.matcher(id).matches()
    }

    /**
     * Extracts an 11-char YouTube ID from tags: comment "yt:<id>" or purl URL.
     */
    fun ytIdFromTags(comment: String?, purl: String?): String? {
        if (!comment.isNullOrEmpty()) {
            val m = YT_COMMENT_RE.matcher(comment)
            if (m.find()) return m.group(1)
        }
        if (!purl.isNullOrEmpty()) {
            val m = YT_PURL_RE.matcher(purl)
            if (m.find()) return m.group(1)
        }
        return null
    }

    /**
     * Extracts an 11-char YouTube ID from file name:
     * "[<video_id>].<ext>" or "yt/<video_id>.<ext>".
     */
    fun ytIdFromName(rel: String): String? {
        val m = FILE_VID_ID_RE.matcher(rel)
        if (m.find()) {
            return m.group(1)
        }
        val file = File(rel)
        val baseName = file.nameWithoutExtension
        if (rel.startsWith("yt/") && YT_ID_RE.matcher(baseName).matches()) {
            return baseName
        }
        return null
    }

    /**
     * Computes the deterministic canonical ID for local audio files:
     * SHA-256 of [8-byte little-endian file_size] + [first 64 KB] + [last 64 KB].
     * Never depends on file paths.
     */
    fun localContentId(file: File): String {
        val size = file.length()
        val chunk = 64 * 1024
        val md = MessageDigest.getInstance("SHA-256")

        val sizeBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array()
        md.update(sizeBuf)

        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(chunk)
            val headRead = raf.read(buf, 0, chunk)
            if (headRead > 0) {
                md.update(buf, 0, headRead)
            }
            if (size > chunk) {
                var off = size - chunk
                if (off < chunk) off = chunk.toLong()
                raf.seek(off)
                val tailRead = raf.read(buf, 0, chunk)
                if (tailRead > 0) {
                    md.update(buf, 0, tailRead)
                }
            }
        }
        return "local:" + bytesToHex(md.digest())
    }

    /**
     * Normalizes a relative path: forward slashes, NFC, no drive letters, leading slashes, ".."
     */
    fun cleanRelPath(rel: String): String? {
        var r = rel.replace('\\', '/')
        r = Normalizer.normalize(r, Normalizer.Form.NFC)
        if (r.isEmpty() || r.startsWith("/") || DRIVE_LETTER.matcher(r).find() || URI_SCHEME_RE.matcher(r).find()) {
            return null
        }
        val segments = r.split("/")
        val cleaned = mutableListOf<String>()
        for (seg in segments) {
            if (seg == "..") return null
            if (seg.isNotEmpty() && seg != ".") {
                cleaned.add(seg)
            }
        }
        if (cleaned.isEmpty()) return null
        return cleaned.joinToString("/")
    }

    /**
     * coverKey makes a track ID safe for use as a file name (<id>.jpg)
     */
    fun coverKey(id: String): String {
        return UNSAFE_ID_CHAR.matcher(id).replaceAll("_")
    }

    fun cleanArtistString(raw: String): String {
        var s = TOPIC_RE.matcher(raw.trim()).replaceAll("").trim()
        val parts = splitArtists(s)
        val dedupped = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (p in parts) {
            val k = norm(p)
            if (k.isNotEmpty() && !seen.contains(k)) {
                seen.add(k)
                dedupped.add(p)
            }
        }
        return if (dedupped.isNotEmpty()) dedupped.joinToString(", ") else s
    }

    fun splitArtists(raw: String): List<String> {
        val s = TOPIC_RE.matcher(raw.trim()).replaceAll("").trim()
        if (s.isEmpty()) return emptyList()
        val tokens = ARTIST_SPLIT_RE.split(s)
        return tokens.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun norm(s: String): String {
        return Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFC)
    }

    fun hasArtist(rawArtistField: String, targetArtist: String): Boolean {
        val target = norm(targetArtist)
        if (target.isEmpty()) return false
        if (norm(rawArtistField) == target) return true
        return splitArtists(rawArtistField).any { norm(it) == target }
    }

    private fun stripTitleNoise(rawTitle: String): String {
        var s = BRACKET_YT_ID_SUFFIX_RE.replace(rawTitle.trim(), "")
        s = PAREN_GROUP_RE.replace(s) { mr ->
            val inner = mr.groupValues[1].ifEmpty { mr.groupValues[2].ifEmpty { mr.groupValues[3] } }
            if (NOISE_CONTENT_RE.containsMatchIn(inner)) "" else mr.value
        }
        s = TOPIC_RE.matcher(s).replaceAll("")
        return s.trim()
    }

    private fun normalizeTitleText(text: String): String {
        val nfc = Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFC)
        val stripped = NON_ALNUM_UNICODE_RE.replace(nfc, " ")
        return MULTI_SPACE_RE.replace(stripped, " ").trim()
    }

    private fun matchesArtistSegment(segment: String, artistNorms: Set<String>): Boolean {
        if (artistNorms.isEmpty()) return false
        val segNorm = norm(segment)
        if (segNorm.isEmpty()) return false
        if (artistNorms.contains(segNorm)) return true
        val segParts = splitArtists(segment).map { norm(it) }.filter { it.isNotEmpty() }
        return segParts.any { artistNorms.contains(it) }
    }

    fun canonicalSongTitle(rawTitle: String, rawArtist: String = ""): String {
        var s = stripTitleNoise(rawTitle)
        if (s.isEmpty()) return ""

        val artistNorms = buildSet {
            val full = norm(rawArtist)
            if (full.isNotEmpty() && full != "unknown artist") add(full)
            for (a in splitArtists(rawArtist)) {
                val k = norm(a)
                if (k.isNotEmpty() && k != "unknown artist") add(k)
            }
        }

        if (artistNorms.isNotEmpty()) {
            val seps = TITLE_SEPARATORS + ":"
            for (sep in seps) {
                val idx = s.indexOf(sep)
                if (idx > 0) {
                    val left = s.substring(0, idx).trim()
                    val right = s.substring(idx + sep.length).trim()
                    if (right.isNotEmpty() && matchesArtistSegment(left, artistNorms)) {
                        s = right
                        break
                    } else if (left.isNotEmpty() && matchesArtistSegment(right, artistNorms)) {
                        s = left
                        break
                    }
                }
            }
        }

        return normalizeTitleText(s)
    }

    fun songIdentityKeys(rawTitle: String, rawArtist: String = ""): Set<String> {
        val keys = linkedSetOf<String>()
        val withArtist = canonicalSongTitle(rawTitle, rawArtist)
        if (withArtist.isNotEmpty()) keys.add(withArtist)

        val withoutArtist = canonicalSongTitle(rawTitle, "")
        if (withoutArtist.isNotEmpty()) keys.add(withoutArtist)

        val cleaned = stripTitleNoise(rawTitle)
        val artistNorms = buildSet {
            val full = norm(rawArtist)
            if (full.isNotEmpty() && full != "unknown artist") add(full)
            for (a in splitArtists(rawArtist)) {
                val k = norm(a)
                if (k.isNotEmpty() && k != "unknown artist") add(k)
            }
        }

        for (sep in listOf(" - ", " – ", " — ")) {
            val idx = cleaned.indexOf(sep)
            if (idx > 0) {
                val left = cleaned.substring(0, idx).trim()
                val right = cleaned.substring(idx + sep.length).trim()
                val rightNorm = normalizeTitleText(right)
                if (rightNorm.length >= 2) {
                    keys.add(rightNorm)
                }
                if (artistNorms.isNotEmpty() && matchesArtistSegment(right, artistNorms)) {
                    val leftNorm = normalizeTitleText(left)
                    if (leftNorm.length >= 2) {
                        keys.add(leftNorm)
                    }
                }
                break
            }
        }
        return keys
    }

    fun titleTokens(title: String): List<String> = metaTokens(title, "")

    fun metaTokens(title: String, album: String = ""): List<String> {
        val raw = normFold("$title $album")
        if (raw.isEmpty()) return emptyList()
        val seen = linkedSetOf<String>()
        for (w in NON_ALNUM_SEP_RE.split(raw)) {
            if (w.isEmpty() || META_STOP_WORDS.contains(w)) continue
            val isLongEnough = w.length >= 3 || (w.length >= 2 && w.any { it.code > 127 })
            if (isLongEnough) seen.add(w)
        }
        return seen.toList()
    }

    /** NFKD lowercase without combining diacritics — handles Sān-Z / San-Z, Beyoncé / Beyonce. */
    fun normFold(s: String): String {
        val decomposed = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFKD)
        return COMBINING_MARKS_RE.replace(decomposed, "")
    }

    fun normName(s: String): String {
        val folded = normFold(s)
        val sb = StringBuilder(folded.length)
        for (c in folded) {
            if (c == '.' || c == '\'' || c == '’' || c == '"') continue
            sb.append(c)
        }
        return MULTI_SPACE_RE.replace(sb.toString(), " ").trim()
    }

    fun stripTitleDecorations(rawTitle: String): String = stripTitleNoise(rawTitle)

    fun trackVideoId(track: Track): String {
        val y = track.ytId?.trim().orEmpty()
        if (isValidYtId(y)) return y
        if (track.id.startsWith("yt:")) {
            val sub = track.id.removePrefix("yt:")
            if (isValidYtId(sub)) return sub
        }
        return ""
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val hexArray = "0123456789abcdef".toCharArray()
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
}
