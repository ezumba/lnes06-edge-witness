package com.exergynet.myapplication.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * WebRtcClient — full-duplex AUDIO + VIDEO over WebRTC, signalled across our own
 * sovereign peer-to-peer TCP socket (NO external signaling server, NO STUN/TURN).
 *
 * Sovereign constraints (per the Architect's directive):
 *   • Signaling rides the existing DLTNCallEngine [socket] via [SignalingClient]:
 *     SDP offer/answer + ICE candidates are serialized to JSON and piped across it.
 *   • ICE servers are EMPTY — on the same Wi-Fi / WiFi-Aware subnet WebRTC gathers
 *     local host candidates and connects directly, no NAT traversal infrastructure.
 *   • Media (SRTP/DTLS) flows peer-to-peer on WebRTC's own transport; the TCP
 *     socket only carries the handshake.
 *
 * The CALLER is the offerer (isCaller = true → createOffer on connect); the callee
 * answers.
 */
class WebRtcClient(
    private val context: Context,
    private val signaling: SignalingChannel,
    private val isCaller: Boolean,
    private val eglBase: EglBase,
    /** EMPTY for local mesh calls; the Sovereign TURN server for global calls. */
    private val iceServers: List<PeerConnection.IceServer> = emptyList(),
    private val onConnectionStateChange: (PeerConnection.IceConnectionState) -> Unit = {},
    /** Fired when the remote peer's video track arrives (so the UI can go full-screen). */
    private val onRemoteVideo: () -> Unit = {},
) : SignalingClientListener {

    private val TAG = "WebRtcClient"
    private val rootEglBase get() = eglBase

    private val factory: PeerConnectionFactory by lazy { buildFactory() }
    private var peerConnection: PeerConnection? = null

    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null

    private val STREAM_ID = "dltn_stream"

    init { signaling.listener = this }

    // ── Public lifecycle ────────────────────────────────────────────────────

    /** Wire renderers BEFORE start() so the first frames have a sink. */
    fun setRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        try { local?.init(rootEglBase.eglBaseContext, null) } catch (_: Exception) {}
        try { remote?.init(rootEglBase.eglBaseContext, null) } catch (_: Exception) {}
        localVideoTrack?.let { t -> local?.let { t.addSink(it) } }
        remoteVideoTrack?.let { t -> remote?.let { t.addSink(it) } }
    }

    fun start() {
        createPeerConnection()
        addLocalMedia()
        // Begin reading signaling frames; onConnectionEstablished kicks off the offer.
        signaling.start()
    }

    fun setMuted(muted: Boolean) { localAudioTrack?.setEnabled(!muted) }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        try {
            if (enabled) startCapture() else videoCapturer?.stopCapture()
        } catch (e: Exception) { Log.w(TAG, "[RTC] video toggle: ${e.message}") }
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun hangup() {
        try { signaling.sendBye() } catch (_: Exception) {}
        close()
    }

    fun close() {
        try { signaling.close() } catch (_: Exception) {}
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        try { localVideoSource?.dispose() } catch (_: Exception) {}
        try { localAudioSource?.dispose() } catch (_: Exception) {}
        try { peerConnection?.close(); peerConnection?.dispose() } catch (_: Exception) {}
        try { localRenderer?.release() } catch (_: Exception) {}
        try { remoteRenderer?.release() } catch (_: Exception) {}
        peerConnection = null
    }

    // ── PeerConnectionFactory + PeerConnection ────────────────────────────────

    private fun buildFactory(): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val encoder = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection() {
        // LOCAL rail → iceServers is empty (same-subnet host candidates only).
        // GLOBAL rail → iceServers carries the Sovereign TURN relay so NATed peers
        // bounce encrypted media through the AWS Alpha Router.
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                // Pipe our local ICE candidate across the sovereign TCP socket.
                signaling.sendIceCandidate(c.sdpMid ?: "", c.sdpMLineIndex, c.sdp)
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "[RTC] ICE state → $state")
                onConnectionStateChange(state)
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    remoteRenderer?.let { track.addSink(it) }
                    onRemoteVideo()
                }
            }
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { vt ->
                    remoteVideoTrack = vt
                    remoteRenderer?.let { vt.addSink(it) }
                    onRemoteVideo()
                }
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    // ── Local media (mic + front camera) ──────────────────────────────────────

    private fun addLocalMedia() {
        // Audio
        localAudioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("dltn_audio", localAudioSource).apply { setEnabled(true) }
        peerConnection?.addTrack(localAudioTrack, listOf(STREAM_ID))

        // Video (front camera). The m=video line is negotiated up front so the
        // call can be UPGRADED to video later without renegotiation — but the track
        // starts DISABLED and the camera is NOT opened until setVideoEnabled(true).
        // This keeps a voice call a voice call (no camera light, no preview).
        val capturer = createFrontCameraCapturer()
        if (capturer != null) {
            videoCapturer = capturer
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
            localVideoSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(surfaceHelper, context, localVideoSource!!.capturerObserver)
            localVideoTrack = factory.createVideoTrack("dltn_video", localVideoSource).apply { setEnabled(false) }
            localRenderer?.let { localVideoTrack?.addSink(it) }
            peerConnection?.addTrack(localVideoTrack, listOf(STREAM_ID))
            // No startCapture() here — voice-first.
        } else {
            Log.w(TAG, "[RTC] no front camera — audio only")
        }
    }

    private fun startCapture() {
        try { videoCapturer?.startCapture(1280, 720, 30) }
        catch (e: Exception) { Log.w(TAG, "[RTC] startCapture: ${e.message}") }
    }

    private fun createFrontCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        names.firstOrNull { enumerator.isFrontFacing(it) }?.let { return enumerator.createCapturer(it, null) }
        names.firstOrNull()?.let { return enumerator.createCapturer(it, null) }
        return null
    }

    // ── SignalingClientListener — inbound handshake over the TCP socket ─────────

    override fun onConnectionEstablished() {
        if (isCaller) createOffer()
    }

    override fun onOfferReceived(sdp: String) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(SdpObserverAdapter(), SessionDescription(SessionDescription.Type.OFFER, sdp))
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                signaling.sendAnswer(desc.description)
            }
        }, MediaConstraints())
    }

    override fun onAnswerReceived(sdp: String) {
        peerConnection?.setRemoteDescription(
            SdpObserverAdapter(), SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    override fun onCallEnded() { close() }
    override fun onError(reason: String) { Log.w(TAG, "[RTC] signaling error: $reason") }

    private fun createOffer() {
        val pc = peerConnection ?: return
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                signaling.sendOffer(desc.description)
            }
        }, MediaConstraints())
    }
}
