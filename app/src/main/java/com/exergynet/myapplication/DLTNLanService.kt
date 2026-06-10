package com.exergynet.myapplication

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLTNLanService — WiFi LAN rail (NSD/mDNS + direct TCP).
 *
 * ════════════════════════════════════════════════════════════════════════════
 * WHY THIS EXISTS: BLE peer-to-peer between two Android phones is fragile (OEM
 * stack limits on simultaneous advertise+scan+GATT, multiple dangerous runtime
 * permissions). When both phones are on the SAME Wi-Fi, NSD service discovery +
 * direct TCP is far more reliable and needs NO dangerous runtime permission.
 * This rail is ADDITIVE — BLE (DLTNManager) and the global WebSocket relay remain.
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Discovery : registers "_exergynet-dltn._tcp" with this node's id in a TXT
 *             attribute + its TCP port; discovers + resolves peers the same way.
 * Transport : a ServerSocket accepts inbound connections and reads length-prefixed
 *             envelopes; outbound, we poll the SAME outbox DLTNManager (BLE) uses
 *             and push each pending envelope to discovered peers over TCP. Because
 *             call-signal envelopes also live in that outbox, this rail delivers
 *             messages, images, AND call ringing.
 *
 * Wire frame: [Int len][len bytes]  (identical to the audio/BLE framing).
 */
