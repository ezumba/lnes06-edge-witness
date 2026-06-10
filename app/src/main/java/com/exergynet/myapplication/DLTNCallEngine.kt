package com.exergynet.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.exergynet.myapplication.webrtc.SignalingClient
import com.exergynet.myapplication.webrtc.WebRtcClient
import com.exergynet.myapplication.webrtc.WsSignalingChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLTNCallEngine — sovereign A/V call orchestrator.
 *
 * Establishes a local peer-to-peer TCP socket over Wi-Fi (caller binds a
 * ServerSocket and advertises ip:port in the mesh CALL_INVITE; callee connects to
 * it), then hands that socket to [WebRtcClient] as the SIGNALING channel. WebRTC
 * negotiates SDP/ICE across our own socket (no external server) and carries the
 * encrypted audio + video peer-to-peer with an EMPTY ICE-server list (same subnet).
 *
 * The raw-PCM path is retired — WebRTC now owns media (and gives us video).
 */
@SuppressLint("MissingPermission")
class DLTNCallEngine(
    private val context: Context,
    private val onCallStateChanged: (CallState, String, Boolean, String) -> Unit,
    private val sendSignal: (toNodeId: String, signalType: String, payload: String) -> Unit,
    // GLOBAL rail invite delivery (rings far peers) + WebRTC signaling transport.
    private val globalSignal: ((toNodeId: String, signalType: String, payload: String) -> Unit)? = null,
    private val globalAvailable: (() -> Boolean)? = null,
    // Push a WebRTC signaling frame (SDP/ICE JSON) to a peer over the LNES-12 WS.
    private val globalRtcSend: ((peerId: String, rtcJson: String) -> Unit)? = null,
) {
    private val TAG = "DLTNCallEngine"

    enum class CallState { IDLE, CALLING, RINGING, CONNECTED, ENDED }

    private val _callState = MutableStateFlow(CallState.IDLE)
    /** Observed by CallScreen. */
    val callState: StateFlow<CallState> = _callState

    // True when local video is on OR the remote peer is sending video → the UI
    // switches from the voice avatar layout to full-screen video.
    private val _videoActive = MutableStateFlow(false)
    val videoActive: StateFlow<Boolean> = _videoActive
    @Volatile private var localVideoOn = false
    @Volatile private var remoteVideoSeen = false
    private fun recomputeVideo() { _videoActive.value = localVideoOn || remoteVideoSeen }

    // ── Binary-frame decoder multiplexer (LNES-12) ────────────────────────────
    // One MediaCodec instance per remote sender. Instantiated lazily on first frame
    // from that sender; surface bound when the UI calls bindParticipantSurface().
    private data class RemoteParticipant(val codec: MediaCodec, val surface: Surface) {
        fun release() { try { codec.stop(); codec.release() } catch (_: Exception) {} }
    }
    private val remoteDecoders = ConcurrentHashMap<String, RemoteParticipant>()

    /**
     * Route an inbound binary payload (extracted from the 64-byte LNES-12 frame)
     * to the MediaCodec instance keyed by [senderId]. Creates a new decoder on first
     * arrival for each unique sender — prevents SPS collisions from multiple streams
     * sharing a single codec instance (which crashes the decoder).
     */
    fun onIncomingBinaryFrame(senderId: String, payload: ByteArray) {
        if (senderId.isEmpty() || payload.isEmpty()) return
        val participant = remoteDecoders.getOrPut(senderId) {
            createParticipantDecoder(senderId) ?: return
        }
        try {
            val idx = participant.codec.dequeueInputBuffer(10_000L)
            if (idx >= 0) {
                val buf = participant.codec.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(payload)
                participant.codec.queueInputBuffer(idx, 0, payload.size,
                    System.currentTimeMillis() * 1_000L, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DEMUX] codec feed error for $senderId: ${e.message}")
        }
    }

    private fun createParticipantDecoder(senderId: String): RemoteParticipant? {
        return try {
            val codec = MediaCodec.createDecoderByType("video/avc")
            // Offscreen placeholder surface until the UI binds a real one via bindParticipantSurface.
            val st = SurfaceTexture(0).also { it.setDefaultBufferSize(1280, 720) }
            val surface = Surface(st)
            val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
            codec.configure(format, surface, null, 0)
            codec.start()
            Log.i(TAG, "[DEMUX] Created H.264 decoder for sender ${senderId.take(8)}")
            RemoteParticipant(codec, surface)
        } catch (e: Exception) {
            Log.e(TAG, "[DEMUX] Decoder creation failed for $senderId: ${e.message}")
            null
        }
    }

    /**
     * Bind a UI-provided [Surface] to the decoder for [senderId].
     * Called by CallScreen when a ParticipantTile surface is ready.
     * Recreates the codec so output routes to the visible surface.
     */
    fun bindParticipantSurface(senderId: String, surface: Surface) {
        remoteDecoders[senderId]?.release()
        try {
            val codec = MediaCodec.createDecoderByType("video/avc")
            val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
            codec.configure(format, surface, null, 0)
            codec.start()
            remoteDecoders[senderId] = RemoteParticipant(codec, surface)
            Log.i(TAG, "[DEMUX] Surface bound for ${senderId.take(8)}")
        } catch (e: Exception) {
            Log.e(TAG, "[DEMUX] Surface bind failed for $senderId: ${e.message}")
        }
    }

    // Group room: null = 1:1, set = GRP_* room ID
    @Volatile var activeRoomId: String? = null
    // Map of peerId → (WebRtcClient, WsSignalingChannel) for group call participants
    private val peerSessions = ConcurrentHashMap<String, Pair<WebRtcClient, WsSignalingChannel>>()
    // Live list of group participant IDs exposed to the UI
    private val _groupParticipants = MutableStateFlow<List<String>>(emptyList())
    val groupParticipants: StateFlow<List<String>> = _groupParticipants
    // Callback fired when a new remote participant joins (peerId) — UI inflates a new tile
    var onParticipantAdded: ((peerId: String) -> Unit)? = null

    private var remotePeer = ""
    private val intentionalTeardown = AtomicBoolean(false)
    private val callConnected = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val eglBase: EglBase by lazy { EglBase.create() }
    private var webRtc: WebRtcClient? = null
    // Active global signaling channel (set for GLOBAL-rail calls); inbound WS
    // SDP/ICE frames are fed into it via onGlobalRtcSignal.
    private var activeWsChannel: WsSignalingChannel? = null

    private var serverSocket: ServerSocket? = null
    private var callSocket: Socket? = null

    // @Volatile ensures the latest address written by setRinging (IO coroutine) is
    // always visible to acceptIncomingCall (UI thread). The value is captured into a
    // local val at the top of acceptIncomingCall before any async work begins, so
    // subsequent setRinging calls cannot corrupt an in-flight connection attempt.
    @Volatile private var pendingCallerAddress = ""
    private var pendingLocalRenderer: SurfaceViewRenderer? = null
    private var pendingRemoteRenderer: SurfaceViewRenderer? = null

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private fun globalUp(): Boolean = globalAvailable?.invoke() == true

    fun eglBaseContext(): EglBase.Context = eglBase.eglBaseContext

    // ── Outgoing ───────────────────────────────────────────────────────────────

    @Volatile private var pendingStartVideo = false

    fun startOutgoingCall(peerNodeId: String, withVideo: Boolean = false) {
        if (_callState.value != CallState.IDLE) return
        intentionalTeardown.set(false)
        callConnected.set(false)
        pendingStartVideo = withVideo

        val localIp = getLocalNetworkIp()
        val globalRail = globalUp()
        if (localIp == null && !globalRail) {
            setCallState(CallState.ENDED, peerNodeId, reason = "no_wifi"); return
        }

        remotePeer = peerNodeId
        setCallState(CallState.CALLING, peerNodeId)

        if (globalRail) {
            globalSignal?.invoke(peerNodeId, DLTNConstants.MSG_TYPE_CALL_INVITE, DLTNConstants.CALL_RAIL_GLOBAL)
        }

        if (localIp != null) {
            scope.launch {
                try {
                    val ss = ServerSocket(0)
                    ss.soTimeout = DLTNConstants.VOICE_TIMEOUT_MS.toInt()
                    serverSocket = ss
                    val addr = "$localIp:${ss.localPort}"
                    Log.i(TAG, "[CALL] listening $addr — invite ${peerNodeId.take(8)}")
                    sendSignal(peerNodeId, DLTNConstants.MSG_TYPE_CALL_INVITE, addr)

                    val socket = ss.accept()
                    if (!callConnected.compareAndSet(false, true)) { socket.close(); return@launch }
                    callSocket = socket
                    try { ss.close() } catch (_: Exception) {}
                    serverSocket = null
                    setCallState(CallState.CONNECTED, remotePeer)
                    bindMedia(socket, isCaller = true)
                } catch (e: Exception) {
                    if (!intentionalTeardown.get() && !callConnected.get()) {
                        Log.w(TAG, "[CALL] local setup failed: ${e.message}")
                        if (!globalRail) endCall()
                    }
                }
            }
        }
    }

    // ── Incoming ────────────────────────────────────────────────────────────────

    fun acceptIncomingCall(peerNodeId: String) {
        if (_callState.value != CallState.RINGING) return
        intentionalTeardown.set(false)

        val addr = pendingCallerAddress
        val isGlobal = addr == DLTNConstants.CALL_RAIL_GLOBAL || !addr.contains(':')
        if (isGlobal) {
            // GLOBAL rail: ack over the WebSocket, then bind WebRTC media using the
            // WS signaling channel + Sovereign TURN relay. Callee is the answerer.
            if (!callConnected.compareAndSet(false, true)) return
            globalSignal?.invoke(peerNodeId, DLTNConstants.MSG_TYPE_CALL_ACCEPT, DLTNConstants.CALL_RAIL_GLOBAL)
            setCallState(CallState.CONNECTED, peerNodeId)
            bindGlobalMedia(peerNodeId, isCaller = false)
            return
        }

        if (!callConnected.compareAndSet(false, true)) return
        // addr captured before this point (local val above) — immune to concurrent setRinging.
        scope.launch {
            try {
                val ip = addr.substringBeforeLast(':')
                val port = addr.substringAfterLast(':').toInt()
                sendSignal(peerNodeId, DLTNConstants.MSG_TYPE_CALL_ACCEPT, "")
                val socket = Socket(ip, port)
                callSocket = socket
                // CONNECTED is only asserted after the socket succeeds — no false
                // "Connected" state while the TCP dial is still in flight or failing.
                setCallState(CallState.CONNECTED, peerNodeId)
                bindMedia(socket, isCaller = false)
            } catch (e: Exception) {
                Log.e(TAG, "[CALL] accept connect failed: ${e.message}")
                endCall()
            }
        }
    }

    fun onGlobalAccept(peerNodeId: String) {
        // Caller side: the far peer accepted over the global rail. Bind WebRTC media
        // over the WS signaling channel + TURN. Caller is the offerer.
        if (_callState.value == CallState.IDLE || _callState.value == CallState.ENDED) return
        if (!callConnected.compareAndSet(false, true)) return
        setCallState(CallState.CONNECTED, peerNodeId)
        bindGlobalMedia(peerNodeId, isCaller = true)
    }

    /** Feed an inbound WS WebRTC signaling frame (SDP/ICE) into the active call. */
    fun onGlobalRtcSignal(fromNodeId: String, rtcJson: String) {
        // Route to the correct peer session in a group call; fall back to 1:1 channel.
        val groupChannel = peerSessions[fromNodeId]?.second
        if (groupChannel != null) {
            groupChannel.onRemote(rtcJson)
        } else {
            activeWsChannel?.onRemote(rtcJson)
        }
    }

    // ── Group Call Upgrade ─────────────────────────────────────────────────────

    /**
     * Upgrade the current 1:1 call to a group call by adding [newPeerId].
     * Steps:
     *  1. Generate a GRP_ room ID.
     *  2. POST join for all three parties (self, existing peer, new peer) via Apex Router.
     *  3. Move existing WebRtcClient into peerSessions under remotePeer.
     *  4. Dial a new WebRTC connection (as offerer) to newPeerId.
     *  5. Notify the UI that a new participant tile should appear.
     */
    fun upgradeToGroupCall(
        newPeerId: String,
        myNodeId: String,
        globalMesh: GlobalMeshService,
        remoteRendererForNew: SurfaceViewRenderer?,
    ) {
        if (_callState.value != CallState.CONNECTED) return
        // Room ID: GRP_ + first 4 chars of myNodeId + last 4 digits of timestamp
        // Short, human-recognisable, collision-safe within a session.
        val ts = System.currentTimeMillis().toString()
        val roomId = "GRP_${myNodeId.take(4).uppercase()}${ts.takeLast(4)}"
        activeRoomId = roomId
        Log.i(TAG, "[GROUP] Upgrading to group call room=$roomId new=$newPeerId")

        // Join all three to the room
        globalMesh.joinGroup(roomId, myNodeId)
        globalMesh.joinGroup(roomId, remotePeer)
        globalMesh.joinGroup(roomId, newPeerId)

        // Move the existing 1:1 client into peerSessions (keep it alive)
        val existingRtc = webRtc
        val existingChannel = activeWsChannel
        if (existingRtc != null && existingChannel != null) {
            peerSessions[remotePeer] = Pair(existingRtc, existingChannel)
            webRtc = null
            activeWsChannel = null
        }

        // Invite the new peer via group room envelope
        scope.launch {
            val invitePayload = org.json.JSONObject()
                .put("room_id", roomId)
                .put("inviter", myNodeId)
                .put("peers", org.json.JSONArray().put(remotePeer).put(newPeerId))
                .toString()
            val inviteBytes = invitePayload.toByteArray(Charsets.UTF_8)
            globalMesh.sendSignalEnvelope(newPeerId, buildGroupInviteEnvelope(myNodeId, newPeerId, roomId, invitePayload))
        }

        // Open a new WebRTC peer connection to newPeerId (we are the offerer)
        val ws = WsSignalingChannel(out = { json ->
            globalMesh.sendRtcSignal(newPeerId, json)
        })
        val rtc = buildPeerWebRtcClient(ws, isCaller = true, remoteRenderer = remoteRendererForNew)
        peerSessions[newPeerId] = Pair(rtc, ws)
        rtc.start()

        // Update participant list for UI
        _groupParticipants.value = peerSessions.keys.toList()
        onParticipantAdded?.invoke(newPeerId)
    }

    /** Accept an incoming group invite from a peer. Called when a group_invite message arrives. */
    fun onGroupInviteReceived(
        fromPeerId: String,
        roomId: String,
        myNodeId: String,
        globalMesh: GlobalMeshService,
        remoteRenderer: SurfaceViewRenderer?,
    ) {
        if (_callState.value != CallState.CONNECTED) return
        activeRoomId = roomId
        Log.i(TAG, "[GROUP] Joining group room=$roomId from=$fromPeerId")

        globalMesh.joinGroup(roomId, myNodeId)

        val ws = WsSignalingChannel(out = { json ->
            globalMesh.sendRtcSignal(fromPeerId, json)
        })
        val rtc = buildPeerWebRtcClient(ws, isCaller = false, remoteRenderer = remoteRenderer)
        peerSessions[fromPeerId] = Pair(rtc, ws)
        rtc.start()

        _groupParticipants.value = peerSessions.keys.toList()
        onParticipantAdded?.invoke(fromPeerId)
    }

    private fun buildPeerWebRtcClient(
        channel: WsSignalingChannel,
        isCaller: Boolean,
        remoteRenderer: SurfaceViewRenderer?,
    ): WebRtcClient {
        val rtc = WebRtcClient(
            context = context,
            signaling = channel,
            isCaller = isCaller,
            eglBase = eglBase,
            iceServers = listOf(
                PeerConnection.IceServer.builder(DLTNConstants.TURN_URI)
                    .setUsername(DLTNConstants.TURN_USERNAME)
                    .setPassword(DLTNConstants.TURN_PASSWORD)
                    .createIceServer()
            ),
            onConnectionStateChange = { state ->
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    Log.w(TAG, "[GROUP] Peer connection dropped ($state)")
                }
            },
            onRemoteVideo = { remoteVideoSeen = true; recomputeVideo() },
        )
        rtc.setRenderers(null, remoteRenderer)
        if (localVideoOn) rtc.setVideoEnabled(true)
        return rtc
    }

    private fun buildGroupInviteEnvelope(
        fromId: String, toId: String, roomId: String, payload: String,
    ): ByteArray {
        val obj = org.json.JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("type", DLTNConstants.MSG_TYPE_GROUP_INVITE)
            .put("from_node_id", fromId)
            .put("to_node_id", toId)
            .put("content", android.util.Base64.encodeToString(
                payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
            .put("timestamp", System.currentTimeMillis())
            .put("signature", "")   // unsigned — group invite is trusted on receipt
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    // ── WebRTC media binding ────────────────────────────────────────────────────

    /** LOCAL rail: signaling over the P2P TCP socket, EMPTY ICE servers. */
    private fun bindMedia(socket: Socket, isCaller: Boolean) {
        val channel = SignalingClient(socket)
        startWebRtc(channel, isCaller, emptyList())
    }

    /** GLOBAL rail: signaling over the LNES-12 WebSocket, Sovereign TURN relay. */
    private fun bindGlobalMedia(peerNodeId: String, isCaller: Boolean) {
        val ws = WsSignalingChannel(out = { json -> globalRtcSend?.invoke(peerNodeId, json) })
        activeWsChannel = ws
        startWebRtc(ws, isCaller, turnIceServers())
    }

    private fun startWebRtc(
        channel: com.exergynet.myapplication.webrtc.SignalingChannel,
        isCaller: Boolean,
        iceServers: List<PeerConnection.IceServer>,
    ) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
            val rtc = WebRtcClient(
                context = context,
                signaling = channel,
                isCaller = isCaller,
                eglBase = eglBase,
                iceServers = iceServers,
                onConnectionStateChange = { state ->
                    if (state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        if (!intentionalTeardown.get()) handleDrop()
                    }
                },
                onRemoteVideo = { remoteVideoSeen = true; recomputeVideo() },
            )
            rtc.setRenderers(pendingLocalRenderer, pendingRemoteRenderer)
            rtc.start()
            webRtc = rtc
            // If the caller dialed a VIDEO call, turn the camera on once media is up.
            if (isCaller && pendingStartVideo) {
                localVideoOn = true; rtc.setVideoEnabled(true); recomputeVideo()
            }
            Log.i(TAG, "[CALL] WebRTC media started (caller=$isCaller, ice=${iceServers.size}, video=$pendingStartVideo)")
        } catch (e: Exception) {
            Log.e(TAG, "[CALL] WebRTC bind failed: ${e.message}")
            endCall()
        }
    }

    private fun turnIceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder(DLTNConstants.TURN_URI)
            .setUsername(DLTNConstants.TURN_USERNAME)
            .setPassword(DLTNConstants.TURN_PASSWORD)
            .createIceServer()
    )

    // ── Call UI controls (CallScreen) ─────────────────────────────────────────

    fun setRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        pendingLocalRenderer = local
        pendingRemoteRenderer = remote
        webRtc?.setRenderers(local, remote)
    }

    /** Register a remote SurfaceViewRenderer for a specific group participant. */
    fun setParticipantRenderer(peerId: String, renderer: SurfaceViewRenderer?) {
        peerSessions[peerId]?.first?.setRenderers(null, renderer)
    }

    fun setMuted(muted: Boolean) {
        webRtc?.setMuted(muted)
        peerSessions.values.forEach { (rtc, _) -> rtc.setMuted(muted) }
    }
    fun setSpeakerphoneOn(on: Boolean) { try { audioManager.isSpeakerphoneOn = on } catch (_: Exception) {} }
    fun setVideoEnabled(enabled: Boolean) {
        localVideoOn = enabled
        webRtc?.setVideoEnabled(enabled)
        peerSessions.values.forEach { (rtc, _) -> rtc.setVideoEnabled(enabled) }
        recomputeVideo()
    }
    fun switchCamera() {
        webRtc?.switchCamera()
        // Only one camera capturer exists per process — switching on the primary
        // WebRtcClient flips the camera for all sessions sharing the same device.
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    fun rejectCall() {
        intentionalTeardown.set(true)
        try { sendSignal(remotePeer, DLTNConstants.MSG_TYPE_CALL_REJECT, "") } catch (_: Exception) {}
        setCallState(CallState.ENDED, remotePeer); cleanup()
    }

    fun endCall() {
        if (_callState.value == CallState.IDLE) return
        intentionalTeardown.set(true)
        val peer = remotePeer
        if (peer.isNotEmpty()) {
            try { sendSignal(peer, DLTNConstants.MSG_TYPE_CALL_END, "") } catch (_: Exception) {}
            if (globalUp()) try { globalSignal?.invoke(peer, DLTNConstants.MSG_TYPE_CALL_END, DLTNConstants.CALL_RAIL_GLOBAL) } catch (_: Exception) {}
        }
        setCallState(CallState.ENDED, remotePeer); cleanup()
    }

    private fun handleDrop() {
        if (_callState.value == CallState.IDLE) return
        if (intentionalTeardown.get()) return
        setCallState(CallState.ENDED, remotePeer, dropped = true); cleanup()
    }

    fun setRinging(peerNodeId: String, callerAddress: String = "") {
        if (_callState.value == CallState.RINGING && remotePeer == peerNodeId) {
            if (callerAddress != DLTNConstants.CALL_RAIL_GLOBAL && callerAddress.contains(':') &&
                pendingCallerAddress == DLTNConstants.CALL_RAIL_GLOBAL) {
                pendingCallerAddress = callerAddress
            }
            return
        }
        pendingCallerAddress = callerAddress
        remotePeer = peerNodeId
        callConnected.set(false)
        setCallState(CallState.RINGING, peerNodeId)
    }

    fun getCurrentState() = _callState.value
    fun getRemotePeer() = remotePeer

    private fun getLocalNetworkIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces.toList()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun cleanup() {
        callConnected.set(false)
        pendingStartVideo = false
        localVideoOn = false; remoteVideoSeen = false; _videoActive.value = false
        try { webRtc?.close() } catch (_: Exception) {}
        webRtc = null
        activeWsChannel = null
        // Close all group peer sessions
        peerSessions.values.forEach { (rtc, _) -> try { rtc.close() } catch (_: Exception) {} }
        peerSessions.clear()
        _groupParticipants.value = emptyList()
        activeRoomId = null
        // Release all per-sender MediaCodec instances
        remoteDecoders.values.forEach { it.release() }
        remoteDecoders.clear()
        try { callSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        callSocket = null; serverSocket = null
        try {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {}
        remotePeer = ""; pendingCallerAddress = ""
        _callState.value = CallState.IDLE
    }

    private fun setCallState(state: CallState, peer: String, dropped: Boolean = false, reason: String = "") {
        _callState.value = state
        onCallStateChanged(state, peer, dropped, reason)
        Log.i(TAG, "[CALL] State → $state (${peer.take(8)})" +
            (if (dropped) " [DROPPED]" else "") + (if (reason.isNotEmpty()) " [$reason]" else ""))
    }

    fun destroy() {
        cleanup()
        try { eglBase.release() } catch (_: Exception) {}
        scope.cancel()
    }
}
