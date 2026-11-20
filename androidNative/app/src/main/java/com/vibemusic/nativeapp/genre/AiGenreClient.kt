package com.vibemusic.nativeapp.genre

import com.vibemusic.nativeapp.data.AiGenreSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToLong

data class AiProviderPreset(
    val id: String,
    val name: String,
    val kind: String, // "openai" | "gemini" | "anthropic"
    val selfHosted: Boolean = false,
    val needsKey: Boolean = false,
    val defaultBaseUrl: String = "",
    val defaultModel: String = "",
    val note: String = ""
)

data class TrackGenreInput(
    val index: Int,
    val artist: String,
    val title: String,
    val album: String = "",
    val year: Int = 0,
    val hints: String = ""
)

data class TrackGenreOutput(
    val index: Int,
    val genres: List<String>,
    val custom: List<String> = emptyList()
)

data class AiTestResult(
    val ok: Boolean,
    val message: String,
    val latencyMs: Long = 0L
)

class AiProviderException(
    val statusCode: Int,
    val kind: String, // "auth" | "quota" | "rate_limit" | "transient" | "bad_request" | "network"
    override val message: String,
    val retryAfterMs: Long = 0L
) : Exception(message)

object AiGenreClient {

    val presets: List<AiProviderPreset> = listOf(
        // Self-hosted servers (no built-in model download on mobile)
        AiProviderPreset(
            id = "ollama",
            name = "Ollama (Self-hosted server)",
            kind = "openai",
            selfHosted = true,
            needsKey = false,
            defaultBaseUrl = "http://localhost:11434/v1",
            defaultModel = "qwen2.5:3b",
            note = "Point Base URL to your PC or server running Ollama (e.g. http://192.168.1.x:11434/v1). API key optional."
        ),
        AiProviderPreset(
            id = "lmstudio",
            name = "LM Studio (Self-hosted server)",
            kind = "openai",
            selfHosted = true,
            needsKey = false,
            defaultBaseUrl = "http://localhost:1234/v1",
            defaultModel = "local-model",
            note = "Point Base URL to your PC running LM Studio's server on your network."
        ),
        AiProviderPreset(
            id = "custom",
            name = "Custom Server (OpenAI-compatible)",
            kind = "openai",
            selfHosted = true,
            needsKey = false,
            note = "Any self-hosted or remote OpenAI Chat Completions server (vLLM, llama-server, OpenWebUI, etc.)."
        ),
        // Cloud APIs
        AiProviderPreset(
            id = "gemini",
            name = "Google Gemini",
            kind = "gemini",
            needsKey = true,
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            defaultModel = "gemini-3.5-flash-lite",
            note = "Accepts AI Studio keys (AQ. or AIza). Free tier works with gemini-3.5-flash-lite (15 RPM)."
        ),
        AiProviderPreset(
            id = "openai",
            name = "OpenAI",
            kind = "openai",
            needsKey = true,
            defaultBaseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini"
        ),
        AiProviderPreset(
            id = "anthropic",
            name = "Anthropic (Claude)",
            kind = "anthropic",
            needsKey = true,
            defaultBaseUrl = "https://api.anthropic.com/v1",
            defaultModel = "claude-haiku-4-5"
        ),
        AiProviderPreset(
            id = "openrouter",
            name = "OpenRouter",
            kind = "openai",
            needsKey = true,
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "google/gemini-2.5-flash"
        ),
        AiProviderPreset(
            id = "groq",
            name = "Groq",
            kind = "openai",
            needsKey = true,
            defaultBaseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile"
        ),
        AiProviderPreset(
            id = "mistral",
            name = "Mistral",
            kind = "openai",
            needsKey = true,
            defaultBaseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-small-latest"
        ),
        AiProviderPreset(
            id = "deepseek",
            name = "DeepSeek",
            kind = "openai",
            needsKey = true,
            defaultBaseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat"
        )
    )

    fun findPreset(id: String): AiProviderPreset? = presets.firstOrNull { it.id == id }

    data class ResolvedConfig(
        val providerId: String,
        val kind: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val webSearch: Boolean,
        val geminiGrounding: Boolean
    )