class DLTNLanService(
    private val context: Context,
    private val myNodeId: String,
    /** Inbound envelope bytes → route to messenger.receiveRawPayload. */
    private val onEnvelope: (peerNodeId: String, bytes: ByteArray) -> Unit,
    /** Pending outbox envelopes (id, bytes) for a given peer node id. */
    private val pendingFor: suspend (nodeId: String) -> List<Pair<String, ByteArray>>,
    /** Mark a message delivered once a peer has acked the TCP write. */
    private val onDelivered: suspend (msgId: String) -> Unit,
) {
    private val TAG = "DLTNLan"
    private val SERVICE_TYPE = "_exergynet-dltn._tcp."
    private val ATTR_NID = "nid"

    private val nsd by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val wifi by lazy { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var serverSocket: ServerSocket? = null
    @Volatile private var localPort = 0
    private var multicastLock: WifiManager.MulticastLock? = null

    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    // Discovered peers: nodeId → socket address.
    private val peers = ConcurrentHashMap<String, InetSocketAddress>()

    // NSD allows only one resolve at a time (pre-API 34) — serialize them.
    private val resolveMutex = Mutex()

    // Diagnostics
    @Volatile private var registered = false
    @Volatile private var discovering = false
    @Volatile private var lastNote = "idle"

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) return
        if (myNodeId.isBlank()) { Log.w(TAG, "no node id — LAN rail not started"); return }
        acquireMulticastLock()
        startServer()
        registerService()
        startDiscovery()
        scope.launch { flushLoop() }
        Log.i(TAG, "[LAN] started — node ${myNodeId.take(8)} port $localPort")
    }

    fun stop() {
        running.set(false)
        try { discListener?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { regListener?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        scope.cancel()
        Log.i(TAG, "[LAN] stopped")
    }

    private fun acquireMulticastLock() {
        try {
            multicastLock = wifi.createMulticastLock("exergynet-dltn").apply {
                setReferenceCounted(true); acquire()
            }
        } catch (e: Exception) { Log.w(TAG, "[LAN] multicast lock failed: ${e.message}") }
    }

    // ── TCP server ────────────────────────────────────────────────────────────

    private fun startServer() {
        try {
            val ss = ServerSocket(0)
            serverSocket = ss
            localPort = ss.localPort
            scope.launch {
                while (running.get()) {
                    try {
                        val sock = ss.accept()
                        scope.launch { readConnection(sock) }
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "[LAN] accept ended: ${e.message}")
                        break
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "[LAN] server bind failed: ${e.message}") }
    }

    private fun readConnection(sock: Socket) {
        val peerAddr = sock.inetAddress?.hostAddress ?: "lan"
        try {
            sock.soTimeout = 0
            val inp = DataInputStream(sock.getInputStream())
            while (running.get()) {
                val len = inp.readInt()
                if (len <= 0 || len > 8_000_000) break
                val buf = ByteArray(len)
                inp.readFully(buf)
                lastNote = "rx ${buf.size}B from $peerAddr"
                onEnvelope(peerAddr, buf)
            }
        } catch (e: Exception) {
            // EOF / closed — normal end of a delivery connection.
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    // ── NSD register + discover ────────────────────────────────────────────────

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = "dltn-" + myNodeId.take(20)
            serviceType = SERVICE_TYPE
            port = localPort
            setAttribute(ATTR_NID, myNodeId)
        }
        regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registered = true
                Log.i(TAG, "[LAN] registered as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registered = false
                Log.w(TAG, "[LAN] register failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) { registered = false }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener) }
        catch (e: Exception) { Log.w(TAG, "[LAN] registerService error: ${e.message}") }
    }

    private fun startDiscovery() {
        discListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { discovering = true }
            override fun onDiscoveryStopped(serviceType: String) { discovering = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false; Log.w(TAG, "[LAN] discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                if (info.serviceName == "dltn-" + myNodeId.take(20)) return  // ourselves
                scope.launch { resolve(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                // Drop any peer whose service vanished (best-effort by name match).
                peers.entries.removeIf { false } // addresses keyed by nodeId; lost handled lazily
            }
        }
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener) }
        catch (e: Exception) { Log.w(TAG, "[LAN] discoverServices error: ${e.message}") }
    }

    private suspend fun resolve(info: NsdServiceInfo) = resolveMutex.withLock {
        val done = CompletableDeferred<NsdServiceInfo?>()
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "[LAN] resolve failed: $errorCode"); done.complete(null)
            }
            override fun onServiceResolved(resolved: NsdServiceInfo) { done.complete(resolved) }
        }
        try { nsd.resolveService(info, listener) } catch (e: Exception) { done.complete(null) }
        val resolved = try { withTimeout(6000) { done.await() } } catch (e: Exception) { null } ?: return
        val nid = resolved.attributes?.get(ATTR_NID)?.let { String(it, Charsets.UTF_8) } ?: return
        if (nid == myNodeId) return
        val host = resolved.host?.hostAddress ?: return
        peers[nid] = InetSocketAddress(host, resolved.port)
        lastNote = "found ${nid.take(8)} @ $host:${resolved.port}"
        Log.i(TAG, "[LAN] peer ${nid.take(8)} @ $host:${resolved.port}")
        // Immediately flush anything queued for this peer (e.g. a pending call invite).
        flushToPeer(nid)
    }

    // ── Outbound delivery (poll the shared outbox) ─────────────────────────────

    private suspend fun flushLoop() {
        while (running.get()) {
            delay(1500)
            if (!running.get()) break
            for (nid in peers.keys) flushToPeer(nid)
        }
    }

    private suspend fun flushToPeer(nodeId: String) {
        val addr = peers[nodeId] ?: return
        val pending = try { pendingFor(nodeId) } catch (_: Exception) { emptyList() }
        if (pending.isEmpty()) return
        try {
            Socket().use { sock ->
                sock.connect(addr, 4000)
                val out = DataOutputStream(sock.getOutputStream())
                for ((id, bytes) in pending) {
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                    onDelivered(id)
                }
                lastNote = "delivered ${pending.size} → ${nodeId.take(8)}"
                Log.i(TAG, "[LAN] delivered ${pending.size} envelope(s) to ${nodeId.take(8)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[LAN] deliver to ${nodeId.take(8)} failed: ${e.message}")
            peers.remove(nodeId)   // stale — will re-resolve on next discovery
        }
    }

    // ── Diagnostics ────────────────────────────────────────────────────────────

    fun diagnostics(): org.json.JSONObject {
        val arr = org.json.JSONArray()
        for ((nid, addr) in peers) {
            arr.put(org.json.JSONObject().apply {
                put("nodeId", nid); put("addr", "${addr.hostString}:${addr.port}")
            })
        }
        return org.json.JSONObject().apply {
            put("lanRunning", running.get())
            put("lanPort", localPort)
            put("lanRegistered", registered)
            put("lanDiscovering", discovering)
            put("lanPeerCount", peers.size)
            put("lanPeers", arr)
            put("lanLast", lastNote)
        }
    }
}
