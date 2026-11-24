package com.vibemusic.nativeapp.sync

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.vibemusic.nativeapp.data.CanonicalKey
import com.vibemusic.nativeapp.data.Track
import com.vibemusic.nativeapp.data.VibeStore
import com.vibemusic.nativeapp.scanner.LibraryScanner
import com.vibemusic.nativeapp.ui.CoverLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SyncManager(
    private val context: Context,
    private val store: VibeStore,
    private val scanner: LibraryScanner
) {
    private class SyncSession(
        val id: String,
        val peerDeviceId: String,
        val peerName: String,
        val peerPlatform: String,
        val peerPubSpki: String,
        val peerPubKey: PublicKey,
        val sessionKey: ByteArray,
        val authKey: ByteArray,
        val transcript: String,
        @Volatile var authenticated: Boolean = false,
        val seenNonces: MutableSet<String> = HashSet()
    )

    private class PendingPairEntry(
        val pairId: String,
        val initDeviceId: String,
        val initDeviceName: String,
        val initPlatform: String,
        val initPubSpki: String,
        val initPubKey: PublicKey,
        val transcript: String,
        val sessionKey: ByteArray,
        val authKey: ByteArray,
        val pin: String,
        @Volatile var status: String = "pending",
        @Volatile var sessionId: String = "",
        @Volatile var trustPeer: Boolean = true,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity: SyncIdentity = loadOrCreateIdentity()
    private val sessions = ConcurrentHashMap<String, SyncSession>()

    @Volatile private var pendingPair: PendingPairEntry? = null
    @Volatile private var lastDeclinedMs: Long = 0L
    @Volatile private var discoveryActive: Boolean = false
    @Volatile private var syncBusy: Boolean = false
    @Volatile private var activeSyncJob: Job? = null
    @Volatile private var reverseSignal: Pair<String, CompletableDeferred<Boolean>>? = null
    @Volatile private var quickSyncPending: Boolean = false
    @Volatile private var quickSyncDeletions: Boolean = true

    private val _status = MutableStateFlow(SyncStatus(state = "idle"))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<SyncPeer>>(emptyList())
    val peers: StateFlow<List<SyncPeer>> = _peers.asStateFlow()

    private val _pairPrompt = MutableStateFlow<SyncPairPrompt?>(null)
    val pairPrompt: StateFlow<SyncPairPrompt?> = _pairPrompt.asStateFlow()

    var onSyncCompleted: (() -> Unit)? = null

    private val server = SyncHttpServer { req -> handleHttpRequest(req) }

    private val discovery = SyncDiscovery(
        context = context,
        onPeersChanged = { list ->
            _peers.value = list
            if (quickSyncPending && !syncBusy) {
                val trusted = list.firstOrNull { it.trusted }
                if (trusted != null) {
                    quickSyncPending = false
                    startPeerSync(trusted.deviceId, quickSyncDeletions)
                }
            }
        },
        onReverseRequest = { remoteIp, port, devId, devName, platform, spki, syncDel ->
            handleReverseRequest(remoteIp, port, devId, devName, platform, spki, syncDel)
        },
        buildSelfSummary = {
            val tracks = store.getAllTracks(includeHidden = true)
            val pls = store.getPlaylists()
            SyncDiscovery.SyncSelfSummary(
                deviceId = identity.deviceId,
                deviceName = store.getSyncDeviceName(),
                port = server.port,
                trackCount = tracks.size,
                playlistCount = pls.size,
                pubKeySpkiB64 = identity.pubSpkiB64
            )
        },
        isPeerTrusted = { id -> store.getTrustedSyncPeer(id) != null }
    )

    private fun loadOrCreateIdentity(): SyncIdentity {
        val stored = store.getSyncSetting("sync_identity_pkcs8")
        SyncCrypto.parseStoredIdentity(stored)?.let { return it }
        val (created, combined) = SyncCrypto.createIdentity()
        store.setSyncSetting("sync_identity_pkcs8", combined)
        return created
    }

    fun getSelfInfo(): SyncSelfInfo {
        val (ips, _) = discovery.localIPv4sAndBroadcasts()
        val tracks = store.getAllTracks(includeHidden = true)
        val pls = store.getPlaylists()
        return SyncSelfInfo(
            deviceId = identity.deviceId,
            deviceName = store.getSyncDeviceName(),
            port = server.port,
            localIps = ips,
            trackCount = tracks.size,
            playlistCount = pls.size
        )
    }

    fun startDiscovery(autoQuickSync: Boolean = false, syncDeletions: Boolean = true) {
        discoveryActive = true
        quickSyncPending = autoQuickSync
        quickSyncDeletions = syncDeletions
        scope.launch {
            server.start()
            discovery.start()
            if (_status.value.state == "idle") {
                _status.value = SyncStatus(
                    state = "discovering",
                    phase = if (autoQuickSync) "Looking for your trusted PC to sync…" else "Listening for nearby VibeMusic devices…"
                )
            }
            if (autoQuickSync) {
                val trusted = discovery.getPeers().firstOrNull { it.trusted }
                if (trusted != null && !syncBusy) {
                    quickSyncPending = false
                    startPeerSync(trusted.deviceId, syncDeletions)
                }
            }
        }
    }

    fun stopDiscovery() {
        discoveryActive = false
        quickSyncPending = false
        discovery.stop()
        if (!syncBusy) {
            server.stop()
            _status.value = SyncStatus(state = "idle")
        }
    }

    fun cancelSync() {
        quickSyncPending = false
        activeSyncJob?.cancel()
        activeSyncJob = null
        val p = pendingPair
        if (p != null && p.status == "pending") {
            p.status = "declined"
            _pairPrompt.value = null
        }
        scanner.isPaused = false
        syncBusy = false
        SyncService.stop(context)
        _status.value = if (discoveryActive) {
            SyncStatus(state = "discovering", phase = "Sync cancelled")
        } else {
            SyncStatus(state = "idle")
        }
    }

    fun respondPair(pairId: String, accept: Boolean, trust: Boolean) {
        val p = pendingPair ?: return
        if (p.pairId != pairId || p.status != "pending") return
        if (accept) {
            val sessId = SyncCrypto.randomHex(16)
            p.sessionId = sessId
            p.trustPeer = trust
            p.status = "accepted"
            sessions[sessId] = SyncSession(
                id = sessId,
                peerDeviceId = p.initDeviceId,
                peerName = p.initDeviceName,
                peerPlatform = p.initPlatform,
                peerPubSpki = p.initPubSpki,
                peerPubKey = p.initPubKey,
                sessionKey = p.sessionKey,
                authKey = p.authKey,
                transcript = p.transcript
            )
        } else {
            p.status = "declined"
            lastDeclinedMs = System.currentTimeMillis()
        }
        _pairPrompt.value = null
    }

    fun renameDevice(name: String) {
        if (name.trim().isNotEmpty()) {
            store.setSyncDeviceName(name.trim())
            discovery.announceNow()
        }
    }

    fun removeTrustedPeer(deviceId: String) {
        store.deleteTrustedSyncPeer(deviceId)
        discovery.refreshTrustFlags()
    }

    suspend fun connectManualIp(ipPortRaw: String): Result<SyncPeer> = withContext(Dispatchers.IO) {
        val raw = ipPortRaw.trim()
        if (raw.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Enter an IP address"))
        val (host, port) = if (raw.contains(":")) {
            val idx = raw.lastIndexOf(':')
            val h = raw.substring(0, idx).trim()
            val p = raw.substring(idx + 1).trim().toIntOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid port"))
            h to p
        } else {
            raw to SyncConstants.DEFAULT_PORT
        }
        try {
            val info = fetchPeerInfo(host, port)
            val devId = info.optString("deviceId", "")
            val spki = info.optString("pubKeySpkiB64", "")
            if (devId == identity.deviceId) {
                return@withContext Result.failure(IllegalArgumentException("Cannot connect to this device"))
            }
            SyncCrypto.verifyDeviceIdAndKey(devId, spki)
            val peer = SyncPeer(
                deviceId = devId,
                deviceName = info.optString("deviceName", "VibeMusic Device"),
                platform = info.optString("platform", ""),
                ip = host,
                port = port,
                trackCount = info.optInt("trackCount", 0),
                playlistCount = info.optInt("playlistCount", 0),
                pubKeySpkiB64 = spki,
                trusted = store.getTrustedSyncPeer(devId) != null,
                lastSeen = System.currentTimeMillis() / 1000L
            )
            discovery.upsertManualPeer(peer)
            Result.success(peer)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun handleReverseRequest(
        remoteIp: String,
        port: Int,
        devId: String,
        devName: String,
        platform: String,
        spki: String,
        syncDel: Boolean
    ) {
        if (!discoveryActive || syncBusy) return
        if (store.getTrustedSyncPeer(devId) == null) return
        discovery.upsertManualPeer(
            SyncPeer(
                deviceId = devId,
                deviceName = devName,
                platform = platform,
                ip = remoteIp,
                port = port,
                trackCount = 0,
                playlistCount = 0,
                pubKeySpkiB64 = spki,
                trusted = true,
                lastSeen = System.currentTimeMillis() / 1000L
            )
        )
        startPeerSync(devId, syncDel)
    }

    fun startPeerSync(deviceId: String, syncDeletions: Boolean): String {
        val target = discovery.getPeers().firstOrNull { it.deviceId == deviceId }
            ?: return "Device is no longer visible on the local network"
        if (syncBusy) return "A sync is already in progress"
        syncBusy = true
        quickSyncPending = false
        SyncService.start(context)

        activeSyncJob = scope.launch {
            try {
                runOutboundSync(target, syncDeletions)
            } catch (e: Exception) {
                if (!isActive) {
                    _status.value = SyncStatus(state = "idle", phase = "Cancelled")
                } else {
                    Log.e("SyncManager", "Outbound sync failed", e)
                    _status.value = SyncStatus(
                        state = "error",
                        phase = "Sync failed",
                        peerId = target.deviceId,
                        peerName = target.deviceName,
                        error = e.message ?: "Unknown error"
                    )
                }
            } finally {
                scanner.isPaused = false
                syncBusy = false
                activeSyncJob = null
                if (!discoveryActive) {
                    server.stop()
                }
                SyncService.stop(context)
            }
        }
        return ""
    }

    // ---------- Incoming HTTP Server Handlers ----------

    private fun handleHttpRequest(req: SyncHttpServer.RequestContext) {
        val path = req.path
        when {
            path == "/api/sync/v1/info" && req.method == "GET" -> {
                val info = getSelfInfo()
                req.sendJson(200, JSONObject().apply {
                    put("protocolVersion", SyncConstants.PROTOCOL_VERSION)
                    put("deviceId", identity.deviceId)
                    put("deviceName", info.deviceName)
                    put("platform", "android")
                    put("port", info.port)
                    put("trackCount", info.trackCount)
                    put("playlistCount", info.playlistCount)
                    put("pubKeySpkiB64", identity.pubSpkiB64)
                })
            }
            path == "/api/sync/v1/auth-init" && req.method == "POST" -> handleAuthInit(req)
            path == "/api/sync/v1/auth-finish" && req.method == "POST" -> handleAuthFinish(req)
            path == "/api/sync/v1/pair-init" && req.method == "POST" -> handlePairInit(req)
            path == "/api/sync/v1/pair-status" && req.method == "POST" -> handlePairStatus(req)
            path == "/api/sync/v1/manifest" && req.method == "POST" -> handleManifest(req)
            path.startsWith("/api/sync/v1/file/") -> handleFile(req)
            path == "/api/sync/v1/progress" && req.method == "POST" -> handleProgressPing(req)
            path == "/api/sync/v1/commit" && req.method == "POST" -> handleCommit(req)
            else -> req.sendError(404, "not found")
        }
    }

    private fun handleAuthInit(req: SyncHttpServer.RequestContext) {
        val body = JSONObject(String(req.readAllBody(64 * 1024), Charsets.UTF_8))
        val initId = body.optString("initDeviceId", "")
        val initName = body.optString("initDeviceName", "")
        val initPlatform = body.optString("initPlatform", "")
        val initSpki = body.optString("initPubSpkiB64", "")
        val initEph = body.optString("initEphSpkiB64", "")
        val initNonce = body.optString("initNonceHex", "")

        val peerPub = try {
            SyncCrypto.verifyDeviceIdAndKey(initId, initSpki)
        } catch (e: Exception) {
            req.sendError(400, e.message ?: "bad key")
            return
        }
        val trusted = store.getTrustedSyncPeer(initId)
        if (trusted == null || trusted.pubKeySpkiB64 != initSpki) {
            req.sendError(403, "untrusted")
            return
        }

        val (ephPriv, ephSpki) = SyncCrypto.generateEphemeralKey()
        val shared = SyncCrypto.computeEcdhSharedSecret(ephPriv, initEph)
        val respNonce = SyncCrypto.randomHex(32)
        val keys = SyncCrypto.deriveSessionKeys(shared, initNonce, respNonce)
        val selfName = store.getSyncDeviceName()
        val transcript = SyncCrypto.buildTranscript(
            initId, identity.deviceId, initSpki, identity.pubSpkiB64, initEph, ephSpki, initNonce, respNonce
        )
        val respSig = SyncCrypto.signMessage(identity.privateKey, ("resp-sig|$transcript").toByteArray(Charsets.UTF_8))
        val sessId = SyncCrypto.randomHex(16)
        sessions[sessId] = SyncSession(
            id = sessId,
            peerDeviceId = initId,
            peerName = initName,
            peerPlatform = initPlatform,
            peerPubSpki = initSpki,
            peerPubKey = peerPub,
            sessionKey = keys.sessionKey,
            authKey = keys.authKey,
            transcript = transcript
        )

        req.sendJson(200, JSONObject().apply {
            put("sessionId", sessId)
            put("respDeviceId", identity.deviceId)
            put("respDeviceName", selfName)
            put("respPubSpkiB64", identity.pubSpkiB64)
            put("respEphSpkiB64", ephSpki)
            put("respNonceHex", respNonce)
            put("respSigB64", respSig)
        })
    }

    private fun handleAuthFinish(req: SyncHttpServer.RequestContext) {
        val body = JSONObject(String(req.readAllBody(32 * 1024), Charsets.UTF_8))
        val sessId = body.optString("sessionId", "")
        val initSig = body.optString("initSigB64", "")
        val sess = sessions[sessId] ?: return req.sendError(401, "unknown session")
        val msg = ("init-sig|${sess.id}|${sess.transcript}").toByteArray(Charsets.UTF_8)
        if (!SyncCrypto.verifySignature(sess.peerPubKey, msg, initSig)) {
            return req.sendError(401, "invalid signature")
        }
        sess.authenticated = true
        store.upsertTrustedSyncPeer(
            TrustedSyncPeer(
                deviceId = sess.peerDeviceId,
                deviceName = sess.peerName,
                platform = sess.peerPlatform,
                pubKeySpkiB64 = sess.peerPubSpki
            )
        )
        req.sendJson(200, JSONObject().apply { put("ok", true) })
    }

    private fun handlePairInit(req: SyncHttpServer.RequestContext) {
        val now = System.currentTimeMillis()
        if (now - lastDeclinedMs < 3000L) {
            return req.sendError(429, "cooldown")
        }
        val curPending = pendingPair
        if (curPending != null && curPending.status == "pending" && now - curPending.createdAtMs < 60_000L) {
            return req.sendError(429, "pair request already active")
        }

        val body = JSONObject(String(req.readAllBody(64 * 1024), Charsets.UTF_8))
        val initId = body.optString("initDeviceId", "")
        val initName = body.optString("initDeviceName", "")
        val initPlatform = body.optString("initPlatform", "")
        val initTrackCount = body.optInt("initTrackCount", 0)
        val initSpki = body.optString("initPubSpkiB64", "")
        val initEph = body.optString("initEphSpkiB64", "")
        val initNonce = body.optString("initNonceHex", "")

        val peerPub = try {
            SyncCrypto.verifyDeviceIdAndKey(initId, initSpki)
        } catch (e: Exception) {
            return req.sendError(400, e.message ?: "bad key")
        }

        val (ephPriv, ephSpki) = SyncCrypto.generateEphemeralKey()
        val shared = SyncCrypto.computeEcdhSharedSecret(ephPriv, initEph)
        val respNonce = SyncCrypto.randomHex(32)
        val keys = SyncCrypto.deriveSessionKeys(shared, initNonce, respNonce)
        val selfName = store.getSyncDeviceName()
        val transcript = SyncCrypto.buildTranscript(
            initId, identity.deviceId, initSpki, identity.pubSpkiB64, initEph, ephSpki, initNonce, respNonce
        )
        val respSig = SyncCrypto.signMessage(identity.privateKey, ("resp-sig|$transcript").toByteArray(Charsets.UTF_8))
        val pairId = SyncCrypto.randomHex(16)
        val pin = SyncCrypto.computeSasPin(transcript)

        val entry = PendingPairEntry(
            pairId = pairId,
            initDeviceId = initId,
            initDeviceName = initName,
            initPlatform = initPlatform,
            initPubSpki = initSpki,
            initPubKey = peerPub,
            transcript = transcript,
            sessionKey = keys.sessionKey,
            authKey = keys.authKey,
            pin = pin
        )
        pendingPair = entry
        _pairPrompt.value = SyncPairPrompt(
            pairId = pairId,
            deviceId = initId,
            deviceName = initName,
            platform = initPlatform,
            trackCount = initTrackCount,
            pin = pin,
            trustedDefault = true,
            incoming = true
        )

        req.sendJson(200, JSONObject().apply {
            put("pairId", pairId)
            put("respDeviceId", identity.deviceId)
            put("respDeviceName", selfName)
            put("respPlatform", "android")
            put("respPubSpkiB64", identity.pubSpkiB64)
            put("respEphSpkiB64", ephSpki)
            put("respNonceHex", respNonce)
            put("respSigB64", respSig)
        })
    }

    private fun handlePairStatus(req: SyncHttpServer.RequestContext) {
        val body = JSONObject(String(req.readAllBody(32 * 1024), Charsets.UTF_8))
        val pairId = body.optString("pairId", "")
        val initSig = body.optString("initSigB64", "")
        val initTrust = body.optBoolean("trustPeer", true)

        val p = pendingPair
        if (p == null || p.pairId != pairId) {
            return req.sendJson(200, JSONObject().apply { put("status", "expired") })
        }
        if (p.status == "pending" && System.currentTimeMillis() - p.createdAtMs > 60_000L) {
            p.status = "expired"
            lastDeclinedMs = System.currentTimeMillis()
            _pairPrompt.value = null
            return req.sendJson(200, JSONObject().apply { put("status", "expired") })
        }
        if (p.status != "accepted") {
            return req.sendJson(200, JSONObject().apply { put("status", p.status) })
        }
        val msg = ("init-sig|${p.pairId}|${p.transcript}").toByteArray(Charsets.UTF_8)
        if (!SyncCrypto.verifySignature(p.initPubKey, msg, initSig)) {
            return req.sendError(401, "invalid signature")
        }
        sessions[p.sessionId]?.authenticated = true
        if (p.trustPeer && initTrust) {
            store.upsertTrustedSyncPeer(
                TrustedSyncPeer(
                    deviceId = p.initDeviceId,
                    deviceName = p.initDeviceName,
                    platform = p.initPlatform,
                    pubKeySpkiB64 = p.initPubSpki,
                    pairedAt = System.currentTimeMillis() / 1000L
                )
            )
            discovery.refreshTrustFlags()
        }
        req.sendJson(200, JSONObject().apply {
            put("status", "accepted")
            put("sessionId", p.sessionId)
            put("responderTrusted", p.trustPeer)
        })
    }

    private fun authenticate(req: SyncHttpServer.RequestContext): SyncSession? {
        val sessId = req.header("x-sync-session")
        val nonce = req.header("x-sync-nonce")
        val macHex = req.header("x-sync-mac")
        if (sessId.isEmpty() || nonce.isEmpty() || macHex.isEmpty()) return null
        val dash = nonce.indexOf('-')
        if (dash <= 0) return null
        val ts = nonce.substring(0, dash).toLongOrNull() ?: return null
        val now = System.currentTimeMillis() / 1000L
        if (kotlin.math.abs(now - ts) > 300L) return null
        val sess = sessions[sessId] ?: return null
        synchronized(sess) {
            if (!sess.authenticated) return null
            if (sess.seenNonces.contains(nonce)) return null
            if (!SyncCrypto.verifyRequestMAC(sess.authKey, req.method, req.path, sessId, nonce, macHex)) {
                return null
            }
            sess.seenNonces.add(nonce)
            return sess
        }
    }

    private fun handleManifest(req: SyncHttpServer.RequestContext) {
        val sess = authenticate(req) ?: return req.sendError(401, "unauthorized")
        val sealed = req.readAllBody()
        val plain = SyncCrypto.decryptSingleBuffer(sess.sessionKey, sealed)
        // validate JSON parses cleanly
        SyncManifest.fromJson(JSONObject(String(plain, Charsets.UTF_8)))

        scanner.isPaused = true
        syncBusy = true
        SyncService.start(context)
        reverseSignal?.let { (targetId, def) ->
            if (targetId == sess.peerDeviceId) def.complete(true)
        }
        _status.value = SyncStatus(
            state = "syncing",
            phase = "Comparing libraries with ${sess.peerName}…",
            peerId = sess.peerDeviceId,
            peerName = sess.peerName,
            direction = "incoming"
        )

        val settings = store.loadSettings()
        val localMan = store.buildSyncManifest(identity.deviceId, store.getSyncDeviceName(), settings.musicDirs)
        val respSealed = SyncCrypto.encryptSingleBuffer(
            sess.sessionKey,
            localMan.toJson().toString().toByteArray(Charsets.UTF_8)
        )
        req.sendBytes(200, "application/octet-stream", respSealed)
    }

    private fun handleProgressPing(req: SyncHttpServer.RequestContext) {
        val sess = authenticate(req) ?: return req.sendError(401, "unauthorized")
        val plain = SyncCrypto.decryptSingleBuffer(sess.sessionKey, req.readAllBody(64 * 1024))
        val obj = JSONObject(String(plain, Charsets.UTF_8))
        _status.value = SyncStatus(
            state = "syncing",
            phase = obj.optString("phase", "Transferring…"),
            peerId = sess.peerDeviceId,
            peerName = sess.peerName,
            direction = "incoming",
            percent = obj.optInt("percent", 0),
            currentFile = obj.optString("currentFile", ""),
            transferredBytes = obj.optLong("transferredBytes", 0L),
            totalBytes = obj.optLong("totalBytes", 0L),
            filesDone = obj.optInt("filesDone", 0),
            filesTotal = obj.optInt("filesTotal", 0),
            speedBps = obj.optLong("speedBps", 0L)
        )
        req.sendJson(200, JSONObject().apply { put("ok", true) })
    }

    private fun handleFile(req: SyncHttpServer.RequestContext) {
        val sess = authenticate(req) ?: return req.sendError(401, "unauthorized")
        val rest = req.path.removePrefix("/api/sync/v1/file/")
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash >= rest.length - 1) return req.sendError(400, "bad path")
        val kind = rest.substring(0, slash)
        val idB64 = rest.substring(slash + 1)
        val trackId = try {
            String(SyncCrypto.b64UrlDecode(idB64), Charsets.UTF_8)
        } catch (_: Exception) {
            return req.sendError(400, "bad track id")
        }

        if (req.method == "GET") {
            serveEncryptedFile(req, sess, kind, trackId)
        } else if (req.method == "PUT") {
            receiveEncryptedFile(req, sess, kind, trackId)
        } else {
            req.sendError(405, "method not allowed")
        }
    }

    private fun serveEncryptedFile(req: SyncHttpServer.RequestContext, sess: SyncSession, kind: String, trackId: String) {
        val (file, ext) = when (kind) {
            "audio" -> {
                val t = store.getTrackById(trackId) ?: return req.sendError(404, "not found")
                val f = File(t.resolvedPath)
                if (!f.exists() || !f.isFile) return req.sendError(404, "missing audio file")
                val dotExt = f.extension.let { if (it.isNotEmpty()) ".$it" else ".m4a" }
                f to dotExt
            }
            "cover" -> {
                val f = store.getCoverFile(trackId)
                if (!f.exists() || f.length() <= 0L) return req.sendError(404, "no cover")
                f to ".jpg"
            }
            "lyrics" -> {
                val t = store.getTrackById(trackId) ?: return req.sendError(404, "not found")
                val f = store.sidecarLrcFile(t.resolvedPath) ?: return req.sendError(404, "no lyrics")
                f to ".lrc"
            }
            else -> return req.sendError(400, "unknown kind")
        }

        val plainLen = file.length()
        val encLen = SyncCrypto.encryptedStreamSize(plainLen)
        val out = req.sendHeaders(
            200,
            "application/octet-stream",
            encLen,
            mapOf("X-Track-Ext" to ext, "X-Plain-Size" to plainLen.toString())
        )
        FileInputStream(file).use { fis ->
            SyncCrypto.writeEncryptedStream(out, sess.sessionKey, fis)
        }
    }

    private fun receiveEncryptedFile(req: SyncHttpServer.RequestContext, sess: SyncSession, kind: String, trackId: String) {
        val plainSize = req.header("x-plain-size").toLongOrNull() ?: -1L
        when (kind) {
            "audio" -> {
                val metaB64 = req.header("x-track-meta")
                if (metaB64.isEmpty()) return req.sendError(400, "missing track meta")
                val plainMeta = SyncCrypto.decryptSingleBuffer(sess.sessionKey, SyncCrypto.b64Decode(metaB64))
                val meta = SyncTrack.fromJson(JSONObject(String(plainMeta, Charsets.UTF_8))).copy(id = trackId)
                val ext = req.header("x-track-ext")
                saveIncomingAudio(req.bodyStream, sess.sessionKey, meta, ext, plainSize, null)
                req.sendJson(200, JSONObject().apply { put("ok", true) })
            }
            "cover" -> {
                saveIncomingCover(req.bodyStream, sess.sessionKey, trackId, plainSize)
                req.sendJson(200, JSONObject().apply { put("ok", true) })
            }
            "lyrics" -> {
                saveIncomingLyrics(req.bodyStream, sess.sessionKey, trackId, plainSize)
                req.sendJson(200, JSONObject().apply { put("ok", true) })
            }
            else -> req.sendError(400, "unknown kind")
        }
    }

    private fun handleCommit(req: SyncHttpServer.RequestContext) {
        val sess = authenticate(req) ?: return req.sendError(401, "unauthorized")
        val plain = SyncCrypto.decryptSingleBuffer(sess.sessionKey, req.readAllBody())
        val payload = SyncCommitPayload.fromJson(JSONObject(String(plain, Charsets.UTF_8)))
        store.applySyncCommit(payload, sess.peerDeviceId, payload.newSnapshot)

        scanner.isPaused = false
        syncBusy = false
        SyncService.stop(context)
        discovery.refreshTrustFlags()
        _status.value = SyncStatus(
            state = "done",
            phase = "Sync complete",
            peerId = sess.peerDeviceId,
            peerName = sess.peerName,
            direction = "incoming",
            percent = 100,
            deletedTracks = payload.deletedTrackIds.size,
            syncedPlaylists = payload.playlists.size,
            message = "Synced ${payload.tracksMeta.size} songs & ${payload.playlists.size} playlists with ${sess.peerName}"
        )
        onSyncCompleted?.invoke()
        req.sendJson(200, JSONObject().apply { put("ok", true) })
    }

    // ---------- File Destination & Ingestion ----------

    private fun primaryDestDir(): File {
        val settings = store.loadSettings()
        var target = File(settings.downloadDir.ifBlank {
            settings.musicDirs.firstOrNull().orEmpty().ifBlank {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "VibeMusic"
                ).absolutePath
            }
        })
        var canWrite = false
        try {
            target.mkdirs()
            val probe = File(target, ".vmsync_probe_${System.currentTimeMillis()}")
            if (probe.createNewFile()) {
                probe.delete()
                canWrite = true
            }
        } catch (_: Exception) {}

        if (!canWrite) {
            val appMusic = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "Music")
            target = File(appMusic, "VibeMusic").apply { mkdirs() }
            val newDirs = (settings.musicDirs + target.absolutePath).distinct()
            store.saveSettings(settings.copy(downloadDir = target.absolutePath, musicDirs = newDirs))
        }
        return target
    }

    private fun freeBytes(dir: File): Long {
        return try {
            val sf = StatFs(dir.absolutePath)
            sf.availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private fun sanitizeIncomingRelPath(
        relPath: String,
        ytId: String,
        extRaw: String,
        artist: String,
        title: String
    ): String {
        val ext = extRaw.ifBlank { ".m4a" }.let { if (it.startsWith(".")) it else ".$it" }
        var rel = CanonicalKey.cleanRelPath(relPath)
        if (rel == null) {
            val rawName = "${artist.trim()} - ${title.trim()}".trim(' ', '-').ifEmpty { "Synced Track" }
            val safe = rawName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            rel = "$safe$ext"
        }
        if (ytId.isNotEmpty() && CanonicalKey.ytIdFromName(rel) == null) {
            val dot = rel.lastIndexOf('.')
            val slash = rel.lastIndexOf('/')
            rel = if (dot > slash) {
                "${rel.substring(0, dot)} [$ytId]${rel.substring(dot)}"
            } else {
                "$rel [$ytId]$ext"
            }
        }
        return rel
    }

    private fun uniqueFile(target: File): File {
        val parent = target.parentFile ?: return target
        val stem = target.nameWithoutExtension
        val ext = target.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var i = 1
        while (true) {
            val cand = File(parent, "$stem ($i)$ext")
            if (!cand.exists()) return cand
            i++
        }
    }

    private fun saveIncomingAudio(
        input: InputStream,
        sessionKey: ByteArray,
        meta: SyncTrack,
        ext: String,
        expectedSize: Long,
        onBytes: ((Long) -> Unit)?
    ) {
        val destDir = primaryDestDir()
        if (expectedSize > 0L && freeBytes(destDir) < expectedSize + 64L * 1024L * 1024L) {
            throw IllegalStateException("Insufficient storage space on phone")
        }
        var rel = sanitizeIncomingRelPath(meta.relPath, meta.ytId, ext, meta.artist, meta.title)
        var target = File(destDir, rel.replace('/', File.separatorChar))
        if (target.exists()) {
            val existing = store.getTrackByPath(target.absolutePath)
            if ((existing != null && existing.id != meta.id) || (existing == null && target.length() != expectedSize)) {
                target = uniqueFile(target)
                val (_, fixedRel, ok) = store.splitPath(target.absolutePath)
                if (ok) rel = fixedRel
            }
        }
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.vmsync.part")
        val written = try {
            FileOutputStream(part).use { fos ->
                SyncCrypto.readEncryptedStream(input, sessionKey, fos, expectedSize, onBytes)
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }

        if (meta.id.startsWith("local:")) {
            val computed = CanonicalKey.localContentId(part)
            if (computed != meta.id) {
                part.delete()
                throw IllegalStateException("Content hash verification failed for ${meta.title}")
            }
        }
        if (meta.mtimeUnix > 0L) {
            part.setLastModified(meta.mtimeUnix * 1000L)
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }

        val rootKey = store.ensureRoot(destDir.canonicalPath)
        val finalMtime = if (target.lastModified() > 0L) target.lastModified() / 1000L else meta.mtimeUnix
        val coverExists = store.getCoverFile(meta.id).let { it.exists() && it.length() > 0L }
        var effectiveYtId = meta.ytId.ifBlank { null }
        if (effectiveYtId != null) {
            val owner = store.trackByYtId(effectiveYtId)
            if (owner != null && owner.id != meta.id) {
                effectiveYtId = null
            }
        }

        store.upsertTrack(
            Track(
                id = meta.id,
                ytId = effectiveYtId,
                rootKey = rootKey,
                relPath = rel,
                title = meta.title,
                artist = meta.artist,
                album = meta.album,
                duration = meta.duration,
                year = meta.year,
                addedAt = meta.addedAt,
                liked = meta.liked,
                playCount = meta.playCount,
                lastPlayed = meta.lastPlayed,
                hasCover = coverExists || meta.hasCover,
                format = meta.format,
                bitrate = meta.bitrate,
                fileSize = written,
                mtimeUnix = finalMtime,
                updatedAt = meta.updatedAt,
                genre = meta.genre,
                genreSource = meta.genreSource
            )
        )
    }

    private fun saveIncomingCover(input: InputStream, sessionKey: ByteArray, trackId: String, expectedSize: Long) {
        val dst = store.getCoverFile(trackId)
        dst.parentFile?.mkdirs()
        val part = File(dst.parentFile, "${dst.name}.vmsync.part")
        try {
            FileOutputStream(part).use { fos ->
                SyncCrypto.readEncryptedStream(input, sessionKey, fos, expectedSize)
            }
            if (!part.renameTo(dst)) {
                part.copyTo(dst, overwrite = true)
                part.delete()
            }
            CoverLoader.invalidate(trackId)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    private fun saveIncomingLyrics(input: InputStream, sessionKey: ByteArray, trackId: String, expectedSize: Long) {
        val track = store.getTrackById(trackId) ?: throw IllegalStateException("Track missing for lyrics")
        if (track.resolvedPath.isBlank()) throw IllegalStateException("Track path missing for lyrics")
        val audio = File(track.resolvedPath)
        val dst = File(audio.parentFile, "${audio.nameWithoutExtension}.lrc")
        val part = File(dst.parentFile, "${dst.name}.vmsync.part")
        try {
            FileOutputStream(part).use { fos ->
                SyncCrypto.readEncryptedStream(input, sessionKey, fos, expectedSize)
            }
            if (!part.renameTo(dst)) {
                part.copyTo(dst, overwrite = true)
                part.delete()
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    // ---------- Outbound Sync Client Driver ----------

    private fun fetchPeerInfo(ip: String, port: Int): JSONObject {
        val url = URL("http://$ip:$port/api/sync/v1/info")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
        }
        return try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(urlStr: String, body: JSONObject): Pair<Int, JSONObject?> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            val json = if (text.isNotBlank()) try { JSONObject(text) } catch (_: Exception) { null } else null
            code to json
        } finally {
            conn.disconnect()
        }
    }

    private fun postEncryptedBytes(
        baseUrl: String,
        path: String,
        sessId: String,
        authKey: ByteArray,
        body: ByteArray
    ): ByteArray {
        val nonce = "${System.currentTimeMillis() / 1000L}-${SyncCrypto.randomHex(8)}"
        val mac = SyncCrypto.computeRequestMAC(authKey, "POST", path, sessId, nonce)
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 120_000
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Sync-Session", sessId)
            setRequestProperty("X-Sync-Nonce", nonce)
            setRequestProperty("X-Sync-Mac", mac)
        }
        return try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) {
                val errTxt = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IllegalStateException("HTTP $code: $errTxt")
            }
            conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun runOutboundSync(peer: SyncPeer, syncDeletions: Boolean) = coroutineScope {
        _status.value = SyncStatus(
            state = "pairing",
            phase = "Connecting to ${peer.deviceName}…",
            peerId = peer.deviceId,
            peerName = peer.deviceName,
            direction = "outgoing"
        )

        val reachable = try {
            fetchPeerInfo(peer.ip, peer.port)
            true
        } catch (_: Exception) {
            false
        }

        if (!reachable) {
            val def = CompletableDeferred<Boolean>()
            reverseSignal = peer.deviceId to def
            discovery.sendReverseRequest(peer.ip, peer.deviceId, syncDeletions)
            _status.value = SyncStatus(
                state = "pairing",
                phase = "Requesting reverse connection from ${peer.deviceName}…",
                peerId = peer.deviceId,
                peerName = peer.deviceName,
                direction = "outgoing"
            )
            var gotReverse = false
            for (i in 0 until 24) {
                if (def.isCompleted) {
                    gotReverse = true
                    break
                }
                delay(250L)
            }
            reverseSignal = null
            if (gotReverse) {
                return@coroutineScope
            }
            throw IllegalStateException("Cannot reach ${peer.ip}:${peer.port} — check firewall or tap Sync on ${peer.deviceName}")
        }

        val baseUrl = "http://${peer.ip}:${peer.port}"
        val selfName = store.getSyncDeviceName()
        val (ephPriv, ephSpki) = SyncCrypto.generateEphemeralKey()
        val initNonce = SyncCrypto.randomHex(32)

        var sessId = ""
        var sessionKey = ByteArray(0)
        var authKey = ByteArray(0)

        if (store.getTrustedSyncPeer(peer.deviceId) != null) {
            val (code, aResp) = postJson("$baseUrl/api/sync/v1/auth-init", JSONObject().apply {
                put("initDeviceId", identity.deviceId)
                put("initDeviceName", selfName)
                put("initPlatform", "android")
                put("initPubSpkiB64", identity.pubSpkiB64)
                put("initEphSpkiB64", ephSpki)
                put("initNonceHex", initNonce)
            })
            if (code == 200 && aResp != null) {
                val rDevId = aResp.optString("respDeviceId", "")
                val rSpki = aResp.optString("respPubSpkiB64", "")
                val rEph = aResp.optString("respEphSpkiB64", "")
                val rNonce = aResp.optString("respNonceHex", "")
                val rSig = aResp.optString("respSigB64", "")
                val rSessId = aResp.optString("sessionId", "")
                val peerPub = SyncCrypto.verifyDeviceIdAndKey(rDevId, rSpki)
                require(rDevId == peer.deviceId && rSpki == peer.pubKeySpkiB64) { "Peer identity key mismatch" }
                val transcript = SyncCrypto.buildTranscript(
                    identity.deviceId, rDevId, identity.pubSpkiB64, rSpki, ephSpki, rEph, initNonce, rNonce
                )
                require(SyncCrypto.verifySignature(peerPub, ("resp-sig|$transcript").toByteArray(Charsets.UTF_8), rSig)) {
                    "Invalid peer signature"
                }
                val shared = SyncCrypto.computeEcdhSharedSecret(ephPriv, rEph)
                val keys = SyncCrypto.deriveSessionKeys(shared, initNonce, rNonce)
                sessionKey = keys.sessionKey
                authKey = keys.authKey
                val initSig = SyncCrypto.signMessage(identity.privateKey, ("init-sig|$rSessId|$transcript").toByteArray(Charsets.UTF_8))
                val (fCode, _) = postJson("$baseUrl/api/sync/v1/auth-finish", JSONObject().apply {
                    put("sessionId", rSessId)
                    put("initSigB64", initSig)
                })
                if (fCode != 200) throw IllegalStateException("Trusted authentication rejected ($fCode)")
                sessId = rSessId
            } else if (code != 403) {
                throw IllegalStateException("auth-init failed ($code)")
            }
        }

        if (sessId.isEmpty()) {
            val trackCnt = store.getAllTracks(includeHidden = true).size
            val (code, pResp) = postJson("$baseUrl/api/sync/v1/pair-init", JSONObject().apply {
                put("initDeviceId", identity.deviceId)
                put("initDeviceName", selfName)
                put("initPlatform", "android")
                put("initTrackCount", trackCnt)
                put("initPubSpkiB64", identity.pubSpkiB64)
                put("initEphSpkiB64", ephSpki)
                put("initNonceHex", initNonce)
            })
            if (code != 200 || pResp == null) {
                throw IllegalStateException("Pairing rejected ($code)")
            }
            val rDevId = pResp.optString("respDeviceId", "")
            val rSpki = pResp.optString("respPubSpkiB64", "")
            val rEph = pResp.optString("respEphSpkiB64", "")
            val rNonce = pResp.optString("respNonceHex", "")
            val rSig = pResp.optString("respSigB64", "")
            val pairId = pResp.optString("pairId", "")
            val peerPub = SyncCrypto.verifyDeviceIdAndKey(rDevId, rSpki)
            require(rDevId == peer.deviceId) { "Peer pairing key mismatch" }
            val transcript = SyncCrypto.buildTranscript(
                identity.deviceId, rDevId, identity.pubSpkiB64, rSpki, ephSpki, rEph, initNonce, rNonce
            )
            require(SyncCrypto.verifySignature(peerPub, ("resp-sig|$transcript").toByteArray(Charsets.UTF_8), rSig)) {
                "Invalid pairing signature"
            }
            val shared = SyncCrypto.computeEcdhSharedSecret(ephPriv, rEph)
            val keys = SyncCrypto.deriveSessionKeys(shared, initNonce, rNonce)
            sessionKey = keys.sessionKey
            authKey = keys.authKey
            val pin = SyncCrypto.computeSasPin(transcript)

            _status.value = SyncStatus(
                state = "pairing",
                phase = "Confirm PIN on ${peer.deviceName}…",
                peerId = peer.deviceId,
                peerName = peer.deviceName,
                direction = "outgoing",
                pin = pin
            )
            val initSig = SyncCrypto.signMessage(identity.privateKey, ("init-sig|$pairId|$transcript").toByteArray(Charsets.UTF_8))
            val deadline = System.currentTimeMillis() + 65_000L
            while (System.currentTimeMillis() < deadline && isActive) {
                val (sCode, stResp) = postJson("$baseUrl/api/sync/v1/pair-status", JSONObject().apply {
                    put("pairId", pairId)
                    put("initSigB64", initSig)
                    put("trustPeer", true)
                })
                if (sCode != 200 || stResp == null) throw IllegalStateException("Pair status failed ($sCode)")
                when (val st = stResp.optString("status", "")) {
                    "accepted" -> {
                        val sId = stResp.optString("sessionId", "")
                        if (sId.isNotEmpty()) {
                            sessId = sId
                            if (stResp.optBoolean("responderTrusted", false)) {
                                store.upsertTrustedSyncPeer(
                                    TrustedSyncPeer(
                                        deviceId = peer.deviceId,
                                        deviceName = peer.deviceName,
                                        platform = peer.platform,
                                        pubKeySpkiB64 = rSpki,
                                        pairedAt = System.currentTimeMillis() / 1000L
                                    )
                                )
                                discovery.refreshTrustFlags()
                            }
                            break
                        }
                    }
                    "declined" -> throw IllegalStateException("Pairing declined on ${peer.deviceName}")
                    "expired" -> throw IllegalStateException("Pairing timed out")
                }
                delay(600L)
            }
            if (sessId.isEmpty()) throw IllegalStateException("Pairing timed out")
        }

        scanner.isPaused = true
        _status.value = SyncStatus(
            state = "syncing",
            phase = "Comparing libraries…",
            peerId = peer.deviceId,
            peerName = peer.deviceName,
            direction = "outgoing"
        )

        val settings = store.loadSettings()
        val localMan = store.buildSyncManifest(identity.deviceId, selfName, settings.musicDirs)
        val sealedLocalMan = SyncCrypto.encryptSingleBuffer(sessionKey, localMan.toJson().toString().toByteArray(Charsets.UTF_8))
        val sealedRemoteMan = postEncryptedBytes(baseUrl, "/api/sync/v1/manifest", sessId, authKey, sealedLocalMan)
        val remoteManBytes = SyncCrypto.decryptSingleBuffer(sessionKey, sealedRemoteMan)
        val remoteMan = SyncManifest.fromJson(JSONObject(String(remoteManBytes, Charsets.UTF_8)))

        val baseSnap = store.loadSyncSnapshot(peer.deviceId)
        val plan = SyncMergeEngine.computeSyncPlan(localMan, remoteMan, baseSnap, syncDeletions)

        var totalBytes = 0L
        var pullBytes = 0L
        for (t in plan.pullAudioFromRemote) {
            totalBytes += t.fileSize
            pullBytes += t.fileSize
        }
        for (t in plan.pushAudioToRemote) {
            totalBytes += t.fileSize
        }
        if (pullBytes > 0L) {
            val dest = primaryDestDir()
            if (freeBytes(dest) < pullBytes + 64L * 1024L * 1024L) {
                throw IllegalStateException("Not enough free storage space (need ${pullBytes / (1024 * 1024)} MB)")
            }
        }

        data class XferTask(
            val dir: String,
            val kind: String,
            val trackId: String,
            val meta: SyncTrack? = null,
            val label: String = ""
        )

        val audioTasks = ArrayList<XferTask>()
        val auxTasks = ArrayList<XferTask>()
        for (t in plan.pullAudioFromRemote) audioTasks.add(XferTask("pull", "audio", t.id, t, "${t.artist} - ${t.title}"))
        for (t in plan.pushAudioToRemote) audioTasks.add(XferTask("push", "audio", t.id, t, "${t.artist} - ${t.title}"))
        for (id in plan.pullCoverFromRemote) auxTasks.add(XferTask("pull", "cover", id))
        for (id in plan.pullLyricsFromRemote) auxTasks.add(XferTask("pull", "lyrics", id))
        for (id in plan.pushCoverToRemote) auxTasks.add(XferTask("push", "cover", id))
        for (id in plan.pushLyricsToRemote) auxTasks.add(XferTask("push", "lyrics", id))

        val filesTotal = audioTasks.size + auxTasks.size
        val filesDone = AtomicInteger(0)
        val bytesDone = AtomicLong(0L)
        val startMs = System.currentTimeMillis()
        val lastEmitMs = AtomicLong(0L)
        val lastPingMs = AtomicLong(0L)
        val currentFileLabel = AtomicReference("")

        fun reportProgress(force: Boolean, label: String = "") {
            if (label.isNotEmpty()) currentFileLabel.set(label)
            val nowMs = System.currentTimeMillis()
            val prevEmit = lastEmitMs.get()
            if (!force && nowMs - prevEmit < 200L) return
            if (!force && !lastEmitMs.compareAndSet(prevEmit, nowMs)) return
            if (force) lastEmitMs.set(nowMs)

            val bd = bytesDone.get()
            val fd = filesDone.get()
            val pct = when {
                totalBytes > 0L -> ((bd * 100L) / totalBytes).toInt().coerceAtMost(99)
                filesTotal > 0 -> ((fd * 100) / filesTotal).coerceAtMost(99)
                else -> 99
            }
            val elapsedSec = (nowMs - startMs) / 1000.0
            val speed = if (elapsedSec > 0.2) (bd / elapsedSec).toLong() else 0L
            val phase = if (filesTotal == 0) "Merging metadata & playlists…" else "Transferring files ($fd/$filesTotal)…"
            _status.value = SyncStatus(
                state = "syncing",
                phase = phase,
                peerId = peer.deviceId,
                peerName = peer.deviceName,
                direction = "outgoing",
                percent = pct,
                currentFile = currentFileLabel.get(),
                transferredBytes = bd,
                totalBytes = totalBytes,
                filesDone = fd,
                filesTotal = filesTotal,
                speedBps = speed
            )
            val prevPing = lastPingMs.get()
            if (force || (nowMs - prevPing >= 600L && lastPingMs.compareAndSet(prevPing, nowMs))) {
                if (force) lastPingMs.set(nowMs)
                scope.launch {
                    try {
                        val ping = JSONObject().apply {
                            put("phase", phase)
                            put("percent", pct)
                            put("currentFile", currentFileLabel.get())
                            put("transferredBytes", bd)
                            put("totalBytes", totalBytes)
                            put("filesDone", fd)
                            put("filesTotal", filesTotal)
                            put("speedBps", speed)
                        }
                        val sealedPing = SyncCrypto.encryptSingleBuffer(sessionKey, ping.toString().toByteArray(Charsets.UTF_8))
                        postEncryptedBytes(baseUrl, "/api/sync/v1/progress", sessId, authKey, sealedPing)
                    } catch (_: Exception) {}
                }
            }
        }

        suspend fun runBatch(batch: List<XferTask>) {
            if (batch.isEmpty()) return
            reportProgress(true, batch.firstOrNull { it.label.isNotEmpty() }?.label.orEmpty())
            val sem = Semaphore(3)
            coroutineScope {
                batch.map { task ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            if (task.label.isNotEmpty()) reportProgress(false, task.label)
                            if (task.dir == "pull") {
                                pullFileFromPeer(baseUrl, sessId, sessionKey, authKey, task.kind, task.meta, task.trackId) { n ->
                                    bytesDone.addAndGet(n)
                                    reportProgress(false)
                                }
                            } else {
                                pushFileToPeer(baseUrl, sessId, sessionKey, authKey, task.kind, task.meta, task.trackId) { n ->
                                    bytesDone.addAndGet(n)
                                    reportProgress(false)
                                }
                            }
                            filesDone.incrementAndGet()
                            reportProgress(false)
                        }
                    }
                }.awaitAll()
            }
        }

        runBatch(audioTasks)
        runBatch(auxTasks)

        _status.value = SyncStatus(
            state = "syncing",
            phase = "Applying merged playlists, play counts & genres…",
            peerId = peer.deviceId,
            peerName = peer.deviceName,
            direction = "outgoing",
            percent = 99,
            transferredBytes = totalBytes,
            totalBytes = totalBytes,
            filesDone = filesTotal,
            filesTotal = filesTotal
        )

        val sealedCommit = SyncCrypto.encryptSingleBuffer(
            sessionKey,
            plan.remoteCommit.toJson().toString().toByteArray(Charsets.UTF_8)
        )
        postEncryptedBytes(baseUrl, "/api/sync/v1/commit", sessId, authKey, sealedCommit)
        store.applySyncCommit(plan.localCommit, peer.deviceId, plan.mergedSnapshot)

        scanner.isPaused = false
        discovery.refreshTrustFlags()
        val msg = "Synced with ${peer.deviceName}: +${plan.summaryLocalAdded} received, ${plan.summaryRemoteAdded} sent, ${plan.summaryLocalDeleted} removed"
        _status.value = SyncStatus(
            state = "done",
            phase = "Sync complete",
            peerId = peer.deviceId,
            peerName = peer.deviceName,
            direction = "outgoing",
            percent = 100,
            transferredBytes = totalBytes,
            totalBytes = totalBytes,
            filesDone = filesTotal,
            filesTotal = filesTotal,
            addedTracks = plan.summaryLocalAdded,
            updatedTracks = plan.summaryUpdated,
            deletedTracks = plan.summaryLocalDeleted,
            syncedPlaylists = plan.summaryPlaylists,
            message = msg
        )
        onSyncCompleted?.invoke()
    }

    private fun pullFileFromPeer(
        baseUrl: String,
        sessId: String,
        sessionKey: ByteArray,
        authKey: ByteArray,
        kind: String,
        meta: SyncTrack?,
        trackId: String,
        onBytes: (Long) -> Unit
    ) {
        val path = "/api/sync/v1/file/$kind/${SyncCrypto.b64UrlEncode(trackId.toByteArray(Charsets.UTF_8))}"
        val nonce = "${System.currentTimeMillis() / 1000L}-${SyncCrypto.randomHex(8)}"
        val mac = SyncCrypto.computeRequestMAC(authKey, "GET", path, sessId, nonce)
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 120_000
            setRequestProperty("X-Sync-Session", sessId)
            setRequestProperty("X-Sync-Nonce", nonce)
            setRequestProperty("X-Sync-Mac", mac)
        }
        try {
            val code = conn.responseCode
            if (code == 404 && (kind == "cover" || kind == "lyrics")) return
            if (code != 200) throw IllegalStateException("pull $kind $trackId failed ($code)")
            val plainSize = conn.getHeaderField("X-Plain-Size")?.toLongOrNull() ?: -1L
            val ext = conn.getHeaderField("X-Track-Ext").orEmpty()
            conn.inputStream.use { ins ->
                when (kind) {
                    "audio" -> saveIncomingAudio(ins, sessionKey, (meta ?: SyncTrack(id = trackId)).copy(id = trackId), ext, plainSize, onBytes)
                    "cover" -> saveIncomingCover(ins, sessionKey, trackId, plainSize)
                    "lyrics" -> saveIncomingLyrics(ins, sessionKey, trackId, plainSize)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun pushFileToPeer(
        baseUrl: String,
        sessId: String,
        sessionKey: ByteArray,
        authKey: ByteArray,
        kind: String,
        meta: SyncTrack?,
        trackId: String,
        onBytes: (Long) -> Unit
    ) {
        val (file, ext) = when (kind) {
            "audio" -> {
                val t = store.getTrackById(trackId) ?: throw IllegalStateException("missing audio $trackId")
                val f = File(t.resolvedPath)
                if (!f.exists()) throw IllegalStateException("missing audio file ${t.resolvedPath}")
                val dotExt = f.extension.let { if (it.isNotEmpty()) ".$it" else ".m4a" }
                f to dotExt
            }
            "cover" -> {
                val f = store.getCoverFile(trackId)
                if (!f.exists()) return
                f to ".jpg"
            }
            "lyrics" -> {
                val t = store.getTrackById(trackId) ?: return
                val f = store.sidecarLrcFile(t.resolvedPath) ?: return
                f to ".lrc"
            }
            else -> return
        }

        val plainLen = file.length()
        val encLen = SyncCrypto.encryptedStreamSize(plainLen)
        val path = "/api/sync/v1/file/$kind/${SyncCrypto.b64UrlEncode(trackId.toByteArray(Charsets.UTF_8))}"
        val nonce = "${System.currentTimeMillis() / 1000L}-${SyncCrypto.randomHex(8)}"
        val mac = SyncCrypto.computeRequestMAC(authKey, "PUT", path, sessId, nonce)

        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 8000
            readTimeout = 120_000
            doOutput = true
            setFixedLengthStreamingMode(encLen)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Sync-Session", sessId)
            setRequestProperty("X-Sync-Nonce", nonce)
            setRequestProperty("X-Sync-Mac", mac)
            setRequestProperty("X-Plain-Size", plainLen.toString())
            setRequestProperty("X-Track-Ext", ext)
            if (kind == "audio" && meta != null) {
                val sealedMeta = SyncCrypto.encryptSingleBuffer(sessionKey, meta.toJson().toString().toByteArray(Charsets.UTF_8))
                setRequestProperty("X-Track-Meta", SyncCrypto.b64Encode(sealedMeta))
            }
        }
        try {
            conn.outputStream.use { out ->
                FileInputStream(file).use { fis ->
                    SyncCrypto.writeEncryptedStream(out, sessionKey, fis, if (kind == "audio") onBytes else null)
                }
            }
            val code = conn.responseCode
            if (code != 200) {
                val errTxt = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IllegalStateException("push $kind $trackId failed ($code): $errTxt")
            }
        } finally {
            conn.disconnect()
        }
    }
}
