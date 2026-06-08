package com.exergynet.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.net.wifi.aware.*
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class DLTNCallEngine(
    private val context: Context,
    private val onCallStateChanged: (CallState, String) -> Unit,
) {
    private val TAG = "DLTNCallEngine"

    enum class CallState { IDLE, CALLING, RINGING, CONNECTED, ENDED }

    private var callState  = CallState.IDLE
    private var remotePeer = ""
    private val active     = AtomicBoolean(false)
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val audioEngine = DLTNAudioEngine()

    private val wifiAwareManager by lazy {
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    }
    private var wifiAwareSession: WifiAwareSession? = null
    private var serverSocket: ServerSocket?  = null
    private var callSocket:   Socket?        = null

    // ── Public API ───────────────────────────────────────────────────────────

    fun startOutgoingCall(peerNodeId: String) {
        if (callState != CallState.IDLE) return
        // Voice is gated strictly to WiFi-Aware (Phase C). BLE MTU/bandwidth cannot
        // carry continuous audio, so we refuse rather than degrade.
        if (wifiAwareManager?.isAvailable != true) {
            Log.w(TAG, "[CALL] WiFi Aware unavailable — voice requires Phase C; aborting outgoing call")
            setCallState(CallState.ENDED, peerNodeId)
            return
        }
        remotePeer = peerNodeId
        setCallState(CallState.CALLING, peerNodeId)
        scope.launch {
            try { openVoiceServer() }
            catch (e: Exception) { Log.e(TAG, "[CALL] Outgoing setup failed: ${e.message}"); endCall() }
        }
    }

    fun acceptIncomingCall(peerNodeId: String) {
        if (callState != CallState.RINGING) return
        if (wifiAwareManager?.isAvailable != true) {
            Log.w(TAG, "[CALL] WiFi Aware unavailable — cannot accept voice call over BLE; ending")
            setCallState(CallState.ENDED, peerNodeId)
            cleanup()
            return
        }
        setCallState(CallState.CONNECTED, peerNodeId)
        scope.launch {
            try { connectToVoiceServer(peerNodeId) }
            catch (e: Exception) { Log.e(TAG, "[CALL] Accept failed: ${e.message}"); endCall() }
        }
    }

    fun rejectCall() {
        setCallState(CallState.ENDED, remotePeer)
        cleanup()
    }

    fun endCall() {
        if (callState == CallState.IDLE) return
        setCallState(CallState.ENDED, remotePeer)
        cleanup()
    }

    fun setRinging(peerNodeId: String) {
        remotePeer = peerNodeId
        setCallState(CallState.RINGING, peerNodeId)
    }

    fun getCurrentState() = callState
    fun getRemotePeer()   = remotePeer

    // ── Server side (caller waits for recipient to connect) ──────────────────

    private suspend fun openVoiceServer() = withContext(Dispatchers.IO) {
        serverSocket = ServerSocket(DLTNConstants.WIFI_AWARE_VOICE_PORT)
        serverSocket!!.soTimeout = DLTNConstants.VOICE_TIMEOUT_MS.toInt()
        Log.i(TAG, "[CALL] Voice server listening on port ${DLTNConstants.WIFI_AWARE_VOICE_PORT}")

        val socket = serverSocket!!.accept()
        callSocket = socket
        serverSocket?.close()

        if (callState != CallState.ENDED) {
            setCallState(CallState.CONNECTED, remotePeer)
            startAudioStreaming(socket)
        } else {
            socket.close()
        }
    }

    // ── Client side (recipient connects to caller) ───────────────────────────

    private suspend fun connectToVoiceServer(peerAddr: String) = withContext(Dispatchers.IO) {
        delay(500)
        val wam = wifiAwareManager ?: throw Exception("WiFi Aware not available")

        wam.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                wifiAwareSession = session
                val config = SubscribeConfig.Builder()
                    .setServiceName(DLTNConstants.WIFI_AWARE_SERVICE_NAME + "-voice")
                    .build()

                session.subscribe(config, object : DiscoverySessionCallback() {
                    override fun onServiceDiscovered(
                        peerHandle: PeerHandle,
                        serviceSpecificInfo: ByteArray?,
                        matchFilter: MutableList<ByteArray>
                    ) {
                        scope.launch {
                            try {
                                val socket = Socket(peerAddr, DLTNConstants.WIFI_AWARE_VOICE_PORT)
                                callSocket = socket
                                startAudioStreaming(socket)
                            } catch (e: Exception) { Log.e(TAG, "[CALL] Connect failed: ${e.message}"); endCall() }
                        }
                    }
                }, null)
            }
            override fun onAttachFailed() {
                scope.launch {
                    try {
                        delay(300)
                        val socket = Socket(peerAddr, DLTNConstants.WIFI_AWARE_VOICE_PORT)
                        callSocket = socket
                        startAudioStreaming(socket)
                    } catch (e: Exception) { Log.e(TAG, "[CALL] Direct connect failed: ${e.message}"); endCall() }
                }
            }
        }, null)
    }

    // ── Full-duplex audio streaming ───────────────────────────────────────────

    private fun startAudioStreaming(socket: Socket) {
        active.set(true)
        Log.i(TAG, "[CALL] Audio streaming started — handing socket to DLTNAudioEngine")
        // Hand the established WiFi-Aware socket streams to the audio engine.
        // If a stream drops on its own (remote hung up), tear the call down.
        audioEngine.start(socket.getInputStream(), socket.getOutputStream()) { endCall() }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun cleanup() {
        active.set(false)
        audioEngine.stop()   // release mic + speaker hardware immediately
        try { callSocket?.close()  } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { wifiAwareSession?.close() } catch (_: Exception) {}
        callSocket     = null; serverSocket   = null; wifiAwareSession = null
        callState      = CallState.IDLE; remotePeer = ""
        Log.i(TAG, "[CALL] Cleaned up — MLE restored")
    }

    private fun setCallState(state: CallState, peer: String) {
        callState = state
        onCallStateChanged(state, peer)
        Log.i(TAG, "[CALL] State → $state (peer: ${peer.take(8)})")
    }

    fun destroy() { cleanup(); audioEngine.destroy(); scope.cancel() }
}
