package com.vibemusic.nativeapp.genre

import com.vibemusic.nativeapp.data.CanonicalKey
import com.vibemusic.nativeapp.data.Track
import com.vibemusic.nativeapp.data.VibeStore
import com.vibemusic.nativeapp.scanner.LibraryScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

data class GenreStatus(
    val running: Boolean = false,
    val mode: String = "", // "" | "classic" | "ai"
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val identified: Int = 0,
    val aiIdentified: Int = 0,
    val unidentified: Int = 0,
    val unchecked: Int = 0,
    val pendingAi: Int = 0,
    val totalTracks: Int = 0,
    val message: String = "",
    val error: String = ""
)

data class GenreTargets(
    val missing: Int = 0,
    val allAuto: Int = 0,
    val pendingAi: Int = 0,
    val total: Int = 0
)

class GenreDetector(
    private val store: VibeStore,
    private val scanner: LibraryScanner
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var activeJob: Job? = null
    private var again = false
    private var againMode = ""
    private val mbLastMs = AtomicLong(0L)
    private val enricher = ContextEnricher()
    private val geminiPaceMs = 4200L

    private val _status = MutableStateFlow(GenreStatus())
    val status: StateFlow<GenreStatus> = _status.asStateFlow()

    var onGenresUpdated: (() -> Unit)? = null

    @Volatile
    private var curRunning = false
    @Volatile
    private var curMode = ""
    @Volatile
    private var curPhase = ""
    @Volatile
    private var curDone = 0
    @Volatile
    private var curTotal = 0
    @Volatile
    private var curMessage = ""
    @Volatile
    private var curError = ""

    fun refreshStatus(): GenreStatus {
        val counts = store.getGenreCounts()
        val pending = store.loadPendingAiTracks().size
        val st = GenreStatus(
            running = curRunning,
            mode = curMode,
            phase = curPhase,
            done = curDone,
            total = curTotal,
            identified = counts.identified,
            aiIdentified = counts.aiIdentified,
            unidentified = counts.unidentified,
            unchecked = counts.unchecked,
            pendingAi = pending,
            totalTracks = counts.total,
            message = curMessage,
            error = curError
        )
        _status.value = st
        return st
    }

    fun getTargets(): GenreTargets {
        val counts = store.getGenreCounts()
        val pending = store.loadPendingAiTracks().size
        return GenreTargets(
            missing = counts.missing,
            allAuto = counts.allAuto,
            pendingAi = pending,
            total = counts.total
        )
    }

    fun kickAuto() {
        startRun("auto", queueIfBusy = true)
    }

    fun startClassic(redetectAll: Boolean = false): String {
        val runMode = if (redetectAll) "classic-all" else "classic"
        return if (startRun(runMode, queueIfBusy = !redetectAll)) "" else if (redetectAll) "Genre detection is already running" else "Queued behind active genre run"
    }

    fun startAi(mode: String): String {
        val runMode = when (mode) {
            "missing" -> "ai-missing"
            "all" -> "ai-all"
            "resume" -> "ai-resume"
            else -> return "Unknown AI detection mode \"$mode\""
        }
        return if (startRun(runMode, queueIfBusy = false)) "" else "Genre detection is already running"
    }

    fun clearPendingAi() {
        store.clearPendingAiTracks()
        refreshStatus()
    }

    fun cancel() {
        val jobToCancel: Job?
        synchronized(lock) {
            again = false
            againMode = ""
            jobToCancel = activeJob
        }
        jobToCancel?.cancel()
    }

    private fun startRun(runMode: String, queueIfBusy: Boolean): Boolean {
        synchronized(lock) {
            if (curRunning) {
                if (queueIfBusy) {
                    again = true
                    if (againMode.isEmpty()) againMode = runMode
                }
                return false
            }
            curRunning = true
            again = false
            againMode = ""
            curMode = if (runMode.startsWith("ai-")) "ai" else "classic"
            curPhase = "Starting"
            curDone = 0
            curTotal = 0
            curMessage = ""
            curError = ""
            refreshStatus()

            activeJob = scope.launch {
                var nextMode = runMode
                try {
                    while (true) {
                        runPass(nextMode)
                        synchronized(lock) {
                            if (!again) {
                                curRunning = false
                                curMode = ""
                                curPhase = ""
                                activeJob = null
                                return@launch
                            }
                            nextMode = againMode.ifEmpty { "auto" }
                            again = false
                            againMode = ""
                            curMode = if (nextMode.startsWith("ai-")) "ai" else "classic"
                            curPhase = "Starting"
                            curDone = 0
                            curTotal = 0
                        }
                        refreshStatus()
                    }
                } finally {
                    synchronized(lock) {
                        curRunning = false
                        curMode = ""
                        curPhase = ""
                        activeJob = null
                    }
                    refreshStatus()
                }
            }
            return true
        }
    }

    private fun publish() {
        refreshStatus()
        onGenresUpdated?.invoke()
    }

    private fun setPhase(phase: String, total: Int) {
        curPhase = phase
        curTotal = total
        curDone = 0
        refreshStatus()
    }

    private fun shouldAutoAi(): Boolean {
        val settings = store.loadSettings()
        if (!settings.enableOnlineFeatures) return false
        val ai = settings.ai
        if (!ai.enabled || !ai.autoOnNew) return false
        return try {
            AiGenreClient.resolveConfig(ai)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun runPass(runMode: String) {
        if (runMode == "classic-all") {
            store.resetAutoGenres()
            publish()
        }
        val settings = store.loadSettings()
        var tracks = store.getAllTracks(includeHidden = true)
        if (tracks.isEmpty()) return

        // 1) Embedded file tags for tracks never checked yet
        val unchecked = tracks.filter {
            it.genre.isEmpty() && it.genreSource.isEmpty() && it.resolvedPath.isNotEmpty()
        }
        if (unchecked.isNotEmpty()) {
            setPhase("Reading file tags", unchecked.size)
            val ch = Channel<Track>(Channel.UNLIMITED)
            for (t in unchecked) ch.trySend(t)
            ch.close()
            val done = AtomicInteger(0)
            try {
                coroutineScope {
                    repeat(minOf(4, unchecked.size)) {
                        launch {
                            for (t in ch) {
                                coroutineContext.ensureActive()
                                val gs = scanner.probeFileGenres(t.resolvedPath)
                                if (gs.isNotEmpty()) {
                                    store.setTracksGenre(listOf(t.id), GenreTaxonomy.joinGenres(gs), "tag")
                                }
                                val d = done.incrementAndGet()
                                curDone = d
                                if (d % 10 == 0 || d == unchecked.size) refreshStatus()
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                curMessage = "Stopped"
                publish()
                return
            }
        }

        val explicitAi = runMode == "ai-missing" || runMode == "ai-all" || runMode == "ai-resume"
        if (runMode == "auto" && !settings.enableOnlineFeatures) {
            // Respect Offline Mode: only local file tags + heuristics
            applyGenres()
            publish()
            return
        }

        val runAi = explicitAi || (runMode == "auto" && shouldAutoAi())

        if (runAi) {
            tracks = store.getAllTracks(includeHidden = true)
            val targetTracks: List<Track> = when (runMode) {
                "ai-resume" -> {
                    val byId = tracks.associateBy { it.id }
                    store.loadPendingAiTracks().mapNotNull { id ->
                        val t = byId[id]
                        if (t != null && t.genreSource != "user" && t.genreSource != "tag") t else null
                    }
                }
                "ai-all" -> tracks.filter { it.genreSource != "user" && it.genreSource != "tag" }
                else -> tracks.filter { it.genre.isEmpty() && it.genreSource != "user" && it.genreSource != "tag" }
            }

            if (targetTracks.isNotEmpty()) {
                curMode = "ai"
                val targetIds = targetTracks.map { it.id }
                if (explicitAi) store.savePendingAiTracks(targetIds)

                val aiCfg = settings.ai
                val resolved = try {
                    AiGenreClient.resolveConfig(aiCfg)
                } catch (e: Exception) {
                    curError = e.message ?: "Invalid AI configuration"
                    curMessage = curError
                    publish()
                    return
                }

                var hintsMap: Map<String, String> = emptyMap()
                if (aiCfg.webSearch) {
                    setPhase("Searching YouTube, iTunes & Wikipedia", targetTracks.size)
                    try {
                        hintsMap = enricher.enrichTracks(store, targetTracks) { done, _ ->
                            curDone = done
                            if (done % 4 == 0 || done == targetTracks.size) refreshStatus()
                        }
                    } catch (_: CancellationException) {
                        curMessage = "Stopped"
                        publish()
                        return
                    }
                }

                val batchSize = when {
                    resolved.providerId == "ollama" || resolved.providerId == "lmstudio" || resolved.providerId == "custom" ->
                        if (aiCfg.webSearch) 8 else 10
                    resolved.providerId == "gemini" && aiCfg.geminiGrounding -> 8
                    aiCfg.webSearch -> 12
                    else -> 15
                }

                setPhase("Classifying songs with AI", targetTracks.size)
                val sysPrompt = AiGenreClient.buildGenreSystemPrompt(aiCfg.allowCustomGenres)

                for (start in targetTracks.indices step batchSize) {
                    val end = minOf(start + batchSize, targetTracks.size)
                    val batch = targetTracks.subList(start, end)
                    val inputs = batch.mapIndexed { idx, t ->
                        TrackGenreInput(
                            index = idx + 1,
                            artist = t.artist,
                            title = t.title,
                            album = t.album,
                            year = t.year,
                            hints = hintsMap[t.id].orEmpty()
                        )
                    }
                    val batchStart = System.currentTimeMillis()
                    val raw = try {
                        AiGenreClient.classifyBatch(
                            cfg = resolved,
                            systemPrompt = sysPrompt,
                            userPrompt = AiGenreClient.buildGenreUserPrompt(inputs),
                            onRetryWait = { waitMs, _ ->
                                val secs = maxOf(1, (waitMs / 1000.0).roundToInt())
                                curPhase = "Rate limited — waiting ${secs}s to retry"
                                refreshStatus()
                            }
                        )
                    } catch (_: CancellationException) {
                        curMessage = "Stopped"
                        publish()
                        return
                    } catch (e: Exception) {
                        curError = e.message ?: "AI request failed"
                        curMessage = curError
                        publish()
                        return
                    }

                    val parsed = try {
                        AiGenreClient.parseAiResponse(raw)
                    } catch (e: Exception) {
                        curError = e.message ?: "Could not parse AI response"
                        curMessage = curError
                        publish()
                        return
                    }
                    val byIdx = parsed.associateBy { it.index }
                    val updates = LinkedHashMap<String, Pair<String, String>>(batch.size)
                    for ((idx, t) in batch.withIndex()) {
                        var gs = byIdx[idx + 1]?.let {
                            AiGenreClient.validateAiGenres(it, aiCfg.allowCustomGenres)
                        }.orEmpty()
                        if (gs.isNotEmpty()) {
                            val hg = GenreTaxonomy.heuristicGenres(t)
                            if (hg.isNotEmpty()) {
                                gs = GenreTaxonomy.dedupeGenres(gs + hg, 5)
                            }
                            updates[t.id] = GenreTaxonomy.joinGenres(gs) to "ai"
                        } else if (runMode == "ai-all") {
                            updates[t.id] = "" to ""
                        }
                    }
                    store.setTrackGenresBatch(updates)
                    if (explicitAi) {
                        if (end < targetIds.size) {
                            store.savePendingAiTracks(targetIds.subList(end, targetIds.size))
                        } else {
                            store.clearPendingAiTracks()
                        }
                    }
                    curPhase = "Classifying songs with AI"
                    curDone = end
                    publish()

                    if (resolved.providerId == "gemini" && end < targetTracks.size) {
                        val rem = geminiPaceMs - (System.currentTimeMillis() - batchStart)
                        if (rem > 0L) {
                            try {
                                delay(rem)
                            } catch (_: CancellationException) {
                                curMessage = "Stopped"
                                publish()
                                return
                            }
                        }
                    }
                }
            } else if (runMode == "ai-resume") {
                store.clearPendingAiTracks()
            }
        } else {
            setPhase("Matching songs by artist", 0)
            if (applyGenres()) publish()
        }

        // 3) Online lookup (MusicBrainz + Deezer), once per artist that still has songs without genres
        if (!settings.enableOnlineFeatures && !explicitAi) {
            applyGenres()
            publish()
            return
        }

        tracks = store.getAllTracks(includeHidden = true)
        data class ArtistJob(val key: String, val name: String, val sampleTitle: String)
        val seenArtists = HashSet<String>()
        val jobs = mutableListOf<ArtistJob>()
        val cutoff = System.currentTimeMillis() / 1000L - 30L * 86400L

        for (t in tracks) {
            if (t.genre.isNotEmpty()) continue
            for ((k, name) in primaryArtistKeys(t.artist)) {
                if (!seenArtists.add(k)) continue
                val (gs, at, ok) = store.getArtistGenres(k)
                if (ok && (gs.isNotEmpty() || at > cutoff)) continue
                jobs.add(ArtistJob(k, name, t.title))
            }
        }

        var failures = 0
        if (jobs.isNotEmpty()) {
            setPhase("Looking up artists online", jobs.size)
        }
        for ((i, j) in jobs.withIndex()) {
            try {
                coroutineScope { coroutineContext.ensureActive() }
                val res = lookupArtistGenres(j.name, j.sampleTitle)
                if (res.failed && res.genres.isEmpty()) {
                    failures++
                    if (failures >= 5) {
                        curMessage = "Could not reach genre services (MusicBrainz/Deezer). Check your connection and try again."
                        break
                    }
                } else {
                    failures = 0
                    store.setArtistGenres(j.key, res.genres, res.source)
                }
            } catch (_: CancellationException) {
                curMessage = "Stopped"
                publish()
                return
            }
            curDone = i + 1
            if ((i + 1) % 8 == 0) {
                if (applyGenres()) publish() else refreshStatus()
            }
        }

        setPhase("Finishing", 0)
        applyGenres()
        if (failures < 5) {
            store.markUncheckedAsNone()
        }
        val fin = refreshStatus()
        if (curMessage.isEmpty()) {
            curMessage = buildString {
                append("${fin.identified} songs have genres")
                if (fin.unidentified > 0) {
                    append("; ${fin.unidentified} unidentified — edit from the track menu")
                }
            }
        }
        publish()
    }

    private fun primaryArtistKeys(artist: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (a in CanonicalKey.splitArtists(artist)) {
            if (a.equals("Unknown Artist", ignoreCase = true)) continue
            out.add(CanonicalKey.normName(a) to a)
            if (out.size == 2) break
        }
        return out
    }

    private fun applyGenres(): Boolean {
        val tracks = store.getAllTracks(includeHidden = true)
        if (tracks.isEmpty()) return false

        val counts = HashMap<String, MutableMap<String, Int>>()
        val aiCounts = HashMap<String, MutableMap<String, Int>>()
        for (t in tracks) {
            if (t.genre.isEmpty()) continue
            val target = when (t.genreSource) {
                "tag", "user" -> counts
                "ai" -> aiCounts
                else -> continue
            }
            for (a in CanonicalKey.splitArtists(t.artist)) {
                val k = CanonicalKey.normName(a)
                val gm = target.getOrPut(k) { HashMap() }
                for (gg in t.genres) {
                    gm[gg] = (gm[gg] ?: 0) + 1
                }
            }
        }

        fun dominantFrom(srcMap: Map<String, Map<String, Int>>, k: String): List<String> {
            val m = srcMap[k] ?: return emptyList()
            val sorted = m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            if (sorted.isEmpty()) return emptyList()
            val top = sorted.first().value
            return GenreTaxonomy.dedupeGenres(
                sorted.filter { it.value >= top * 0.5 }.map { it.key },
                3
            )
        }

        val groups = LinkedHashMap<Pair<String, String>, MutableList<String>>()
        for (t in tracks) {
            if (t.genreSource == "user" || t.genreSource == "tag" || t.genreSource == "ai") continue
            var base = emptyList<String>()
            var src = ""
            for ((k, _) in primaryArtistKeys(t.artist)) {
                val d = dominantFrom(counts, k)
                if (d.isNotEmpty()) {
                    base = d
                    src = "inferred"
                    break
                }
                val (ag, _, ok) = store.getArtistGenres(k)
                if (ok && ag.isNotEmpty()) {
                    base = ag
                    src = "online"
                    break
                }
                val aiD = dominantFrom(aiCounts, k)
                if (aiD.isNotEmpty()) {
                    base = aiD
                    src = "inferred"
                    break
                }
            }
            val finalList = GenreTaxonomy.dedupeGenres(base + GenreTaxonomy.heuristicGenres(t), 5)
            if (finalList.isEmpty()) continue
            if (src.isEmpty()) src = "heuristic"
            val joined = GenreTaxonomy.joinGenres(finalList)
            if (joined == t.genre && src == t.genreSource) continue
            groups.getOrPut(joined to src) { mutableListOf() }.add(t.id)
        }

        var changed = false
        for ((key, ids) in groups) {
            store.setTracksGenre(ids, key.first, key.second)
            changed = true
        }
        return changed
    }

    // ---------- Classic Merged Lookup (MusicBrainz + Deezer) ----------

    private data class MbTag(val count: Int, val name: String)
    private data class LookupResult(val genres: List<String>, val source: String, val failed: Boolean)

    private suspend fun httpGetJson(url: String): JSONObject? {
        repeat(3) { attempt ->
            if (attempt > 0) delay(attempt * 2500L)
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "VibeMusic/0.6 (https://github.com/vibemusic)")
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code == 503 || code == 429 || code >= 500) return@repeat
                if (code != 200) return null
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return JSONObject(body)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (attempt == 2) return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun pickTags(tags: List<MbTag>, maxCount: Int): List<String> {
        val sorted = tags.sortedByDescending { it.count }
        val top = sorted.maxOfOrNull { it.count } ?: 0
        val out = mutableListOf<String>()
        for (t in sorted) {
            if (t.count <= 0) continue
            if (top >= 4 && t.count < top * 0.2) continue
            val g = GenreTaxonomy.mapTag(t.name)
            if (g.isNotEmpty()) out.add(g)
        }
        return GenreTaxonomy.dedupeGenres(out, maxCount)
    }

    private suspend fun mbLookup(artist: String): Pair<List<String>, Boolean> {
        val prev = mbLastMs.get()
        val now = System.currentTimeMillis()
        val delta = now - prev
        if (delta < 1100L) delay(1100L - delta)
        mbLastMs.set(System.currentTimeMillis())

        val q = "artist:\"${artist.replace("\"", "")}\""
        val res = httpGetJson(
            "https://musicbrainz.org/ws/2/artist/?fmt=json&limit=5&query=${URLEncoder.encode(q, "UTF-8")}"
        ) ?: return emptyList<String>() to true

        val want = CanonicalKey.normName(artist)
        val artists = res.optJSONArray("artists") ?: return emptyList<String>() to false
        for (i in 0 until artists.length()) {
            val a = artists.optJSONObject(i) ?: continue
            val score = a.optInt("score", 0)
            val name = a.optString("name", "")
            if (score < 85 || CanonicalKey.normName(name) != want) continue
            val combined = mutableListOf<MbTag>()
            for (field in listOf("genres", "tags")) {
                val arr = a.optJSONArray(field) ?: continue
                for (j in 0 until arr.length()) {
                    val obj = arr.optJSONObject(j) ?: continue
                    combined.add(MbTag(obj.optInt("count", 0), obj.optString("name", "")))
                }
            }
            val picked = pickTags(combined, 4)
            if (picked.isNotEmpty()) return picked to false
        }
        return emptyList<String>() to false
    }

    private suspend fun deezerLookup(artist: String, sampleTitle: String): Pair<List<String>, Boolean> {
        delay(200L)
        val q = "$artist $sampleTitle".trim()
        val res = httpGetJson(
            "https://api.deezer.com/search/track?limit=10&q=${URLEncoder.encode(q, "UTF-8")}"
        ) ?: return emptyList<String>() to true

        val want = CanonicalKey.normName(artist)
        val data = res.optJSONArray("data") ?: return emptyList<String>() to false
        for (i in 0 until data.length()) {
            val d = data.optJSONObject(i) ?: continue
            val dArt = d.optJSONObject("artist")?.optString("name", "").orEmpty()
            val albumId = d.optJSONObject("album")?.optLong("id", 0L) ?: 0L
            if (CanonicalKey.normName(dArt) != want || albumId <= 0L) continue
            delay(200L)
            val albumObj = httpGetJson("https://api.deezer.com/album/$albumId") ?: return emptyList<String>() to true
            val gArr = albumObj.optJSONObject("genres")?.optJSONArray("data") ?: continue
            val out = mutableListOf<String>()
            for (j in 0 until gArr.length()) {
                val gName = gArr.optJSONObject(j)?.optString("name", "").orEmpty()
                val m = GenreTaxonomy.mapTag(gName)
                if (m.isNotEmpty()) out.add(m)
            }
            val deduped = GenreTaxonomy.dedupeGenres(out, 4)
            if (deduped.isNotEmpty()) return deduped to false
        }
        return emptyList<String>() to false
    }

    private fun mergeGenreSources(mb: List<String>, dz: List<String>, maxCount: Int): List<String> {
        val corroborated = mutableListOf<String>()
        for (m in mb) {
            for (d in dz) {
                when {
                    m == d || GenreTaxonomy.isAncestorGenre(d, m) -> corroborated.add(m)
                    GenreTaxonomy.isAncestorGenre(m, d) -> corroborated.add(d)
                }
            }
        }
        return GenreTaxonomy.dedupeGenres(corroborated + mb + dz, maxCount)
    }

    private suspend fun lookupArtistGenres(artist: String, sampleTitle: String): LookupResult {
        val (mbG, mbErr) = mbLookup(artist)
        val (dzG, dzErr) = deezerLookup(artist, sampleTitle)
        val merged = mergeGenreSources(mbG, dzG, 5)
        if (merged.isNotEmpty()) {
            val src = when {
                mbG.isNotEmpty() && dzG.isNotEmpty() -> "musicbrainz+deezer"
                mbG.isNotEmpty() -> "musicbrainz"
                else -> "deezer"
            }
            return LookupResult(merged, src, failed = false)
        }
        return LookupResult(emptyList(), "", failed = mbErr && dzErr)
    }
}
