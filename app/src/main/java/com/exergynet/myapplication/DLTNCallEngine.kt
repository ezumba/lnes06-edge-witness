package com.exergynet.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLTNCallEngine — sovereign voice calls with DUAL-RAIL routing (LNES-06 + LNES-12).
 *
 *   RAIL 1 (PRIMARY)  — Local: direct TCP socket over the same LAN, address carried
 *                       in the local BLE/WiFi-Aware CALL_INVITE payload ("ip:port").
 *   RAIL 2 (FALLBACK) — Global: audio relayed through the L0 Sovereign WebSocket
 *                       (GlobalMeshService). The CALL_INVITE payload is the sentinel
 *                       "GLOBAL". Reaches peers far outside BLE range.
 *
 * On an outgoing call BOTH rails fire simultaneously. Whichever connects first
 * wins the audio engine (guarded by [callConnected]); the loser is discarded.
 * If the global lambdas are null (local-only build) behaviour is identical to
 * the previous local-only engine.
 */
@SuppressLint("MissingPermission")
class DLTNCallEngine(
    private val context: Context,
    private val onCallStateChanged: (CallState, String, Boolean, String) -> Unit,
    private val sendSignal: (toNodeId: String, signalType: String, payload: String) -> Unit,
    // ── LNES-12 GLOBAL rail (all optional → local-only safe) ──────────────────
    private val globalSignal: ((toNodeId: String, signalType: String, payload: String) -> Unit)? = null,
    private val globalAvailable: (() -> Boolean)? = null,
    private val globalAudioBridge: ((peerNodeId: String) -> Pair<InputStream, OutputStream>?)? = null,
    private val globalAudioClose: (() -> Unit)? = null,
) {
    private val TAG = "DLTNCallEngine"

    enum class CallState { IDLE, CALLING, RINGING, CONNECTED, ENDED }

    private var callState  = CallState.IDLE
    private var remotePeer = ""
    private val active     = AtomicBoolean(false)
    private val intentionalTeardown = AtomicBoolean(false)
    // Exactly one rail may bind the audio engine. First to win locks the call.
    private val callConnected = AtomicBoolean(false)
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val audioEngine = DLTNAudioEngine()

    private var serverSocket: ServerSocket? = null
    private var callSocket:   Socket?       = null

    // Caller's "ip:port" (local rail) or "GLOBAL" (global rail) from the invite.
    private var pendingCallerAddress = ""

    private fun globalUp(): Boolean = globalAvailable?.invoke() == true

    // ── Public API ───────────────────────────────────────────────────────────

    fun startOutgoingCall(peerNodeId: String) {
        if (callState != CallState.IDLE) return
        intentionalTeardown.set(false)
        callConnected.set(false)

        val localIp    = getLocalNetworkIp()
        val globalRail = globalUp()

        // Neither rail usable → fail fast (preserves the old "no_wifi" behaviour
        // when there is no global relay either).
        if (localIp == null && !globalRail) {
            Log.w(TAG, "[CALL] No local network AND global rail down — cannot call")
            setCallState(CallState.ENDED, peerNodeId, reason = "no_wifi")
            return
        }

        remotePeer = peerNodeId
        setCallState(CallState.CALLING, peerNodeId)

        // RAIL 2 — fire the GLOBAL invite (reaches far peers). The caller binds
        // WS audio when the matching CALL_ACCEPT(GLOBAL) returns via onGlobalAccept().
        if (globalRail) {
            Log.i(TAG, "[CALL] GLOBAL rail: sending invite to ${peerNodeId.take(8)}")
            globalSignal?.invoke(peerNodeId, DLTNConstants.MSG_TYPE_CALL_INVITE, DLTNConstants.CALL_RAIL_GLOBAL)
        }

        // RAIL 1 — local LAN socket (primary). Only if we have a local IP.
        if (localIp != null) {
            scope.launch {
                try {
                    val ss = ServerSocket(0)
                    ss.soTimeout = DLTNConstants.VOICE_TIMEOUT_MS.toInt()
                    serverSocket = ss
                    val addr = "$localIp:${ss.localPort}"
                    Log.i(TAG, "[CALL] LOCAL rail: listening on $addr — invite to ${peerNodeId.take(8)}")
                    sendSignal(peerNodeId, DLTNConstants.MSG_TYPE_CALL_INVITE, addr)

                    val socket = ss.accept()       // blocks until callee connects (or timeout)
                    // Did the global rail already win the race?
                    if (!callConnected.compareAndSet(false, true)) {
                        try { socket.close() } catch (_: Exception) {}
                        return@launch
                    }
                    socket.keepAlive = true
                    callSocket = socket
                    try { ss.close() } catch (_: Exception) {}
                    serverSocket = null
                    if (callState != CallState.ENDED) {
                        Log.i(TAG, "[CALL] LOCAL rail WON")
                        setCallState(CallState.CONNECTED, remotePeer)
                        startAudioStreaming(socket.getInputStream(), socket.getOutputStream())
                    } else {
                        try { socket.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    // accept() failing because we deliberately closed the socket
                    // after the GLOBAL rail won is expected — do not tear down then.
                    if (!intentionalTeardown.get() && !callConnected.get()) {
                        Log.w(TAG, "[CALL] LOCAL rail setup failed: ${e.message}")
                        // Only end the whole call if the global rail isn't carrying it.
                        if (!globalRail) endCall()
                    }
                }
            }
        }
    }

    fun acceptIncomingCall(peerNodeId: String) {
        if (callState != CallState.RINGING) return
        intentionalTeardown.set(false)

        val addr = pendingCallerAddress
        val isGlobal = addr == DLTNConstants.CALL_RAIL_GLOBAL || !addr.contains(':')

        if (isGlobal) {
            // RAIL 2 — accept over the global WebSocket and bind WS audio.
            Log.i(TAG, "[CALL] Accepting GLOBAL call from ${peerNodeId.take(8)}")
            globalSignal?.invoke(peerNodeId, DLTNConstants.MSG_TYPE_CALL_ACCEPT, DLTNConstants.CALL_RAIL_GLOBAL)
            bindGlobalAudio(peerNodeId)
            return
        }

        // RAIL 1 — local LAN connect to caller's ip:port.
        if (!callConnected.compareAndSet(false, true)) return
        setCallState(CallState.CONNECTED, peerNodeId)
        scope.launch {
            try {
                val ip   = addr.substringBeforeLast(':')
                val port = addr.substringAfterLast(':').toInt()
                Log.i(TAG, "[CALL] LOCAL connect to caller at $ip:$port")
                sendSignal(peerNodeId, DLTNConstants.MSG_TYPE_CALL_ACCEPT, "")
                val socket = Socket(ip, port)
                socket.keepAlive = true
                callSocket = socket
                startAudioStreaming(socket.getInputStream(), socket.getOutputStream())
            } catch (e: Exception) {
                Log.e(TAG, "[CALL] Accept connect failed: ${e.message}")
                endCall()
            }
        }
    }

    /**
     * LNES-12: the caller received a CALL_ACCEPT carrying the GLOBAL sentinel.
     * Bind the WebSocket audio bridge — unless the local rail already won.
     */
    fun onGlobalAccept(peerNodeId: String) {
        if (callState == CallState.IDLE || callState == CallState.ENDED) return
        Log.i(TAG, "[CALL] GLOBAL accept from ${peerNodeId.take(8)}")
        bindGlobalAudio(peerNodeId)
    }

    private fun bindGlobalAudio(peerNodeId: String) {
        if (!callConnected.compareAndSet(false, true)) return   // a rail already won
        val bridge = globalAudioBridge?.invoke(peerNodeId)
        if (bridge == null) {
            Log.w(TAG, "[CALL] GLOBAL audio bridge unavailable")
            callConnected.set(false)
            return
        }
        // Tear down the now-redundant local listener.
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        remotePeer = peerNodeId
        Log.i(TAG, "[CALL] GLOBAL rail WON")
        setCallState(CallState.CONNECTED, peerNodeId)
        startAudioStreaming(bridge.first, bridge.second)
    }

    fun rejectCall() {
        intentionalTeardown.set(true)
        try { sendSignal(remotePeer, DLTNConstants.MSG_TYPE_CALL_REJECT, "") } catch (_: Exception) {}
        if (globalUp()) try { globalSignal?.invoke(remotePeer, DLTNConstants.MSG_TYPE_CALL_REJECT, DLTNConstants.CALL_RAIL_GLOBAL) } catch (_: Exception) {}
        setCallState(CallState.ENDED, remotePeer)
        cleanup()
    }

    fun endCall() {
        if (callState == CallState.IDLE) return
        intentionalTeardown.set(true)
        // Hang up on BOTH rails (best-effort).
        val peer = remotePeer
        if (peer.isNotEmpty()) {
            try { sendSignal(peer, DLTNConstants.MSG_TYPE_CALL_END, "") } catch (_: Exception) {}
            if (globalUp()) try { globalSignal?.invoke(peer, DLTNConstants.MSG_TYPE_CALL_END, DLTNConstants.CALL_RAIL_GLOBAL) } catch (_: Exception) {}
        }
        setCallState(CallState.ENDED, remotePeer)
        cleanup()
    }

    private fun handleUnexpectedDrop() {
        if (callState == CallState.IDLE) return
        if (intentionalTeardown.get()) return
        Log.e(TAG, "Socket unexpectedly severed")
        setCallState(CallState.ENDED, remotePeer, dropped = true)
        cleanup()
    }

    fun setRinging(peerNodeId: String, callerAddress: String = "") {
        // A second invite for the SAME incoming call may arrive on the other rail
        // (both BLE and WebSocket). Ring once; prefer the local LAN address (lower
        // latency) if it arrives after a global invite.
        if (callState == CallState.RINGING && remotePeer == peerNodeId) {
            if (callerAddress != DLTNConstants.CALL_RAIL_GLOBAL && callerAddress.contains(':') &&
                pendingCallerAddress == DLTNConstants.CALL_RAIL_GLOBAL) {
                pendingCallerAddress = callerAddress   // upgrade global → local
            }
            return
        }
        pendingCallerAddress = callerAddress
        remotePeer = peerNodeId
        callConnected.set(false)
        setCallState(CallState.RINGING, peerNodeId)
    }

    fun getCurrentState() = callState
    fun getRemotePeer()   = remotePeer

    // ── Network IP resolution (local rail) ─────────────────────────────────────

    private fun getLocalNetworkIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces.toList()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        Log.i(TAG, "[CALL] Local network IP: $ip (iface: ${iface.name})")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[CALL] IP resolution failed: ${e.message}")
        }
        return null
    }

    // ── Full-duplex audio streaming (rail-agnostic) ────────────────────────────

    private fun startAudioStreaming(input: InputStream, output: OutputStream) {
        active.set(true)
        Log.i(TAG, "[CALL] Audio streaming started")
        audioEngine.start(input, output) { handleUnexpectedDrop() }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun cleanup() {
        active.set(false)
        callConnected.set(false)
        audioEngine.stop()
        try { globalAudioClose?.invoke() } catch (_: Exception) {}
        try { callSocket?.close()  } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        callSocket = null; serverSocket = null
        callState  = CallState.IDLE; remotePeer = ""
        pendingCallerAddress = ""
        Log.i(TAG, "[CALL] Cleaned up — MLE restored")
    }

    private fun setCallState(
        state: CallState, peer: String, dropped: Boolean = false, reason: String = "",
    ) {
        callState = state
        onCallStateChanged(state, peer, dropped, reason)
        Log.i(TAG, "[CALL] State → $state (peer: ${peer.take(8)})" +
            (if (dropped) " [DROPPED]" else "") +
            (if (reason.isNotEmpty()) " [$reason]" else ""))
    }

    fun destroy() { cleanup(); audioEngine.destroy(); scope.cancel() }
}
