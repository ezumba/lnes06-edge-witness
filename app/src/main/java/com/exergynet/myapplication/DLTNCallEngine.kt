package com.exergynet.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class DLTNCallEngine(
    private val context: Context,
    private val onCallStateChanged: (CallState, String, Boolean, String) -> Unit,
    private val sendSignal: (toNodeId: String, signalType: String, payload: String) -> Unit,
) {
    private val TAG = "DLTNCallEngine"

    enum class CallState { IDLE, CALLING, RINGING, CONNECTED, ENDED }

    private var callState  = CallState.IDLE
    private var remotePeer = ""
    private val active     = AtomicBoolean(false)
    private val intentionalTeardown = AtomicBoolean(false)
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val audioEngine = DLTNAudioEngine()

    private var serverSocket: ServerSocket? = null
    private var callSocket:   Socket?       = null

    // Caller's "ip:port" received in CALL_INVITE — stored so acceptIncomingCall can connect.
    private var pendingCallerAddress = ""

    // ── Public API ───────────────────────────────────────────────────────────

    fun startOutgoingCall(peerNodeId: String) {
        if (callState != CallState.IDLE) return
        intentionalTeardown.set(false)

        val localIp = getLocalNetworkIp()
        if (localIp == null) {
            Log.w(TAG, "[CALL] No network IP — connect to WiFi to make calls")
            setCallState(CallState.ENDED, peerNodeId, reason = "no_wifi")
            return
        }

        remotePeer = peerNodeId
        setCallState(CallState.CALLING, peerNodeId)

        scope.launch {
            try {
                // Bind on any free port; the OS picks one.
                val ss = ServerSocket(0)
                ss.soTimeout = DLTNConstants.VOICE_TIMEOUT_MS.toInt()
                serverSocket = ss
                val port = ss.localPort
                val addr = "$localIp:$port"
                Log.i(TAG, "[CALL] Listening on $addr — sending invite to ${peerNodeId.take(8)}")

                // Advertise our address in the invite payload so the callee can connect.
                sendSignal(peerNodeId, DLTNConstants.MSG_TYPE_CALL_INVITE, addr)

                // Wait for callee to connect (blocking — times out via soTimeout above).
                val socket = ss.accept()
                socket.keepAlive = true
                callSocket = socket
                ss.close()
                serverSocket = null

                if (callState != CallState.ENDED) {
                    setCallState(CallState.CONNECTED, remotePeer)
                    startAudioStreaming(socket)
                } else {
                    socket.close()
                }
            } catch (e: Exception) {
                if (!intentionalTeardown.get()) {
                    Log.e(TAG, "[CALL] Outgoing setup failed: ${e.message}")
                }
                endCall()
            }
        }
    }

    fun acceptIncomingCall(peerNodeId: String) {
        if (callState != CallState.RINGING) return
        intentionalTeardown.set(false)

        val addr = pendingCallerAddress
        if (addr.isEmpty() || !addr.contains(':')) {
            Log.e(TAG, "[CALL] No caller address in invite — cannot connect")
            setCallState(CallState.ENDED, peerNodeId, reason = "no_caller_address")
            cleanup()
            return
        }

        setCallState(CallState.CONNECTED, peerNodeId)
        scope.launch {
            try {
                val ip   = addr.substringBeforeLast(':')
                val port = addr.substringAfterLast(':').toInt()
                Log.i(TAG, "[CALL] Connecting to caller at $ip:$port")
                sendSignal(peerNodeId, DLTNConstants.MSG_TYPE_CALL_ACCEPT, "")
                val socket = Socket(ip, port)
                socket.keepAlive = true
                callSocket = socket
                startAudioStreaming(socket)
            } catch (e: Exception) {
                Log.e(TAG, "[CALL] Accept connect failed: ${e.message}")
                endCall()
            }
        }
    }

    fun rejectCall() {
        intentionalTeardown.set(true)
        setCallState(CallState.ENDED, remotePeer)
        cleanup()
    }

    fun endCall() {
        if (callState == CallState.IDLE) return
        intentionalTeardown.set(true)
        setCallState(CallState.ENDED, remotePeer)
        cleanup()
    }

    private fun handleUnexpectedDrop() {
        if (callState == CallState.IDLE) return
        if (intentionalTeardown.get()) return
        Log.e(TAG, "Socket unexpectedly severed by OS")
        setCallState(CallState.ENDED, remotePeer, dropped = true)
        cleanup()
    }

    fun setRinging(peerNodeId: String, callerAddress: String = "") {
        pendingCallerAddress = callerAddress
        remotePeer = peerNodeId
        setCallState(CallState.RINGING, peerNodeId)
    }

    fun getCurrentState() = callState
    fun getRemotePeer()   = remotePeer

    // ── Network IP resolution ─────────────────────────────────────────────────

    /**
     * Returns the device's first active non-loopback IPv4 address.
     * Works on WiFi, hotspot, and Ethernet — no special permissions needed.
     * Returns null if no suitable network interface is up.
     */
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

    // ── Full-duplex audio streaming ───────────────────────────────────────────

    private fun startAudioStreaming(socket: Socket) {
        active.set(true)
        Log.i(TAG, "[CALL] Audio streaming started")
        audioEngine.start(socket.getInputStream(), socket.getOutputStream()) { handleUnexpectedDrop() }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun cleanup() {
        active.set(false)
        audioEngine.stop()
        try { callSocket?.close()  } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        callSocket       = null; serverSocket     = null
        callState        = CallState.IDLE; remotePeer = ""
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
