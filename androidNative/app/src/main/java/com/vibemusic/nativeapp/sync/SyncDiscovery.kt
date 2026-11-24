package com.vibemusic.nativeapp.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

class SyncDiscovery(
    private val context: Context,
    private val onPeersChanged: (List<SyncPeer>) -> Unit,
    private val onReverseRequest: (remoteIp: String, port: Int, deviceId: String, deviceName: String, platform: String, pubSpkiB64: String, syncDeletions: Boolean) -> Unit,
    private val buildSelfSummary: () -> SyncSelfSummary,
    private val isPeerTrusted: (deviceId: String) -> Boolean
) {
    data class SyncSelfSummary(
        val deviceId: String,
        val deviceName: String,
        val port: Int,
        val trackCount: Int,
        val playlistCount: Int,
        val pubKeySpkiB64: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var readJob: Job? = null
    private var announceJob: Job? = null
    private val peersMap = ConcurrentHashMap<String, SyncPeer>()

    @Volatile
    var isRunning: Boolean = false
        private set

    fun localIPv4sAndBroadcasts(): Pair<List<String>, List<InetAddress>> {
        val ips = LinkedHashSet<String>()
        val bcasts = LinkedHashSet<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { ips.add(it) }
                        ia.broadcast?.let { bcasts.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        try {
            bcasts.add(InetAddress.getByName("255.255.255.255"))
            bcasts.add(InetAddress.getByName(SyncConstants.MULTICAST_ADDR))
        } catch (_: Exception) {}
        return ips.sorted() to bcasts.toList()
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        peersMap.clear()

        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("vibemusic_sync_mc")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("SyncDiscovery", "MulticastLock failed: ${e.message}")
        }

        val sock: DatagramSocket = try {
            val ms = MulticastSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 2000
                bind(InetSocketAddress(SyncConstants.DEFAULT_PORT))
            }
            try {
                val group = InetAddress.getByName(SyncConstants.MULTICAST_ADDR)
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces != null && ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    if (iface.isUp && iface.supportsMulticast() && !iface.isLoopback) {
                        try {
                            @Suppress("DEPRECATION")
                            ms.joinGroup(group)
                            break
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            ms
        } catch (_: Exception) {
            DatagramSocket(0).apply {
                broadcast = true
                soTimeout = 2000
            }
        }

        socket = sock
        readJob = scope.launch { readLoop(sock) }
        announceJob = scope.launch { announceLoop() }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        readJob?.cancel()
        announceJob?.cancel()
        readJob = null
        announceJob = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
        peersMap.clear()
        onPeersChanged(emptyList())
    }

    fun getPeers(): List<SyncPeer> {
        val now = System.currentTimeMillis() / 1000L
        val it = peersMap.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastSeen > 10L) {
                it.remove()
            }
        }
        return peersMap.values.map { p ->
            p.copy(trusted = isPeerTrusted(p.deviceId))
        }.sortedWith(compareByDescending<SyncPeer> { it.trusted }.thenBy { it.deviceName }.thenBy { it.deviceId })
    }

    fun upsertManualPeer(peer: SyncPeer) {
        peersMap[peer.deviceId] = peer.copy(lastSeen = System.currentTimeMillis() / 1000L)
        onPeersChanged(getPeers())
    }

    fun refreshTrustFlags() {
        onPeersChanged(getPeers())
    }

    private fun buildPacketBytes(type: String, targetDeviceId: String = "", syncDeletions: Boolean = true): ByteArray {
        val self = buildSelfSummary()
        val obj = JSONObject().apply {
            put("magic", SyncConstants.DISCOVERY_MAGIC)
            put("type", type)
            put("deviceId", self.deviceId)
            put("deviceName", self.deviceName)
            put("platform", "android")
            put("port", self.port)
            put("trackCount", self.trackCount)
            put("playlistCount", self.playlistCount)
            put("pubKeySpkiB64", self.pubKeySpkiB64)
            if (targetDeviceId.isNotEmpty()) {
                put("targetDeviceId", targetDeviceId)
                put("syncDeletions", syncDeletions)
            }
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    fun announceNow() {
        val sock = socket ?: return
        scope.launch {
            sendAnnounce(sock)
        }
    }

    fun sendReverseRequest(peerIp: String, targetDeviceId: String, syncDeletions: Boolean) {
        val sock = socket ?: return
        try {
            val bytes = buildPacketBytes("reverse_request", targetDeviceId, syncDeletions)
            val addr = InetAddress.getByName(peerIp)
            sock.send(DatagramPacket(bytes, bytes.size, addr, SyncConstants.DEFAULT_PORT))
        } catch (e: Exception) {
            Log.w("SyncDiscovery", "sendReverseRequest error: ${e.message}")
        }
    }

    private fun sendAnnounce(sock: DatagramSocket) {
        try {
            val bytes = buildPacketBytes("announce")
            val (_, bcasts) = localIPv4sAndBroadcasts()
            for (dst in bcasts) {
                try {
                    sock.send(DatagramPacket(bytes, bytes.size, dst, SyncConstants.DEFAULT_PORT))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private suspend fun announceLoop() {
        while (scope.isActive && isRunning) {
            val sock = socket ?: break
            sendAnnounce(sock)
            val now = System.currentTimeMillis() / 1000L
            var pruned = false
            val it = peersMap.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value.lastSeen > 10L) {
                    it.remove()
                    pruned = true
                }
            }
            if (pruned) {
                onPeersChanged(getPeers())
            }
            delay(2500L)
        }
    }

    private fun readLoop(sock: DatagramSocket) {
        val buf = ByteArray(2048)
        val selfId = buildSelfSummary().deviceId
        while (isRunning && !sock.isClosed) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val len = pkt.length
                if (len <= 0 || len > 1536) continue
                val remoteAddr = pkt.address as? Inet4Address ?: continue
                val remoteIp = remoteAddr.hostAddress ?: continue
                val json = JSONObject(String(pkt.data, 0, len, Charsets.UTF_8))
                if (json.optString("magic") != SyncConstants.DISCOVERY_MAGIC) continue
                val devId = json.optString("deviceId", "")
                if (devId.isEmpty() || devId == selfId) continue
                val port = json.optInt("port", 0)
                if (port <= 0 || port > 65535) continue
                val spki = json.optString("pubKeySpkiB64", "")
                try {
                    SyncCrypto.verifyDeviceIdAndKey(devId, spki)
                } catch (_: Exception) {
                    continue
                }

                val type = json.optString("type", "")
                val devName = json.optString("deviceName", "").trim()
                val platform = json.optString("platform", "")
                val trackCount = json.optInt("trackCount", 0)
                val playlistCount = json.optInt("playlistCount", 0)

                if (type == "reverse_request") {
                    if (json.optString("targetDeviceId", "") == selfId) {
                        val syncDel = json.optBoolean("syncDeletions", true)
                        scope.launch {
                            onReverseRequest(remoteIp, port, devId, devName, platform, spki, syncDel)
                        }
                    }
                    continue
                }
                if (type != "announce" && type != "reply") continue

                val trusted = isPeerTrusted(devId)
                val peer = SyncPeer(
                    deviceId = devId,
                    deviceName = devName,
                    platform = platform,
                    ip = remoteIp,
                    port = port,
                    trackCount = trackCount,
                    playlistCount = playlistCount,
                    pubKeySpkiB64 = spki,
                    trusted = trusted,
                    lastSeen = System.currentTimeMillis() / 1000L
                )
                val prev = peersMap.put(devId, peer)
                if (prev == null || prev.ip != peer.ip || prev.port != peer.port ||
                    prev.deviceName != peer.deviceName || prev.trackCount != peer.trackCount ||
                    prev.playlistCount != peer.playlistCount || prev.trusted != peer.trusted
                ) {
                    onPeersChanged(getPeers())
                }

                if (type == "announce") {
                    try {
                        val rep = buildPacketBytes("reply")
                        sock.send(DatagramPacket(rep, rep.size, remoteAddr, SyncConstants.DEFAULT_PORT))
                        if (pkt.port in 1..65535 && pkt.port != SyncConstants.DEFAULT_PORT) {
                            sock.send(DatagramPacket(rep, rep.size, remoteAddr, pkt.port))
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: SocketTimeoutException) {
                // continue polling isRunning
            } catch (_: Exception) {
                if (!isRunning || sock.isClosed) break
            }
        }
    }
}