    fun resolveConfig(cfg: AiGenreSettings): ResolvedConfig {
        val id = cfg.provider.trim().ifEmpty { "gemini" }
        val preset = findPreset(id) ?: throw AiProviderException(0, "bad_request", "Unknown AI provider \"$id\"")
        val baseUrl = cfg.baseUrl.trim().trimEnd('/').ifEmpty { preset.defaultBaseUrl.trimEnd('/') }
        val model = cfg.model.trim().ifEmpty { preset.defaultModel }
        val apiKey = cfg.apiKey.trim()

        if (preset.needsKey && apiKey.isEmpty()) {
            throw AiProviderException(0, "auth", "API key is required for ${preset.name}")
        }
        if (baseUrl.isEmpty()) {
            throw AiProviderException(0, "bad_request", "Server Base URL is required for ${preset.name}")
        }
        if (model.isEmpty()) {
            throw AiProviderException(0, "bad_request", "Model name is required for ${preset.name}")
        }
        return ResolvedConfig(
            providerId = preset.id,
            kind = preset.kind,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            webSearch = cfg.webSearch,
            geminiGrounding = cfg.geminiGrounding
        )
    }

    private fun sanitizeError(msg: String, apiKey: String): String {
        var clean = msg.trim()
        if (apiKey.length >= 4) {
            clean = clean.replace(apiKey, "[REDACTED]")
        }
        if (clean.length > 500) {
            clean = clean.substring(0, 500) + "..."
        }
        return clean
    }

    private fun extractApiErrorMessage(body: String): String {
        return try {
            val obj = JSONObject(body)
            val errMsg = obj.optJSONObject("error")?.optString("message", "")?.trim().orEmpty()
            if (errMsg.isNotEmpty()) return errMsg
            val msg = obj.optString("message", "").trim()
            if (msg.isNotEmpty()) return msg
            val detail = obj.optString("detail", "").trim()
            if (detail.isNotEmpty()) return detail
            body.trim()
        } catch (_: Exception) {
            body.trim()
        }
    }

    private val retryInRe = Regex("(?i)retry in (\\d+(?:\\.\\d+)?)s")
    private val durationRe = Regex("^(\\d+(?:\\.\\d+)?)s$")

    private data class GeminiRetryInfo(
        val retryAfterMs: Long = 0L,
        val isDailyQuota: Boolean = false,
        val isZeroLimit: Boolean = false
    )

