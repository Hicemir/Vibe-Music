package com.vibemusic.nativeapp.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vibemusic.nativeapp.genre.GenreTaxonomy
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class VibeStore(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val roots = ConcurrentHashMap<String, String>()

    init {
        loadRoots()
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_roots(
              root_key TEXT PRIMARY KEY,
              base_path TEXT UNIQUE NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tracks(
              id TEXT PRIMARY KEY,
              yt_id TEXT UNIQUE,
              root_key TEXT NOT NULL,
              rel_path TEXT NOT NULL,
              title TEXT NOT NULL DEFAULT '',
              artist TEXT NOT NULL DEFAULT '',
              album TEXT NOT NULL DEFAULT '',
              duration REAL NOT NULL DEFAULT 0,
              year INTEGER NOT NULL DEFAULT 0,
              added_at INTEGER NOT NULL DEFAULT 0,
              liked INTEGER NOT NULL DEFAULT 0,
              play_count INTEGER NOT NULL DEFAULT 0,
              last_played INTEGER NOT NULL DEFAULT 0,
              has_cover INTEGER NOT NULL DEFAULT 0,
              format TEXT NOT NULL DEFAULT '',
              bitrate INTEGER NOT NULL DEFAULT 0,
              file_size INTEGER NOT NULL DEFAULT 0,
              mtime_unix INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL DEFAULT 0,
              genre TEXT NOT NULL DEFAULT '',
              genre_source TEXT NOT NULL DEFAULT '',
              UNIQUE(root_key, rel_path)
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlists(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              created_at INTEGER NOT NULL DEFAULT 0,
              last_played INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_tracks(
              playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
              track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
              position INTEGER NOT NULL,
              PRIMARY KEY(playlist_id, position)
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pt_track ON playlist_tracks(track_id);")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS play_history(
              track_id TEXT NOT NULL,
              playlist_id INTEGER NOT NULL DEFAULT 0,
              played_at INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);")
        db.execSQL("CREATE TABLE IF NOT EXISTS hidden_tracks(track_id TEXT PRIMARY KEY);")
        ensureGenreSchema(db)
        ensureIndexes(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS hidden_tracks(track_id TEXT PRIMARY KEY);")
        ensureGenreSchema(db)
        ensureIndexes(db)
    }

    private fun ensureIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tracks_added ON tracks(added_at DESC, id DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_play_history_played_at ON play_history(played_at);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_play_history_track ON play_history(track_id);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ensureGenreSchema(db)
    }

    private fun ensureGenreSchema(db: SQLiteDatabase) {
        val cols = HashSet<String>()
        db.rawQuery("PRAGMA table_info(tracks)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0) cols.add(c.getString(nameIdx))
            }
        }
        if (cols.isNotEmpty()) {
            if (!cols.contains("genre")) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN genre TEXT NOT NULL DEFAULT ''")
            }
            if (!cols.contains("genre_source")) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN genre_source TEXT NOT NULL DEFAULT ''")
            }
        }
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS artist_genres(
              artist_key TEXT PRIMARY KEY,
              genre TEXT NOT NULL DEFAULT '',
              source TEXT NOT NULL DEFAULT '',
              checked_at INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS track_context(
              cache_key TEXT PRIMARY KEY,
              hints TEXT NOT NULL DEFAULT '',
              updated_at INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
    }

    // -------------------------------------------------------------
    // Roots Management
    // -------------------------------------------------------------

    private fun loadRoots() {
        readableDatabase.rawQuery("SELECT root_key, base_path FROM local_roots", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                val base = cursor.getString(1)
                roots[key] = base
            }
        }
    }

    @Synchronized
    fun ensureRoot(basePath: String): String {
        val cleanBase = File(basePath).canonicalPath
        for ((k, b) in roots) {
            if (b == cleanBase) return k
        }
        val key = if (!roots.containsKey("main")) {
            "main"
        } else {
            val md = MessageDigest.getInstance("SHA-1")
            val sum = md.digest(cleanBase.toByteArray())
            "r" + sum.take(4).joinToString("") { "%02x".format(it) }
        }

        val cv = ContentValues().apply {
            put("root_key", key)
            put("base_path", cleanBase)
        }
        writableDatabase.insertWithOnConflict("local_roots", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        roots[key] = cleanBase
        return key
    }

    fun splitPath(absPath: String): Triple<String, String, Boolean> {
        val cleanAbs = File(absPath).canonicalPath
        var bestLen = -1
        var bestRoot = ""
        var bestRel = ""

        for ((k, b) in roots) {
            if ((cleanAbs.startsWith(b + File.separator) || cleanAbs == b) && b.length > bestLen) {
                val rel = cleanAbs.removePrefix(b).removePrefix(File.separator)
                val c = CanonicalKey.cleanRelPath(rel)
                if (c != null) {
                    bestRoot = k
                    bestRel = c
                    bestLen = b.length
                }
            }
        }
        return if (bestLen >= 0) Triple(bestRoot, bestRel, true) else Triple("", "", false)
    }

    fun resolvePath(rootKey: String, relPath: String): String {
        val base = roots[rootKey] ?: return ""
        val osRel = relPath.replace('/', File.separatorChar)
        return File(base, osRel).path
    }

    private val coversDir: File by lazy {
        File(context.filesDir, "covers").apply { mkdirs() }
    }

    fun getCoverFile(trackId: String): File {
        return File(coversDir, "${CanonicalKey.coverKey(trackId)}.jpg")
    }

    // -------------------------------------------------------------
    // Tracks CRUD
    // -------------------------------------------------------------

    private class TrackCursorCols(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow("id")
        val ytId = cursor.getColumnIndexOrThrow("yt_id")
        val rootKey = cursor.getColumnIndexOrThrow("root_key")
        val relPath = cursor.getColumnIndexOrThrow("rel_path")
        val title = cursor.getColumnIndexOrThrow("title")
        val artist = cursor.getColumnIndexOrThrow("artist")
        val album = cursor.getColumnIndexOrThrow("album")
        val duration = cursor.getColumnIndexOrThrow("duration")
        val year = cursor.getColumnIndexOrThrow("year")
        val addedAt = cursor.getColumnIndexOrThrow("added_at")
        val liked = cursor.getColumnIndexOrThrow("liked")
        val playCount = cursor.getColumnIndexOrThrow("play_count")
        val lastPlayed = cursor.getColumnIndexOrThrow("last_played")
        val hasCover = cursor.getColumnIndexOrThrow("has_cover")
        val format = cursor.getColumnIndexOrThrow("format")
        val bitrate = cursor.getColumnIndexOrThrow("bitrate")
        val fileSize = cursor.getColumnIndexOrThrow("file_size")
        val mtimeUnix = cursor.getColumnIndexOrThrow("mtime_unix")
        val updatedAt = cursor.getColumnIndexOrThrow("updated_at")
        val genre = cursor.getColumnIndex("genre")
        val genreSource = cursor.getColumnIndex("genre_source")
    }

    private fun cursorToTrack(cursor: Cursor, cols: TrackCursorCols = TrackCursorCols(cursor)): Track {
        val id = cursor.getString(cols.id)
        val ytId = cursor.getString(cols.ytId)
        val rootKey = cursor.getString(cols.rootKey)
        val relPath = cursor.getString(cols.relPath)
        val title = cursor.getString(cols.title)
        val artist = cursor.getString(cols.artist)
        val album = cursor.getString(cols.album)
        val duration = cursor.getDouble(cols.duration)
        val year = cursor.getInt(cols.year)
        val addedAt = cursor.getLong(cols.addedAt)
        val liked = cursor.getInt(cols.liked) != 0
        val playCount = cursor.getInt(cols.playCount)
        val lastPlayed = cursor.getLong(cols.lastPlayed)
        val hasCover = cursor.getInt(cols.hasCover) != 0
        val format = cursor.getString(cols.format)
        val bitrate = cursor.getInt(cols.bitrate)
        val fileSize = cursor.getLong(cols.fileSize)
        val mtimeUnix = cursor.getLong(cols.mtimeUnix)
        val updatedAt = cursor.getLong(cols.updatedAt)
        val genre = if (cols.genre >= 0) cursor.getString(cols.genre).orEmpty() else ""
        val genreSource = if (cols.genreSource >= 0) cursor.getString(cols.genreSource).orEmpty() else ""

        val track = Track(
            id = id,
            ytId = ytId,
            rootKey = rootKey,
            relPath = relPath,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            year = year,
            addedAt = addedAt,
            liked = liked,
            playCount = playCount,
            lastPlayed = lastPlayed,
            hasCover = hasCover,
            format = format,
            bitrate = bitrate,
            fileSize = fileSize,
            mtimeUnix = mtimeUnix,
            updatedAt = updatedAt,
            genre = genre,
            genreSource = genreSource
        )
        track.resolvedPath = resolvePath(rootKey, relPath)
        return track
    }

    fun getAllTracks(includeHidden: Boolean = false): List<Track> {
        val list = mutableListOf<Track>()
        readableDatabase.rawQuery("SELECT * FROM tracks ORDER BY added_at DESC, id DESC", null).use { cursor ->
            val cols = TrackCursorCols(cursor)
            while (cursor.moveToNext()) {
                list.add(cursorToTrack(cursor, cols))
            }
        }
        if (!includeHidden) {
            val hiddenIds = getHiddenTrackIds()
            if (hiddenIds.isNotEmpty()) {
                return list.filter { !hiddenIds.contains(it.id) }
            }
        }
        return list
    }

    fun getHiddenTrackIds(): Set<String> {
        val set = mutableSetOf<String>()
        try {
            readableDatabase.rawQuery("SELECT track_id FROM hidden_tracks", null).use { cursor ->
                while (cursor.moveToNext()) {
                    set.add(cursor.getString(0))
                }
            }
        } catch (_: Exception) {}
        return set
    }

    fun isTrackHidden(trackId: String): Boolean {
        return getHiddenTrackIds().contains(trackId)
    }

    fun hideTrack(trackId: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO hidden_tracks (track_id) VALUES (?)", arrayOf(trackId))
    }

    fun unhideTrack(trackId: String) {
        writableDatabase.execSQL("DELETE FROM hidden_tracks WHERE track_id = ?", arrayOf(trackId))
    }

    fun getHiddenTracks(): List<Track> {
        val hiddenIds = getHiddenTrackIds()
        if (hiddenIds.isEmpty()) return emptyList()
        val all = getAllTracks(includeHidden = true)
        return all.filter { hiddenIds.contains(it.id) }
    }

    fun getTrackById(id: String): Track? {
        readableDatabase.rawQuery("SELECT * FROM tracks WHERE id = ? LIMIT 1", arrayOf(id)).use { cursor ->
            if (cursor.moveToNext()) {
                return cursorToTrack(cursor)
            }
        }
        return null
    }

    fun getTrackByPath(absPath: String): Track? {
        val (rootKey, relPath, ok) = splitPath(absPath)
        if (!ok) return null
        return getTrackByRel(rootKey, relPath)
    }

    fun getTrackByRel(rootKey: String, relPath: String): Track? {
        readableDatabase.rawQuery(
            "SELECT * FROM tracks WHERE root_key = ? AND rel_path = ? LIMIT 1",
            arrayOf(rootKey, relPath)
        ).use { cursor ->
            if (cursor.moveToNext()) {
                return cursorToTrack(cursor)
            }
        }
        return null
    }

    fun getTrackByRelAnyRoot(relPath: String): Track? {
        readableDatabase.rawQuery(
            "SELECT * FROM tracks WHERE rel_path = ? LIMIT 1",
            arrayOf(relPath)
        ).use { cursor ->
            if (cursor.moveToNext()) {
                return cursorToTrack(cursor)
            }
        }
        return null
    }

    @Synchronized
    fun upsertTrack(t: Track): String {
        val rel = CanonicalKey.cleanRelPath(t.relPath) ?: return ""
        val now = System.currentTimeMillis() / 1000
        val addedAt = if (t.addedAt == 0L) now else t.addedAt

        val db = writableDatabase
        db.beginTransaction()
        try {
            // Check if track ID already existed at another path
            var prevId: String? = null
            var pAdded = addedAt
            var pLiked = if (t.liked) 1 else 0
            var pPlays = t.playCount
            var pLast = t.lastPlayed
            var effGenre = t.genre
            var effGenreSrc = t.genreSource

            db.rawQuery(
                "SELECT id, added_at, liked, play_count, last_played, genre, genre_source FROM tracks WHERE root_key = ? AND rel_path = ? AND id <> ?",
                arrayOf(t.rootKey, rel, t.id)
            ).use { cur ->
                if (cur.moveToNext()) {
                    prevId = cur.getString(0)
                    pAdded = cur.getLong(1)
                    pLiked = cur.getInt(2)
                    pPlays = cur.getInt(3)
                    pLast = cur.getLong(4)
                    val pGenre = cur.getString(5).orEmpty()
                    val pGenreSrc = cur.getString(6).orEmpty()
                    if (effGenre.isEmpty() && pGenre.isNotEmpty()) {
                        effGenre = pGenre
                        effGenreSrc = pGenreSrc
                    }
                }
            }
            if (effGenre.isNotEmpty() && effGenreSrc.isEmpty()) {
                effGenreSrc = "tag"
            }

            if (prevId != null) {
                db.execSQL("UPDATE tracks SET rel_path = ? WHERE id = ?", arrayOf("//moved:$prevId", prevId))
            }

            val sql = """
                INSERT INTO tracks(
                  id, yt_id, root_key, rel_path, title, artist, album, duration, year,
                  added_at, liked, play_count, last_played, has_cover, format, bitrate,
                  file_size, mtime_unix, updated_at, genre, genre_source
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  yt_id=excluded.yt_id, root_key=excluded.root_key, rel_path=excluded.rel_path,
                  title=excluded.title, artist=excluded.artist, album=excluded.album,
                  duration=excluded.duration, year=excluded.year, has_cover=excluded.has_cover,
                  format=excluded.format, bitrate=excluded.bitrate, file_size=excluded.file_size,
                  mtime_unix=excluded.mtime_unix, updated_at=excluded.updated_at,
                  genre=CASE WHEN tracks.genre_source='user' THEN tracks.genre WHEN excluded.genre<>'' THEN excluded.genre ELSE tracks.genre END,
                  genre_source=CASE WHEN tracks.genre_source='user' THEN 'user' WHEN excluded.genre<>'' THEN excluded.genre_source ELSE tracks.genre_source END
            """.trimIndent()

            db.execSQL(
                sql,
                arrayOf(
                    t.id,
                    t.ytId,
                    t.rootKey,
                    rel,
                    t.title,
                    t.artist,
                    t.album,
                    t.duration,
                    t.year,
                    pAdded,
                    pLiked,
                    pPlays,
                    pLast,
                    if (t.hasCover) 1 else 0,
                    t.format,
                    t.bitrate,
                    t.fileSize,
                    t.mtimeUnix,
                    now,
                    effGenre,
                    effGenreSrc
                )
            )

            if (prevId != null) {
                db.execSQL("UPDATE playlist_tracks SET track_id = ? WHERE track_id = ?", arrayOf(t.id, prevId))
                db.execSQL("UPDATE play_history SET track_id = ? WHERE track_id = ?", arrayOf(t.id, prevId))
                db.execSQL("DELETE FROM tracks WHERE id = ?", arrayOf(prevId))
            }

            db.setTransactionSuccessful()
            return t.id
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteTracks(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.delete("tracks", "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        compactAllPlaylists()
    }

    @Synchronized
    fun setLiked(id: String, liked: Boolean) {
        val cv = ContentValues().apply { put("liked", if (liked) 1 else 0) }
        writableDatabase.update("tracks", cv, "id = ?", arrayOf(id))
    }

    @Synchronized
    fun updateTrackAlbum(trackId: String, albumName: String) {
        val clean = albumName.trim()
        if (clean.isEmpty()) return
        val cv = ContentValues().apply { put("album", clean) }
        writableDatabase.update("tracks", cv, "id = ?", arrayOf(trackId))
    }

    @Synchronized
    fun recordPlay(trackId: String, playlistId: Long) {
        val now = System.currentTimeMillis() / 1000
        val db = writableDatabase
        db.execSQL("UPDATE tracks SET play_count = play_count + 1, last_played = ? WHERE id = ?", arrayOf(now, trackId))
        db.execSQL("INSERT INTO play_history(track_id, playlist_id, played_at) VALUES(?, ?, ?)", arrayOf(trackId, playlistId, now))
    }

    fun getRecentPlayCounts(sinceUnixSec: Long): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        try {
            readableDatabase.rawQuery(
                "SELECT track_id, COUNT(*) FROM play_history WHERE played_at >= ? GROUP BY track_id",
                arrayOf(sinceUnixSec.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    counts[cursor.getString(0)] = cursor.getInt(1)
                }
            }
        } catch (_: Exception) {}
        return counts
    }

    // -------------------------------------------------------------
    // Playlists CRUD
    // -------------------------------------------------------------

    @Synchronized
    fun createPlaylist(name: String, trackIds: List<String> = emptyList()): Long {
        val trimmed = name.trim().ifEmpty { "New playlist" }
        val now = System.currentTimeMillis() / 1000
        val cv = ContentValues().apply {
            put("name", trimmed)
            put("created_at", now)
            put("last_played", 0L)
        }
        val id = writableDatabase.insert("playlists", null, cv)
        if (id > 0 && trackIds.isNotEmpty()) {
            setPlaylistTracks(id, trackIds)
        }
        return id
    }

    @Synchronized
    fun getPlaylistByName(name: String): Playlist? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        return getPlaylists().firstOrNull { it.name.equals(clean, ignoreCase = true) }
    }

    @Synchronized
    fun getOrCreatePlaylist(name: String): Long {
        val existing = getPlaylistByName(name)
        if (existing != null) return existing.id
        return createPlaylist(name)
    }

    @Synchronized
    fun renamePlaylist(id: Long, newName: String) {
        val cv = ContentValues().apply { put("name", newName.trim()) }
        writableDatabase.update("playlists", cv, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun deletePlaylist(id: Long) {
        writableDatabase.delete("playlists", "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun touchPlaylist(id: Long) {
        val cv = ContentValues().apply { put("last_played", System.currentTimeMillis() / 1000) }
        writableDatabase.update("playlists", cv, "id = ?", arrayOf(id.toString()))
    }

    fun getPlaylists(): List<Playlist> {
        val list = mutableListOf<Playlist>()
        val sql = """
            SELECT p.id, p.name, p.created_at, p.last_played,
              (SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = p.id) AS track_count,
              COALESCE((SELECT SUM(t.duration) FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id WHERE pt.playlist_id = p.id), 0) AS total_dur
            FROM playlists p
            ORDER BY p.created_at DESC, p.id DESC
        """.trimIndent()

        readableDatabase.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    Playlist(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        createdAt = cursor.getLong(2),
                        lastPlayed = cursor.getLong(3),
                        trackCount = cursor.getInt(4),
                        duration = cursor.getDouble(5),
                        coverTracks = emptyList()
                    )
                )
            }
        }
        if (list.isEmpty()) return list

        val coverMap = HashMap<Long, MutableList<String>>()
        readableDatabase.rawQuery(
            """
            SELECT pt.playlist_id, pt.track_id
            FROM playlist_tracks pt
            JOIN tracks t ON t.id = pt.track_id
            WHERE t.has_cover = 1
            ORDER BY pt.playlist_id, pt.position
            """.trimIndent(),
            null
        ).use { c2 ->
            while (c2.moveToNext()) {
                val pid = c2.getLong(0)
                val covers = coverMap.getOrPut(pid) { mutableListOf() }
                if (covers.size < 4) {
                    covers.add(c2.getString(1))
                }
            }
        }

        return list.map { pl ->
            val covers = coverMap[pl.id]
            if (covers != null) pl.copy(coverTracks = covers) else pl
        }
    }

    fun getPlaylistTracks(playlistId: Long): List<Track> {
        val list = mutableListOf<Track>()
        readableDatabase.rawQuery(
            """
            SELECT t.* FROM playlist_tracks pt
            JOIN tracks t ON t.id = pt.track_id
            WHERE pt.playlist_id = ?
            ORDER BY pt.position
            """.trimIndent(),
            arrayOf(playlistId.toString())
        ).use { cursor ->
            val cols = TrackCursorCols(cursor)
            while (cursor.moveToNext()) {
                list.add(cursorToTrack(cursor, cols))
            }
        }
        return list
    }

    fun getPlaylistTrackIds(playlistId: Long): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT track_id FROM playlist_tracks WHERE playlist_id = ? ORDER BY position",
            arrayOf(playlistId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(cursor.getString(0))
            }
        }
        return out
    }

    fun getAllPlaylistTracks(): Map<Long, List<String>> {
        val map = mutableMapOf<Long, MutableList<String>>()
        readableDatabase.rawQuery(
            "SELECT playlist_id, track_id FROM playlist_tracks ORDER BY playlist_id, position",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val pid = cursor.getLong(0)
                val tid = cursor.getString(1)
                map.getOrPut(pid) { mutableListOf() }.add(tid)
            }
        }
        return map
    }

    @Synchronized
    fun setPlaylistTracks(playlistId: Long, trackIds: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("playlist_tracks", "playlist_id = ?", arrayOf(playlistId.toString()))
            val cv = ContentValues()
            for ((index, tid) in trackIds.withIndex()) {
                cv.clear()
                cv.put("playlist_id", playlistId)
                cv.put("track_id", tid)
                cv.put("position", index)
                db.insert("playlist_tracks", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun addToPlaylist(playlistId: Long, trackIds: List<String>): Int {
        val cur = getPlaylistTrackIds(playlistId).toMutableList()
        val have = cur.toHashSet()
        var added = 0
        for (id in trackIds) {
            if (!have.contains(id)) {
                have.add(id)
                cur.add(id)
                added++
            }
        }
        if (added > 0) {
            setPlaylistTracks(playlistId, cur)
        }
        return added
    }

    @Synchronized
    private fun compactAllPlaylists() {
        val pids = mutableListOf<Long>()
        readableDatabase.rawQuery("SELECT id FROM playlists", null).use { cursor ->
            while (cursor.moveToNext()) {
                pids.add(cursor.getLong(0))
            }
        }
        for (id in pids) {
            val tracks = getPlaylistTrackIds(id)
            setPlaylistTracks(id, tracks)
        }
    }

    // -------------------------------------------------------------
    // Settings Persistence
    // -------------------------------------------------------------

    fun loadSettings(): AppSettings {
        var jsonStr: String? = null
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key = 'settings' LIMIT 1", null).use { cursor ->
            if (cursor.moveToNext()) {
                jsonStr = cursor.getString(0)
            }
        }
        if (jsonStr == null) {
            val defaultMusicDir = File(context.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile?.parentFile, "Music/VibeMusic").path
            return AppSettings(
                musicDirs = listOf(defaultMusicDir),
                downloadDir = defaultMusicDir
            )
        }

        return try {
            val obj = JSONObject(jsonStr)
            val musicDirs = mutableListOf<String>()
            val mArr = obj.optJSONArray("musicDirs")
            if (mArr != null) {
                for (i in 0 until mArr.length()) musicDirs.add(mArr.getString(i))
            }
            val downloadDir = obj.optString("downloadDir", "")
            val downloadFormat = obj.optString("downloadFormat", "m4a")
            val volume = obj.optInt("volume", 100)
            val shuffle = obj.optBoolean("shuffle", false)
            val repeat = obj.optString("repeat", "off")
            val fadeSeconds = obj.optDouble("fadeSeconds", 0.5).toFloat()
            val replayGain = obj.optBoolean("replayGain", true)
            val eqEnabled = obj.optBoolean("eqEnabled", false)
            val eqPreset = obj.optString("eqPreset", "Flat")
            val eqPreamp = obj.optDouble("eqPreamp", 0.0).toFloat().coerceIn(-12f, 6f)

            val eqBands = FloatArray(10)
            val eqArr = obj.optJSONArray("eqBands")
            if (eqArr != null && eqArr.length() == 10) {
                for (i in 0 until 10) eqBands[i] = eqArr.getDouble(i).toFloat()
            }

            val autoUpdateYtdlp = obj.optBoolean("autoUpdateYtdlp", true)

            val homeOrder = mutableListOf<String>()
            val hoArr = obj.optJSONArray("homeOrder")
            if (hoArr != null && hoArr.length() > 0) {
                for (i in 0 until hoArr.length()) homeOrder.add(hoArr.getString(i))
            } else {
                homeOrder.addAll(listOf("recentPlaylists", "quickPicks", "topTracks", "artistRadios", "discover", "recentlyAdded"))
            }
            homeOrder.removeAll { it == "allMusic" }

            val homeHidden = mutableSetOf<String>()
            val hhArr = obj.optJSONArray("homeHidden")
            if (hhArr != null) {
                for (i in 0 until hhArr.length()) homeHidden.add(hhArr.getString(i))
            } else {
                homeHidden.add("discover") // Online Discovery off by default
            }

            val enableOnlineFeatures = obj.optBoolean("enableOnlineFeatures", true)

            val aiObj = obj.optJSONObject("ai")
            val aiSettings = if (aiObj != null) {
                AiGenreSettings(
                    enabled = aiObj.optBoolean("enabled", false),
                    autoOnNew = aiObj.optBoolean("autoOnNew", false),
                    provider = aiObj.optString("provider", "gemini").ifBlank { "gemini" },
                    baseUrl = aiObj.optString("baseUrl", ""),
                    apiKey = aiObj.optString("apiKey", ""),
                    model = aiObj.optString("model", ""),
                    allowCustomGenres = aiObj.optBoolean("allowCustomGenres", false),
                    webSearch = aiObj.optBoolean("webSearch", true),
                    geminiGrounding = aiObj.optBoolean("geminiGrounding", false)
                )
            } else {
                AiGenreSettings()
            }

            AppSettings(
                musicDirs = musicDirs,
                downloadDir = downloadDir,
                downloadFormat = downloadFormat,
                volume = volume,
                shuffle = shuffle,
                repeat = repeat,
                fadeSeconds = fadeSeconds,
                replayGain = replayGain,
                eqEnabled = eqEnabled,
                eqPreset = eqPreset,
                eqPreamp = eqPreamp,
                eqBands = eqBands,
                autoUpdateYtdlp = autoUpdateYtdlp,
                homeOrder = homeOrder,
                homeHidden = homeHidden,
                enableOnlineFeatures = enableOnlineFeatures,
                ai = aiSettings
            )
        } catch (e: Exception) {
            AppSettings()
        }
    }

    @Synchronized
    fun saveSettings(settings: AppSettings) {
        val aiJson = JSONObject().apply {
            put("enabled", settings.ai.enabled)
            put("autoOnNew", settings.ai.autoOnNew)
            put("provider", settings.ai.provider)
            put("baseUrl", settings.ai.baseUrl)
            put("apiKey", settings.ai.apiKey)
            put("model", settings.ai.model)
            put("allowCustomGenres", settings.ai.allowCustomGenres)
            put("webSearch", settings.ai.webSearch)
            put("geminiGrounding", settings.ai.geminiGrounding)
        }
        val obj = JSONObject().apply {
            put("musicDirs", JSONArray(settings.musicDirs))
            put("downloadDir", settings.downloadDir)
            put("downloadFormat", settings.downloadFormat)
            put("volume", settings.volume)
            put("shuffle", settings.shuffle)
            put("repeat", settings.repeat)
            put("fadeSeconds", settings.fadeSeconds.toDouble())
            put("replayGain", settings.replayGain)
            put("eqEnabled", settings.eqEnabled)
            put("eqPreset", settings.eqPreset)
            put("eqPreamp", settings.eqPreamp.toDouble())
            put("eqBands", JSONArray(settings.eqBands.map { it.toDouble() }))
            put("autoUpdateYtdlp", settings.autoUpdateYtdlp)
            put("homeOrder", JSONArray(settings.homeOrder))
            put("homeHidden", JSONArray(settings.homeHidden.toList()))
            put("enableOnlineFeatures", settings.enableOnlineFeatures)
            put("ai", aiJson)
        }
        val cv = ContentValues().apply {
            put("key", "settings")
            put("value", obj.toString())
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    data class SavedQueueState(val trackIds: List<String>, val index: Int, val positionMs: Long)

    fun saveQueueState(trackIds: List<String>, index: Int, positionMs: Long = 0L) {
        val json = JSONObject().apply {
            put("trackIds", JSONArray(trackIds))
            put("index", index)
            put("positionMs", positionMs)
        }
        val cv = ContentValues().apply {
            put("key", "playback_queue")
            put("value", json.toString())
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadQueueState(): SavedQueueState {
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key = 'playback_queue' LIMIT 1", null).use { cursor ->
            if (cursor.moveToNext()) {
                val json = JSONObject(cursor.getString(0))
                val arr = json.optJSONArray("trackIds")
                val list = mutableListOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        list.add(arr.getString(i))
                    }
                }
                val idx = json.optInt("index", 0)
                val pos = json.optLong("positionMs", 0L)
                return SavedQueueState(list, idx, pos)
            }
        }
        return SavedQueueState(emptyList(), 0, 0L)
    }

    @Synchronized
    fun saveSyncedYtCollections(ids: Set<String>) {
        val arr = JSONArray()
        for (id in ids) {
            if (id.isNotBlank()) arr.put(id)
        }
        val cv = ContentValues().apply {
            put("key", "yt_synced_collections")
            put("value", arr.toString())
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadSyncedYtCollections(): Set<String> {
        var jsonStr: String? = null
        try {
            readableDatabase.rawQuery("SELECT value FROM settings WHERE key = 'yt_synced_collections' LIMIT 1", null).use { cursor ->
                if (cursor.moveToNext()) {
                    jsonStr = cursor.getString(0)
                }
            }
        } catch (_: Exception) {
            return emptySet()
        }
        if (jsonStr.isNullOrBlank()) return emptySet()
        return try {
            val arr = JSONArray(jsonStr)
            val set = linkedSetOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty()) set.add(item)
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    // -------------------------------------------------------------
    // Download History Persistence
    // -------------------------------------------------------------

    @Synchronized
    fun saveDownloadHistory(jobs: List<DownloadJob>) {
        val arr = JSONArray()
        for (job in jobs.take(200)) {
            val obj = JSONObject().apply {
                put("id", job.id)
                put("url", job.url)
                put("ytId", job.ytId)
                put("mode", job.mode)
                put("status", job.status)
                put("progress", job.progress.toDouble().let { if (it.isNaN()) 0.0 else it })
                put("downloadedBytes", job.downloadedBytes)
                put("totalBytes", job.totalBytes)
                put("speed", job.speed)
                put("eta", job.eta)
                put("title", job.title)
                if (job.error != null) put("error", job.error)
                if (job.targetPlaylistId != null) put("targetPlaylistId", job.targetPlaylistId)
                if (job.targetPlaylistIds.isNotEmpty()) put("targetPlaylistIds", JSONArray(job.targetPlaylistIds))
                if (job.markAsLiked) put("markAsLiked", true)
                if (!job.albumOverride.isNullOrBlank()) put("albumOverride", job.albumOverride)
                put("createdAt", job.createdAt)
            }
            arr.put(obj)
        }
        val cv = ContentValues().apply {
            put("key", "download_history")
            put("value", arr.toString())
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadDownloadHistory(): List<DownloadJob> {
        var jsonStr: String? = null
        try {
            readableDatabase.rawQuery("SELECT value FROM settings WHERE key = 'download_history' LIMIT 1", null).use { cursor ->
                if (cursor.moveToNext()) {
                    jsonStr = cursor.getString(0)
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        if (jsonStr.isNullOrBlank()) return emptyList()

        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<DownloadJob>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                val url = obj.optString("url", "")
                val ytId = obj.optString("ytId", "")
                val mode = obj.optString("mode", "track")
                val rawStatus = obj.optString("status", "done")
                val wasInterrupted = rawStatus == "downloading" || rawStatus == "pending"
                val status = if (wasInterrupted) "cancelled" else rawStatus
                val rawProgress = obj.optDouble("progress", 0.0).toFloat()
                val progress = if (rawProgress.isNaN()) 0f else rawProgress
                val downloadedBytes = obj.optLong("downloadedBytes", 0L)
                val totalBytes = obj.optLong("totalBytes", 0L)
                val speed = if (wasInterrupted) "" else obj.optString("speed", "")
                val eta = if (wasInterrupted) "" else obj.optString("eta", "")
                val title = obj.optString("title", "")
                val rawError = if (obj.has("error") && !obj.isNull("error")) {
                    obj.optString("error", "").ifEmpty { null }
                } else null
                val error = if (wasInterrupted) "Interrupted when app closed" else rawError
                val targetPlaylistId = if (obj.has("targetPlaylistId") && !obj.isNull("targetPlaylistId")) {
                    obj.optLong("targetPlaylistId")
                } else null
                val targetPlaylistIds = mutableListOf<Long>()
                val plIdsArr = obj.optJSONArray("targetPlaylistIds")
                if (plIdsArr != null) {
                    for (j in 0 until plIdsArr.length()) {
                        val pid = plIdsArr.optLong(j, 0L)
                        if (pid > 0L) targetPlaylistIds.add(pid)
                    }
                }
                val markAsLiked = obj.optBoolean("markAsLiked", false)
                val albumOverride = if (obj.has("albumOverride") && !obj.isNull("albumOverride")) {
                    obj.optString("albumOverride", "").ifBlank { null }
                } else null
                val createdAt = obj.optLong("createdAt", 0L)

                list.add(
                    DownloadJob(
                        id = id,
                        url = url,
                        ytId = ytId,
                        mode = mode,
                        status = status,
                        progress = progress,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        speed = speed,
                        eta = eta,
                        title = title,
                        error = error,
                        targetPlaylistId = targetPlaylistId,
                        targetPlaylistIds = targetPlaylistIds,
                        markAsLiked = markAsLiked,
                        albumOverride = albumOverride,
                        createdAt = createdAt
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    // -------------------------------------------------------------
    // Portable .m3u8 Import & Export (100% PC Parity)
    // -------------------------------------------------------------

    fun exportPlaylistToM3u8(playlistId: Long, targetFile: File): Boolean {
        val trackIds = getPlaylistTrackIds(playlistId)
        val tracks = trackIds.mapNotNull { getTrackById(it) }
        try {
            targetFile.parentFile?.mkdirs()
            FileWriter(targetFile).use { writer ->
                writer.write("#EXTM3U\n")
                for (t in tracks) {
                    writer.write("#EXTVIBE:ID=${t.id};YTID=${t.ytId ?: ""}\n")
                    writer.write("#EXTINF:${t.duration.toInt()},${t.artist} - ${t.title}\n")
                    writer.write("${t.relPath}\n")
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun importPlaylistFromM3u8(sourceFile: File): Long {
        if (!sourceFile.exists()) return -1L
        val entries = mutableListOf<Triple<String, String, String>>() // (id, ytId, path)
        var pendingId = ""
        var pendingYtId = ""

        BufferedReader(FileReader(sourceFile)).use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                val l = line.trim().removePrefix("\uFEFF")
                if (l.isNotEmpty()) {
                    if (l.startsWith("#EXTVIBE:")) {
                        val tags = l.removePrefix("#EXTVIBE:").split(";")
                        for (kv in tags) {
                            val parts = kv.split("=", limit = 2)
                            if (parts.size == 2) {
                                when (parts[0].trim().uppercase()) {
                                    "ID" -> pendingId = parts[1].trim()
                                    "YTID" -> pendingYtId = parts[1].trim()
                                }
                            }
                        }
                    } else if (!l.startsWith("#")) {
                        entries.add(Triple(pendingId, pendingYtId, l.replace('\\', '/')))
                        pendingId = ""
                        pendingYtId = ""
                    }
                }
                line = reader.readLine()
            }
        }

        val resolvedTrackIds = mutableListOf<String>()
        val fileDir = sourceFile.parentFile

        for ((id, ytId, path) in entries) {
            var match: Track? = null
            if (id.isNotEmpty()) {
                match = getTrackById(id)
            }
            if (match == null && ytId.isNotEmpty() && CanonicalKey.isValidYtId(ytId)) {
                match = getTrackById("yt:$ytId")
            }
            if (match == null && path.isNotEmpty()) {
                val cleanRel = CanonicalKey.cleanRelPath(path)
                if (cleanRel != null) {
                    match = getTrackByRelAnyRoot(cleanRel)
                }
                if (match == null && fileDir != null) {
                    val localF = File(fileDir, path)
                    if (localF.exists()) {
                        match = getTrackByPath(localF.canonicalPath)
                    }
                }
            }
            if (match != null) {
                resolvedTrackIds.add(match.id)
            }
        }

        if (resolvedTrackIds.isEmpty()) return -1L
        val plName = sourceFile.nameWithoutExtension.ifBlank { "Imported Playlist" }
        return createPlaylist(plName, resolvedTrackIds)
    }

    // -------------------------------------------------------------
    // Genres, Artist Cache, Context Hints & Pending AI Run
    // -------------------------------------------------------------

    data class GenreCounts(
        val total: Int = 0,
        val identified: Int = 0,
        val aiIdentified: Int = 0,
        val unidentified: Int = 0,
        val unchecked: Int = 0,
        val missing: Int = 0,
        val allAuto: Int = 0
    )

    fun getGenreCounts(): GenreCounts {
        var total = 0
        var identified = 0
        var aiIdentified = 0
        var unidentified = 0
        var unchecked = 0
        var missing = 0
        var allAuto = 0
        val sql = """
            SELECT
              COUNT(*),
              SUM(CASE WHEN genre<>'' THEN 1 ELSE 0 END),
              SUM(CASE WHEN genre<>'' AND genre_source='ai' THEN 1 ELSE 0 END),
              SUM(CASE WHEN genre='' AND genre_source='none' THEN 1 ELSE 0 END),
              SUM(CASE WHEN genre='' AND genre_source='' THEN 1 ELSE 0 END),
              SUM(CASE WHEN genre='' THEN 1 ELSE 0 END),
              SUM(CASE WHEN genre_source NOT IN ('user','tag') THEN 1 ELSE 0 END)
            FROM tracks
        """.trimIndent()
        readableDatabase.rawQuery(sql, null).use { c ->
            if (c.moveToNext()) {
                total = c.getInt(0)
                identified = if (c.isNull(1)) 0 else c.getInt(1)
                aiIdentified = if (c.isNull(2)) 0 else c.getInt(2)
                unidentified = if (c.isNull(3)) 0 else c.getInt(3)
                unchecked = if (c.isNull(4)) 0 else c.getInt(4)
                missing = if (c.isNull(5)) 0 else c.getInt(5)
                allAuto = if (c.isNull(6)) 0 else c.getInt(6)
            }
        }
        return GenreCounts(total, identified, aiIdentified, unidentified, unchecked, missing, allAuto)
    }

    @Synchronized
    fun setTracksGenre(ids: List<String>, genre: String, source: String) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.execSQL(
                    "UPDATE tracks SET genre = ?, genre_source = ? WHERE id = ?",
                    arrayOf(genre, source, id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun setTrackGenresBatch(updates: Map<String, Pair<String, String>>) {
        if (updates.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, gs) in updates) {
                db.execSQL(
                    "UPDATE tracks SET genre = ?, genre_source = ? WHERE id = ? AND genre_source <> 'user'",
                    arrayOf(gs.first, gs.second, id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun markUncheckedAsNone() {
        writableDatabase.execSQL("UPDATE tracks SET genre_source = 'none' WHERE genre = '' AND genre_source = ''")
    }

    @Synchronized
    fun setTrackUserGenres(trackId: String, genres: List<String>) {
        val clean = GenreTaxonomy.cleanUserGenres(genres)
        val joined = GenreTaxonomy.joinGenres(clean)
        val src = if (clean.isEmpty()) "" else "user"
        writableDatabase.execSQL(
            "UPDATE tracks SET genre = ?, genre_source = ? WHERE id = ?",
            arrayOf(joined, src, trackId)
        )
    }

    @Synchronized
    fun setArtistUserGenres(artistName: String, genres: List<String>) {
        val clean = GenreTaxonomy.cleanUserGenres(genres)
        val joined = GenreTaxonomy.joinGenres(clean)
        val src = if (clean.isEmpty()) "" else "user"
        val matchingIds = getAllTracks(includeHidden = true)
            .filter { CanonicalKey.hasArtist(it.artist, artistName) || it.displayArtist.equals(artistName, ignoreCase = true) }
            .map { it.id }
        if (matchingIds.isEmpty()) return
        setTracksGenre(matchingIds, joined, src)
    }

    @Synchronized
    fun resetAutoGenres() {
        val db = writableDatabase
        db.execSQL("UPDATE tracks SET genre = '', genre_source = '' WHERE genre_source <> 'user'")
        db.execSQL("DELETE FROM artist_genres")
    }

    fun getArtistGenres(artistKey: String): Triple<List<String>, Long, Boolean> {
        readableDatabase.rawQuery(
            "SELECT genre, checked_at FROM artist_genres WHERE artist_key = ? LIMIT 1",
            arrayOf(artistKey)
        ).use { c ->
            if (c.moveToNext()) {
                val g = c.getString(0).orEmpty()
                val checkedAt = c.getLong(1)
                return Triple(GenreTaxonomy.splitGenres(g), checkedAt, true)
            }
        }
        return Triple(emptyList(), 0L, false)
    }

    @Synchronized
    fun setArtistGenres(artistKey: String, genres: List<String>, source: String) {
        val now = System.currentTimeMillis() / 1000
        val cv = ContentValues().apply {
            put("artist_key", artistKey)
            put("genre", GenreTaxonomy.joinGenres(genres))
            put("source", source)
            put("checked_at", now)
        }
        writableDatabase.insertWithOnConflict("artist_genres", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getTrackContext(cacheKey: String): Pair<String, Long>? {
        readableDatabase.rawQuery(
            "SELECT hints, updated_at FROM track_context WHERE cache_key = ? LIMIT 1",
            arrayOf(cacheKey)
        ).use { c ->
            if (c.moveToNext()) {
                return Pair(c.getString(0).orEmpty(), c.getLong(1))
            }
        }
        return null
    }

    @Synchronized
    fun setTrackContext(cacheKey: String, hints: String) {
        val now = System.currentTimeMillis() / 1000
        val cv = ContentValues().apply {
            put("cache_key", cacheKey)
            put("hints", hints)
            put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict("track_context", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadPendingAiTracks(): List<String> {
        var jsonStr: String? = null
        readableDatabase.rawQuery(
            "SELECT value FROM settings WHERE key = 'ai_genre_pending' LIMIT 1",
            null
        ).use { c ->
            if (c.moveToNext()) jsonStr = c.getString(0)
        }
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val id = arr.optString(i, "").trim()
                if (id.isNotEmpty()) list.add(id)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun savePendingAiTracks(ids: List<String>) {
        if (ids.isEmpty()) {
            clearPendingAiTracks()
            return
        }
        val cv = ContentValues().apply {
            put("key", "ai_genre_pending")
            put("value", JSONArray(ids).toString())
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun clearPendingAiTracks() {
        writableDatabase.delete("settings", "key = ?", arrayOf("ai_genre_pending"))
    }

    companion object {
        const val DATABASE_NAME = "library.db"
        const val DATABASE_VERSION = 2
    }
}
