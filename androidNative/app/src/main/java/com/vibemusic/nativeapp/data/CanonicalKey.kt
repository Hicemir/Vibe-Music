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
    private val BRACKET_REGEXES = listOf(
        Regex("\\[[^\\[\\]]*\\]"),
        Regex("【[^【】]*】"),
        Regex("\\([^()]*\\)"),
        Regex("（[^（）]*）")
    )
    private val UNCLOSED_BRACKET_RE = Regex("(?:\\[[^\\]]*|【[^】]*|\\([^)]*|（[^）]*)$")
    private val LEADING_TRACK_NUM_RE = Regex("^\\d{1,3}\\s*(?:[-–—.]|\\))\\s+")
    private val TRAILING_FEAT_RE = Regex("(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$")
    private val PROMO_PHRASE_RE = Regex(
        "(?i)\\b(?:official\\s+music\\s+video|official\\s+lyric\\s+video|official\\s+visualizer|official\\s+video|official\\s+audio|official\\s+lyrics?|official\\s+mv|music\\s+video|lyric\\s+video|chronicle\\s+movie|the\\s+first\\s+take|first\\s+take|valkyrie\\s+theme|theme\\s+song|animation\\s+short|animated\\s+short|character\\s+demo|character\\s+teaser|phoenix\\s+ep|special\\s+ep|original\\s+soundtrack|soundtrack|full\\s+ver(?:sion)?\\.?|short\\s+ver(?:sion)?\\.?|live\\s+ver(?:sion)?\\.?|game\\s+ver(?:sion)?\\.?|tv\\s+size|off\\s+vocal|explicit|clean|remaster(?:ed)?|bonus\\s+track|album\\s+version|single\\s+version|ost|mv|pv|audio|hd|4k)\\b"
    )
    private val QUOTED_JA_RE = Regex("[「『]([^「」『』]+)[」』]")
    private val QUOTED_ASCII_RE = Regex("[\"“]([^\"“”]+)[\"”]")
    private val MULTI_SPACE_RE = Regex("\\s+")
    private val COMBINING_MARKS_RE = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM_SEP_RE = Regex("[^\\p{L}\\p{N}]+")
    private val TITLE_SEP_LIST = listOf(" // ", " — ", " – ", " ― ", " - ", " | ", " / ", " : ", " ~ ", " 〜 ", "—", "―", "｜")
    private val SONG_FRANCHISE_PATTERNS = listOf(
        "the first take", "honkai impact", "genshin impact", "zenless zone zero",
        "honkai star rail", "wuthering waves", "arknights", "bang dream", "chronicle movie"
    )
    private val SONG_META_EDGE_WORDS = setOf("ep", "ost", "soundtrack", "theme", "live", "official", "cover")
    private val SONG_GENERIC_WORDS = setOf(
        "track", "song", "music", "video", "audio", "ep", "ost", "theme", "live",
        "official", "mix", "remix", "cover", "demo", "full", "short", "ver",
        "version", "teaser", "trailer", "pv", "mv", "hd", "4k"
    )
    private val SONG_STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "of", "in", "on", "at", "to", "for", "from",
        "with", "by", "feat", "ft", "featuring", "ver", "version", "original",
        "soundtrack", "ost", "music", "song", "theme", "edit", "mix", "remaster",
        "remastered", "official", "video", "audio", "lyric", "lyrics", "mv", "pv",
        "ep", "live", "hd", "4k", "full", "short"
    )
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

    fun normSongText(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val nfd = Normalizer.normalize(nfkc, Normalizer.Form.NFD)
        val noMarks = COMBINING_MARKS_RE.replace(nfd, "")
        val folded = Normalizer.normalize(noMarks, Normalizer.Form.NFC).lowercase()
        val sb = StringBuilder(folded.length)
        var lastSpace = true
        for (ch in folded) {
            when {
                Character.isLetterOrDigit(ch) || ch == '%' -> {
                    sb.append(ch)
                    lastSpace = false
                }
                ch == '\'' || ch == '’' || ch == '‘' -> {
                    // omit apostrophes so "don't" matches "dont"
                }
                else -> {
                    if (!lastSpace) {
                        sb.append(' ')
                        lastSpace = true
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    private fun stripBrackets(s: String): String {
        var cur = s
        repeat(3) {
            var next = cur
            for (re in BRACKET_REGEXES) {
                next = re.replace(next, " ")
            }
            if (next == cur) return@repeat
            cur = next
        }
        cur = UNCLOSED_BRACKET_RE.replace(cur, " ")
        return cur.trim()
    }

    private fun stripTitleNoise(rawTitle: String): String {
        val orig = Normalizer.normalize(rawTitle.trim(), Normalizer.Form.NFKC)
        var cur = stripBrackets(BRACKET_YT_ID_SUFFIX_RE.replace(orig, ""))
        val withoutTrackNum = LEADING_TRACK_NUM_RE.replace(cur, "").trim()
        if (normSongText(withoutTrackNum).isNotEmpty()) {
            cur = withoutTrackNum
        }
        cur = TRAILING_FEAT_RE.replace(cur, " ")
        cur = PROMO_PHRASE_RE.replace(cur, " ")
        cur = TOPIC_RE.matcher(cur).replaceAll("").trim()
        if (normSongText(cur).isEmpty()) {
            val noUnclosed = UNCLOSED_BRACKET_RE.replace(orig, " ")
            if (normSongText(noUnclosed).isNotEmpty()) {
                return noUnclosed.trim()
            }
            return orig
        }
        return cur
    }

    private fun extractQuotedTitles(raw: String): List<String> {
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        fun add(cand: String) {
            val cleaned = normSongText(stripTitleNoise(cand))
            if (cleaned.length < 2 || SONG_GENERIC_WORDS.contains(cleaned) || !seen.add(cleaned)) return
            out.add(cleaned)
        }
        for (m in QUOTED_JA_RE.findAll(raw)) add(m.groupValues[1])
        for (m in QUOTED_ASCII_RE.findAll(raw)) add(m.groupValues[1])
        return out
    }

    private fun splitTitleSegments(s: String): List<String> {
        var work = Normalizer.normalize(s.replace("｜", " | "), Normalizer.Form.NFKC)
        val delim = "\u0000"
        for (sep in TITLE_SEP_LIST) {
            work = work.replace(sep, delim)
        }
        return work.split(delim).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun buildRawArtistSet(rawArtist: String): Set<String> {
        val out = HashSet<String>()
        val full = normSongText(TOPIC_RE.matcher(rawArtist).replaceAll(""))
        if (full.isNotEmpty() && full != "unknown artist") out.add(full)
        for (a in splitArtists(rawArtist)) {
            val na = normSongText(a)
            if (na.isNotEmpty() && na != "unknown artist") out.add(na)
        }
        return out
    }

    private fun isMetadataSegment(seg: String, rawArtists: Set<String>, knownNames: Set<String>): Boolean {
        val nRaw = normSongText(seg)
        if (nRaw.isEmpty()) return true
        val promoOnly = normSongText(
            PROMO_PHRASE_RE.replace(
                stripBrackets(Normalizer.normalize(seg, Normalizer.Form.NFKC)),
                " "
            )
        )
        if (promoOnly.isEmpty()) return true
        val nClean = normSongText(stripTitleNoise(seg))
        if (rawArtists.contains(nRaw) || rawArtists.contains(nClean) ||
            SONG_GENERIC_WORDS.contains(nRaw) || SONG_GENERIC_WORDS.contains(nClean)
        ) {
            return true
        }
        if (knownNames.contains(nRaw) || knownNames.contains(nClean)) return true
        for (pat in SONG_FRANCHISE_PATTERNS) {
            if (nRaw.contains(pat) || nClean.contains(pat)) return true
        }
        val words = nRaw.split(' ').filter { it.isNotEmpty() }
        if (words.isNotEmpty()) {
            if (SONG_META_EDGE_WORDS.contains(words.first()) || SONG_META_EDGE_WORDS.contains(words.last())) {
                return true
            }
        }
        return false
    }

    fun collectKnownLibraryNames(tracks: List<Track>, extraKnown: Collection<String> = emptyList()): Set<String> {
        val known = HashSet<String>(tracks.size * 2)
        fun addArtist(raw: String) {
            val full = normSongText(TOPIC_RE.matcher(raw).replaceAll(""))
            if (full.isNotEmpty() && full != "unknown artist") known.add(full)
            for (a in splitArtists(raw)) {
                val na = normSongText(a)
                if (na.isNotEmpty() && na != "unknown artist") known.add(na)
            }
        }
        for (t in tracks) {
            addArtist(t.artist)
            if (t.album.isNotBlank()) {
                val nal = normSongText(t.album)
                if (nal.isNotEmpty()) known.add(nal)
            }
        }
        for (ex in extraKnown) addArtist(ex)
        return known
    }

    fun songTitleKeys(rawTitle: String, rawArtist: String = "", knownNames: Set<String> = emptySet()): List<String> {
        val keys = mutableListOf<String>()
        val seen = HashSet<String>()
        fun addKey(kRaw: String) {
            val k = kRaw.trim()
            if (k.length < 2 || SONG_GENERIC_WORDS.contains(k) || !seen.add(k)) return
            keys.add(k)
        }

        for (q in extractQuotedTitles(rawTitle)) addKey(q)

        val rawArtists = buildRawArtistSet(rawArtist)
        val segs = splitTitleSegments(stripBrackets(rawTitle))
        if (segs.size >= 2) {
            for (seg in segs) {
                if (isMetadataSegment(seg, rawArtists, knownNames)) continue
                val k = normSongText(stripTitleNoise(seg))
                if (k.length >= 2 && !SONG_GENERIC_WORDS.contains(k) && !rawArtists.contains(k) && !knownNames.contains(k)) {
                    addKey(k)
                }
            }
        }

        addKey(normSongText(stripTitleNoise(rawTitle)))
        return keys
    }

    private fun tokenizeNormalizedSong(normTitle: String, rawArtist: String): List<String> {
        val artistToks = HashSet<String>()
        for (a in splitArtists(rawArtist)) {
            for (w in normSongText(a).split(' ')) {
                if (w.isNotEmpty()) artistToks.add(w)
            }
        }
        val fullArt = normSongText(TOPIC_RE.matcher(rawArtist).replaceAll(""))
        if (fullArt.isNotEmpty()) {
            for (w in fullArt.split(' ')) {
                if (w.isNotEmpty()) artistToks.add(w)
            }
        }
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        for (w in normTitle.split(' ')) {
            if (w.isEmpty() || SONG_STOP_WORDS.contains(w) || artistToks.contains(w) || !seen.add(w)) continue
            out.add(w)
        }
        return out
    }

    private fun songCoreTokens(rawTitle: String, rawArtist: String, knownNames: Set<String>): List<String> {
        val quoted = extractQuotedTitles(rawTitle)
        if (quoted.isNotEmpty()) {
            val toks = tokenizeNormalizedSong(quoted[0], rawArtist)
            if (toks.isNotEmpty()) return toks
        }
        val keys = songTitleKeys(rawTitle, rawArtist, knownNames)
        var best = ""
        var bestToks: List<String>? = null
        for (k in keys) {
            val toks = tokenizeNormalizedSong(k, rawArtist)
            if (toks.size >= 2 && (bestToks == null || k.length < best.length)) {
                best = k
                bestToks = toks
            }
        }
        if (bestToks != null) return bestToks
        if (keys.isNotEmpty()) return tokenizeNormalizedSong(keys[0], rawArtist)
        return tokenizeNormalizedSong(normSongText(stripTitleNoise(rawTitle)), rawArtist)
    }

    private fun isDistinctiveToken(tok: String): Boolean {
        if (tok.length >= 5) return true
        return tok.any { it.code > 127 || it.isDigit() || it == '%' }
    }

    private fun areSameSongTokens(a: List<String>, b: List<String>): Boolean {
        if (a.size < 2 || b.size < 2) return false
        val small = if (b.size < a.size) b else a
        val large = if (b.size < a.size) a else b
        val largeSet = if (large.size > 10) large.toHashSet() else null
        var distinctive = false
        var matched = 0
        for (tok in small) {
            val has = largeSet?.contains(tok) ?: large.contains(tok)
            if (!has) return false
            matched++
            if (isDistinctiveToken(tok)) distinctive = true
        }
        return distinctive || matched >= 3
    }

    class SongMatcher(val knownNames: Set<String> = emptySet()) {
        private val vidSet = HashSet<String>()
        private val titleKeySet = HashSet<String>()
        private val tokenList = ArrayList<List<String>>()

        fun add(vid: String, title: String, artist: String) {
            if (vid.isNotBlank()) vidSet.add(vid)
            for (k in songTitleKeys(title, artist, knownNames)) {
                titleKeySet.add(k)
            }
            val toks = songCoreTokens(title, artist, knownNames)
            if (toks.size >= 2) {
                tokenList.add(toks)
            }
        }

        fun matches(vid: String, title: String, artist: String): Boolean {
            if (vid.isNotBlank() && vidSet.contains(vid)) return true
            for (k in songTitleKeys(title, artist, knownNames)) {
                if (titleKeySet.contains(k)) return true
            }
            val toks = songCoreTokens(title, artist, knownNames)
            if (toks.size >= 2) {
                for (existing in tokenList) {
                    if (areSameSongTokens(toks, existing)) return true
                }
            }
            return false
        }

        fun clear() {
            vidSet.clear()
            titleKeySet.clear()
            tokenList.clear()
        }
    }

    fun canonicalSongTitle(rawTitle: String, rawArtist: String = ""): String {
        val keys = songTitleKeys(rawTitle, rawArtist)
        if (keys.isNotEmpty()) return keys.last()
        return normSongText(stripTitleNoise(rawTitle))
    }

    fun songIdentityKeys(rawTitle: String, rawArtist: String = ""): Set<String> {
        return songTitleKeys(rawTitle, rawArtist).toSet()
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
        ytIdFromName(track.relPath)?.let { if (isValidYtId(it)) return it }
        if (track.resolvedPath.isNotBlank()) {
            ytIdFromName(track.resolvedPath)?.let { if (isValidYtId(it)) return it }
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
