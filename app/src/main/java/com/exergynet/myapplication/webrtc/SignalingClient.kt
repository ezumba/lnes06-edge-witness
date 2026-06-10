package com.exergynet.myapplication.webrtc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notifies the [WebRtcClient] of inbound signaling events. Background thread.
 */
interface SignalingClientListener {
    fun onConnectionEstablished()
    fun onOfferReceived(sdp: String)
    fun onAnswerReceived(sdp: String)
    fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String)
    fun onCallEnded()
    fun onError(reason: String)
}

/**
 * Transport-agnostic signaling channel. WebRtcClient talks to this; the concrete
 * implementation decides whether SDP/ICE travels over the LOCAL P2P TCP socket
 * ([SignalingClient]) or the GLOBAL LNES-12 WebSocket ([WsSignalingChannel]).
 */
interface SignalingChannel {
    var listener: SignalingClientListener?
    fun start()
    fun sendOffer(sdp: String)
    fun sendAnswer(sdp: String)
    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String)
    fun sendBye()
    fun close()
}

/** Frame helpers shared by both channels (newline/standalone JSON). */
internal object SignalingFrames {
    fun offer(sdp: String) = JSONObject().put("type", "OFFER").put("sdp", sdp)
    fun answer(sdp: String) = JSONObject().put("type", "ANSWER").put("sdp", sdp)
    fun ice(mid: String, idx: Int, cand: String) = JSONObject()
        .put("type", "ICE").put("sdpMid", mid).put("sdpMLineIndex", idx).put("candidate", cand)
    fun bye() = JSONObject().put("type", "BYE")

    fun dispatch(line: String, listener: SignalingClientListener?) {
        if (listener == null) return
        val o = JSONObject(line)
        when (o.optString("type")) {
            "OFFER"  -> listener.onOfferReceived(o.optString("sdp"))
            "ANSWER" -> listener.onAnswerReceived(o.optString("sdp"))
            "ICE"    -> listener.onIceCandidateReceived(
                o.optString("sdpMid"), o.optInt("sdpMLineIndex"), o.optString("candidate"))
            "BYE"    -> listener.onCallEnded()
        }
    }
}

/**
 * LOCAL signaling over a raw TCP [Socket] (the DLTNCallEngine peer-to-peer socket).
 * Newline-delimited JSON frames. No external server.
 */
class SignalingClient(
    private val socket: Socket,
    override var listener: SignalingClientListener? = null,
) : SignalingChannel {
    private val TAG = "SignalingClient"
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writer by lazy { socket.getOutputStream().bufferedWriter() }

    override fun start() {
        if (running.getAndSet(true)) return
        listener?.onConnectionEstablished()
        scope.launch { readLoop() }
    }

    private fun readLoop() {
        try {
            val reader = socket.getInputStream().bufferedReader()
            while (running.get()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try { SignalingFrames.dispatch(line, listener) }
                catch (e: Exception) { listener?.onError("bad frame: ${e.message}") }
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "[SIG] read ended: ${e.message}")
        } finally {
            if (running.getAndSet(false)) listener?.onCallEnded()
        }
    }

    @Synchronized private fun send(o: JSONObject) {
        try { writer.write(o.toString()); writer.write("\n"); writer.flush() }
        catch (e: Exception) { Log.w(TAG, "[SIG] send failed: ${e.message}") }
    }

    override fun sendOffer(sdp: String) = send(SignalingFrames.offer(sdp))
    override fun sendAnswer(sdp: String) = send(SignalingFrames.answer(sdp))
    override fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) =
        send(SignalingFrames.ice(sdpMid, sdpMLineIndex, candidate))
    override fun sendBye() = send(SignalingFrames.bye())

    override fun close() {
        running.set(false)
        try { socket.close() } catch (_: Exception) {}
        scope.cancel()
    }
}

/**
 * GLOBAL signaling over the LNES-12 WebSocket. Outbound frames are pushed to the
 * peer via [out] (GlobalMeshService.sendRtcSignal); inbound frames arrive out-of-band
 * (GlobalMeshService routes them) and are injected via [onRemote].
 */
class WsSignalingChannel(
    private val out: (rtcJson: String) -> Unit,
    override var listener: SignalingClientListener? = null,
) : SignalingChannel {
    override fun start() { listener?.onConnectionEstablished() }
    override fun sendOffer(sdp: String) = out(SignalingFrames.offer(sdp).toString())
    override fun sendAnswer(sdp: String) = out(SignalingFrames.answer(sdp).toString())
    override fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) =
        out(SignalingFrames.ice(sdpMid, sdpMLineIndex, candidate).toString())
    override fun sendBye() = out(SignalingFrames.bye().toString())
    override fun close() {}

    /** Called by the call engine when a WebRTC signaling frame arrives over the WS. */
    fun onRemote(rtcJson: String) {
        try { SignalingFrames.dispatch(rtcJson, listener) }
        catch (e: Exception) { listener?.onError("bad ws frame: ${e.message}") }
    }
}