    private fun parseGeminiRetryInfo(body: String): GeminiRetryInfo {
        return try {
            val err = JSONObject(body).optJSONObject("error") ?: return GeminiRetryInfo()
            var retryAfterMs = 0L
            var isDaily = false
            var isZero = false
            val details = err.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    val d = details.optJSONObject(i) ?: continue
                    val type = d.optString("@type", "")
                    if (type.contains("QuotaFailure")) {
                        val violations = d.optJSONArray("violations")
                        if (violations != null) {
                            for (j in 0 until violations.length()) {
                                val v = violations.optJSONObject(j) ?: continue
                                val qid = v.optString("quotaId", "").lowercase()
                                if (qid.contains("perday") || qid.contains("daily")) isDaily = true
                                if (v.optString("quotaValue", "").trim() == "0") isZero = true
                            }
                        }
                    }
                    if (type.contains("RetryInfo")) {
                        val rd = d.optString("retryDelay", "").trim()
                        val m = durationRe.matchEntire(rd)
                        val secs = m?.groupValues?.get(1)?.toDoubleOrNull()
                        if (secs != null && secs > 0.0) {
                            retryAfterMs = (((secs + 1.0) * 1000.0).roundToLong()).coerceAtMost(75_000L)
                        }
                    }
                }
            }
            if (retryAfterMs == 0L) {
                val msg = err.optString("message", "")
                val m = retryInRe.find(msg)
                val secs = m?.groupValues?.get(1)?.toDoubleOrNull()
                if (secs != null && secs > 0.0) {
                    retryAfterMs = (((secs + 1.0) * 1000.0).roundToLong()).coerceAtMost(75_000L)
                }
            }
            GeminiRetryInfo(retryAfterMs, isDaily, isZero)
        } catch (_: Exception) {
            GeminiRetryInfo()
        }
    }

    private fun classifyHttpFailure(status: Int, retryAfterHeader: String?, body: String, apiKey: String): AiProviderException {
        val detail = sanitizeError(extractApiErrorMessage(body), apiKey)
        val lower = detail.lowercase()
        return when {
            status == 401 || status == 403 -> {
                val msg = buildString {
                    append("Authentication failed (HTTP $status): check your API key")
                    if (detail.isNotEmpty()) append(" — $detail")
                }
                AiProviderException(status, "auth", msg)
            }
            status == 400 && (lower.contains("api_key_invalid") || lower.contains("invalid api key") || lower.contains("api key not valid")) -> {
                AiProviderException(status, "auth", "Invalid API key — $detail")
            }
            status == 429 -> {
                val gem = parseGeminiRetryInfo(body)
                if (gem.isZeroLimit || lower.contains("limit: 0")) {
                    AiProviderException(status, "quota", "Quota exceeded (HTTP 429, limit: 0) — $detail")
                } else if (gem.isDailyQuota) {
                    AiProviderException(status, "quota", "Daily quota exhausted (HTTP 429) — $detail")
                } else {
                    val hdrSec = retryAfterHeader?.trim()?.toIntOrNull()
                    val hdrMs = if (hdrSec != null && hdrSec > 0) (hdrSec * 1000L).coerceAtMost(60_000L) else 0L
                    val waitMs = if (hdrMs > 0L) hdrMs else gem.retryAfterMs
                    val msg = if (detail.isNotEmpty()) "Rate limited (HTTP 429) — $detail" else "Rate limited (HTTP 429)"
                    AiProviderException(status, "rate_limit", msg, waitMs)
                }
            }
            status >= 500 || status == 408 -> {
                val msg = if (detail.isNotEmpty()) "Provider error (HTTP $status) — $detail" else "Provider error (HTTP $status)"
                AiProviderException(status, "transient", msg)
            }
            else -> {
                val msg = if (detail.isNotEmpty()) "Request rejected (HTTP $status) — $detail" else "Request rejected (HTTP $status)"
                AiProviderException(status, "bad_request", msg)
            }
        }
    }

    private data class HttpResult(val status: Int, val retryAfter: String?, val body: String)

    private fun doJsonPost(
        endpoint: String,
        headers: Map<String, String>,
        payload: JSONObject,
        apiKey: String,
        timeoutMs: Int
    ): HttpResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload.toString())
            }
            val status = conn.responseCode
            val retryAfter = conn.getHeaderField("Retry-After")
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResult(status, retryAfter, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw AiProviderException(0, "network", "Request timed out waiting for AI provider response")
        } catch (e: Exception) {
            throw AiProviderException(0, "network", sanitizeError("Network error: ${e.message ?: "connection failed"}", apiKey))
        } finally {
            conn?.disconnect()
        }
    }

    private fun callOpenAI(
        cfg: ResolvedConfig,
        systemPrompt: String,
        userPrompt: String,
        useJsonFormat: Boolean,
        timeoutMs: Int
    ): String {
        val payload = JSONObject().apply {
            put("model", cfg.model)
            put("temperature", 0.1)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
            if (useJsonFormat) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
        }
        val headers = HashMap<String, String>()
        if (cfg.apiKey.isNotEmpty()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
        if (cfg.providerId == "openrouter") {
            headers["HTTP-Referer"] = "https://github.com/vibemusic"
            headers["X-Title"] = "VibeMusic"
        }

        val res = doJsonPost("${cfg.baseUrl}/chat/completions", headers, payload, cfg.apiKey, timeoutMs)
        if (res.status == 400 && useJsonFormat) {
            val lower = res.body.lowercase()
            if (lower.contains("response_format") || lower.contains("json_object")) {
                return callOpenAI(cfg, systemPrompt, userPrompt, false, timeoutMs)
            }
        }
        if (res.status !in 200..299) {
            throw classifyHttpFailure(res.status, res.retryAfter, res.body, cfg.apiKey)
        }
        val root = JSONObject(res.body)
        val choices = root.optJSONArray("choices") ?: throw AiProviderException(0, "bad_request", "Provider returned no choices")
        if (choices.length() == 0) throw AiProviderException(0, "bad_request", "Provider returned no choices")
        return choices.getJSONObject(0).getJSONObject("message").optString("content", "")
    }

    private fun callGemini(
        cfg: ResolvedConfig,
        systemPrompt: String,
        userPrompt: String,
        useThinkingConfig: Boolean,
        useSearch: Boolean,
        useJsonMime: Boolean,
        timeoutMs: Int
    ): String {
        val model = cfg.model.removePrefix("models/")
        val encodedModel = URLEncoder.encode(model, "UTF-8").replace("+", "%20")
        val endpoint = "${cfg.baseUrl}/models/$encodedModel:generateContent"
        val isGemini3 = model.lowercase().startsWith("gemini-3")

        val genCfg = JSONObject().apply {
            put("temperature", 0.1)
            if (useJsonMime) put("responseMimeType", "application/json")
            if (useThinkingConfig && isGemini3) {
                put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
            }
        }
        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            }))
            put("generationConfig", genCfg)
            if (cfg.geminiGrounding && useSearch && isGemini3) {
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
        }
        val headers = HashMap<String, String>()
        if (cfg.apiKey.isNotEmpty()) headers["x-goog-api-key"] = cfg.apiKey

        val res = doJsonPost(endpoint, headers, payload, cfg.apiKey, timeoutMs)
        if (res.status == 400) {
            val lower = res.body.lowercase()
            if (useThinkingConfig && (lower.contains("thinkingconfig") || lower.contains("thinkinglevel"))) {
                return callGemini(cfg, systemPrompt, userPrompt, false, useSearch, useJsonMime, timeoutMs)
            }
            if (useSearch && useJsonMime && (lower.contains("json") || lower.contains("mime") || lower.contains("tool") || lower.contains("google_search"))) {
                return callGemini(cfg, systemPrompt, userPrompt, useThinkingConfig, true, false, timeoutMs)
            }
            if (useSearch && (lower.contains("google_search") || lower.contains("tool"))) {
                return callGemini(cfg, systemPrompt, userPrompt, useThinkingConfig, false, true, timeoutMs)
            }
        }
        if (cfg.geminiGrounding && useSearch) {
            val lower = res.body.lowercase()
            val gem = parseGeminiRetryInfo(res.body)
            if ((res.status == 429 && (gem.isZeroLimit || lower.contains("limit: 0") || lower.contains("search") || lower.contains("grounding"))) ||
                (res.status == 403 && (lower.contains("search") || lower.contains("grounding")))
            ) {
                return callGemini(cfg, systemPrompt, userPrompt, useThinkingConfig, false, true, timeoutMs)
            }
        }
        if (res.status !in 200..299) {
            throw classifyHttpFailure(res.status, res.retryAfter, res.body, cfg.apiKey)
        }

        val root = JSONObject(res.body)
        val candidates = root.optJSONArray("candidates") ?: throw AiProviderException(0, "bad_request", "Gemini returned no candidates")
        if (candidates.length() == 0) throw AiProviderException(0, "bad_request", "Gemini returned no candidates")
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        return sb.toString()
    }

    private fun callAnthropic(
        cfg: ResolvedConfig,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Int
    ): String {
        val payload = JSONObject().apply {
            put("model", cfg.model)
            put("max_tokens", 2048)
            put("temperature", 0.1)
            put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            }))
        }
        val headers = HashMap<String, String>()
        headers["anthropic-version"] = "2023-06-01"
        if (cfg.apiKey.isNotEmpty()) headers["x-api-key"] = cfg.apiKey

        val res = doJsonPost("${cfg.baseUrl}/messages", headers, payload, cfg.apiKey, timeoutMs)
        if (res.status !in 200..299) {
            throw classifyHttpFailure(res.status, res.retryAfter, res.body, cfg.apiKey)
        }
        val content = JSONObject(res.body).optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            if (c.optString("type") == "text") sb.append(c.optString("text", ""))
        }
        return sb.toString()
    }

    suspend fun classifyBatch(
        cfg: ResolvedConfig,
        systemPrompt: String,
        userPrompt: String,
        noRetry: Boolean = false,
        timeoutMs: Int = 90_000,
        onRetryWait: ((waitMs: Long, reason: String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val invokeOnce: () -> String = {
            when (cfg.kind) {
                "openai" -> callOpenAI(cfg, systemPrompt, userPrompt, true, timeoutMs)
                "gemini" -> callGemini(cfg, systemPrompt, userPrompt, true, true, true, timeoutMs)
                "anthropic" -> callAnthropic(cfg, systemPrompt, userPrompt, timeoutMs)
                else -> throw AiProviderException(0, "bad_request", "Unsupported AI adapter \"${cfg.kind}\"")
            }
        }
        if (noRetry) return@withContext invokeOnce()

        val rateWaits = longArrayOf(4_000L, 14_000L, 30_000L, 62_000L)
        val transientWaits = longArrayOf(2_000L, 4_000L, 8_000L, 14_000L, 20_000L)
        var rateAttempt = 0
        var transAttempt = 0

        while (true) {
            try {
                return@withContext invokeOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (ae: AiProviderException) {
                when (ae.kind) {
                    "auth", "quota", "bad_request" -> throw ae
                    "rate_limit" -> {
                        if (rateAttempt >= rateWaits.size) throw ae
                        val waitMs = if (ae.retryAfterMs > 0L) ae.retryAfterMs else rateWaits[rateAttempt]
                        rateAttempt++
                        onRetryWait?.invoke(waitMs, ae.message)
                        delay(waitMs)
                    }
                    "transient", "network" -> {
                        if (transAttempt >= transientWaits.size) throw ae
                        val waitMs = transientWaits[transAttempt++]
                        onRetryWait?.invoke(waitMs, ae.message)
                        delay(waitMs)
                    }
                    else -> throw ae
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ""
    }

    // ---------- Prompt, Parsing & Validation ----------

    fun buildGenreSystemPrompt(allowCustom: Boolean): String {
        val sb = StringBuilder()
        sb.append("You are a music genre classifier. Classify each song individually using its artist, title, album, year, and any \"hints\" field.\n")
        sb.append("Read \"hints\" (YouTube title/tags/description, iTunes genre, Wikipedia summary, MusicBrainz tags) as primary factual evidence while ignoring YouTube promo spam.\n")
        sb.append("Different tracks by the same artist or composer may have different genres (for example an orchestral boss theme vs a vocal pop/rock track).\n\n")
        sb.append("Allowed genre taxonomy (use exact names):\n")
        sb.append(GenreTaxonomy.formatGenreTreeForPrompt())
        sb.append("\nRules:\n")
        sb.append("1. Assign up to 5 accurate sub-genres/styles/contexts per song (aim for 2 to 5 whenever multiple genuinely apply, do not pad with unrelated tags).\n")
        sb.append("2. Combine BOTH media/origin context AND musical style when applicable: anime songs -> [\"Anime\", \"J-Rock\", \"Math Rock\"] or [\"Anime\", \"J-Pop\"]; game score -> [\"Video Game Music\", \"Orchestral\"]; game vocal pop/rock -> [\"Video Game Music\", \"J-Pop\"] or [\"Video Game Music\", \"Pop Rock\"].\n")
        sb.append("3. Prefer specific/regional sub-genres (\"J-Rock\", \"J-Pop\", \"City Pop\", \"K-Pop\", \"Turkish Pop\", \"Turkish Hip-Hop\", \"Soft Pop\", \"Ballad\", \"Vocaloid\", \"Future Bass\") instead of generic \"Rock\", \"Pop\", or \"Alternative Rock\" when accurate.\n")
        sb.append("4. Do NOT tag vocal pop, soft pop, ballad, or indie tracks as \"Electronic\" just for modern synths/production — use \"Electronic\" (or Synthpop/EDM/Future Bass) only when electronic synthesis or beats define the track.\n")
        sb.append("5. Do NOT include broad parent genres when a more specific sub-genre fits (pick \"J-Rock\" instead of \"Rock\"; \"Anime\" or \"Video Game Music\" instead of \"Soundtrack\" — parents are implied automatically).\n")
        sb.append("6. If you do not recognize the song/artist and \"hints\" does not identify it, return \"genres\": [] instead of guessing.\n")
        if (allowCustom) {
            sb.append("7. Use exact names from the taxonomy in \"genres\". Only if an important genre is completely missing from the taxonomy, put it in \"custom\" (max 2 short names).\n")
            sb.append("8. Reply ONLY with valid JSON: {\"results\":[{\"i\":1,\"genres\":[\"...\"],\"custom\":[]}]}\n")
        } else {
            sb.append("7. Only use exact names from the taxonomy above; do not invent new genre names.\n")
            sb.append("8. Reply ONLY with valid JSON: {\"results\":[{\"i\":1,\"genres\":[\"...\"]}]}\n")
        }
        return sb.toString()
    }

    fun buildGenreUserPrompt(items: List<TrackGenreInput>): String {
        val arr = JSONArray()
        for (it in items) {
            val obj = JSONObject().apply {
                put("i", it.index)
                put("artist", it.artist)
                put("title", it.title)
                if (it.album.isNotEmpty()) put("album", it.album)
                if (it.year > 0) put("year", it.year)
                if (it.hints.isNotEmpty()) put("hints", it.hints)
            }
            arr.put(obj)
        }
        return "Classify these songs:\n$arr"
    }

    private fun stripMarkdownFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val nl = s.indexOf('\n')
            s = if (nl >= 0) s.substring(nl + 1) else s.removePrefix("```")
            val end = s.lastIndexOf("```")
            if (end >= 0) s = s.substring(0, end)
        }
        return s.trim()
    }

    private fun parseArray(arr: JSONArray): List<TrackGenreOutput> {
        val out = mutableListOf<TrackGenreOutput>()
        for (k in 0 until arr.length()) {
            val item = arr.optJSONObject(k) ?: continue
            val idx = item.optInt("i", 0)
            val genres = mutableListOf<String>()
            item.optJSONArray("genres")?.let { gArr ->
                for (m in 0 until gArr.length()) {
                    val g = gArr.optString(m, "")
                    if (g.isNotEmpty()) genres.add(g)
                }
            }
            val custom = mutableListOf<String>()
            item.optJSONArray("custom")?.let { cArr ->
                for (m in 0 until cArr.length()) {
                    val c = cArr.optString(m, "")
                    if (c.isNotEmpty()) custom.add(c)
                }
            }
            out.add(TrackGenreOutput(idx, genres, custom))
        }
        return out
    }

    fun parseAiResponse(raw: String): List<TrackGenreOutput> {
        val s = stripMarkdownFences(raw)
        if (s.isEmpty()) throw IllegalStateException("Empty AI response")

        fun tryParse(candidate: String): List<TrackGenreOutput>? {
            val trimmed = candidate.trim()
            try {
                if (trimmed.startsWith("[")) {
                    return parseArray(JSONArray(trimmed))
                }
                if (trimmed.startsWith("{")) {
                    val obj = JSONObject(trimmed)
                    for (key in listOf("results", "tracks", "items", "songs")) {
                        val arr = obj.optJSONArray(key)
                        if (arr != null) return parseArray(arr)
                    }
                }
            } catch (_: Exception) {
            }
            return null
        }

        tryParse(s)?.let { return it }
        val objStart = s.indexOf('{')
        val objEnd = s.lastIndexOf('}')
        val arrStart = s.indexOf('[')
        val arrEnd = s.lastIndexOf(']')
        if (objStart >= 0 && objEnd > objStart && (arrStart < 0 || objStart < arrStart)) {
            tryParse(s.substring(objStart, objEnd + 1))?.let { return it }
        }
        if (arrStart >= 0 && arrEnd > arrStart) {
            tryParse(s.substring(arrStart, arrEnd + 1))?.let { return it }
        }
        throw IllegalStateException("Could not parse JSON genre results from AI response")
    }

    private val customStopwords = setOf("unknown", "none", "other", "n/a", "na", "music", "various", "misc", "genre", "song")

    fun validateAiGenres(out: TrackGenreOutput, allowCustom: Boolean): List<String> {
        val valid = mutableListOf<String>()
        for (g in out.genres) {
            val canon = GenreTaxonomy.canonicalGenreName(g)
            if (canon.isNotEmpty()) valid.add(canon)
        }
        if (allowCustom) {
            var customAdded = 0
            for (rawC in out.custom) {
                val c = rawC.trim()
                if (c.isEmpty()) continue
                val canon = GenreTaxonomy.canonicalGenreName(c)
                if (canon.isNotEmpty()) {
                    valid.add(canon)
                    continue
                }
                if (customAdded >= 2 || c.length < 2 || c.length > 40 || customStopwords.contains(c.lowercase())) {
                    continue
                }
                valid.add(c)
                customAdded++
            }
        }
        return GenreTaxonomy.dedupeGenres(valid, 5)
    }

    suspend fun testConnection(settings: AiGenreSettings): AiTestResult {
        val resolved = try {
            resolveConfig(settings).copy(webSearch = false, geminiGrounding = false)
        } catch (e: Exception) {
            return AiTestResult(false, e.message ?: "Invalid configuration", 0L)
        }
        val start = System.currentTimeMillis()
        return try {
            val sys = buildGenreSystemPrompt(false)
            val usr = buildGenreUserPrompt(
                listOf(TrackGenreInput(1, "Metallica", "Master of Puppets", "Master of Puppets"))
            )
            val raw = classifyBatch(resolved, sys, usr, noRetry = true, timeoutMs = 45_000)
            val elapsed = System.currentTimeMillis() - start
            val parsed = parseAiResponse(raw)
            if (parsed.isEmpty()) {
                AiTestResult(false, "Connected, but model returned empty results", elapsed)
            } else {
                AiTestResult(true, "Connected (${elapsed} ms)", elapsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            AiTestResult(false, e.message ?: "Connection failed", elapsed)
        }
    }
}
