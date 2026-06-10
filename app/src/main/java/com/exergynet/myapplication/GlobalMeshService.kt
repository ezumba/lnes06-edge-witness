package com.exergynet.myapplication

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LNES-12 — Sovereign WebSocket Relay (GLOBAL fallback rail).
 *
 * ════════════════════════════════════════════════════════════════════════════
 * STRICTLY ADDITIVE. The local BLE / WiFi-Aware DLTN mesh (DLTNManager) is the
 * PRIMARY path and is NOT touched. This class is a second, parallel rail that
 * carries calls beyond BLE range (e.g. Indiana ↔ Pennsylvania) by relaying
 * through the L0 Switchboard over a single persistent OkHttp WebSocket.
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Media (audio + video) is carried entirely by WebRTC via [WebRtcClient].
 *
 * Relay wire protocol:
 *   • TEXT frame   : a DLTN call envelope JSON with an added top-level
 *                    "target_id" (the peer node-id). The relay forwards the text
 *                    verbatim to target_id's socket. The receiver feeds it into
 *                    DLTNMessenger.receiveRawPayload → the UI rings like a local call.
 *   • BINARY frame : forwarded-media frame from the relay. Unused by this client —
 *                    WebRTC carries all media on its own encrypted transport.
 */
class GlobalMeshService(
    private val myNodeId: String,
    /** Received signaling envelope (JSON bytes) → route to messenger for ringing. */
    private val onSignalEnvelope: (ByteArray) -> Unit,
) {
    private val TAG = "GlobalMesh"

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)   // keep the relay socket warm (no FCM)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    private val shouldRun = AtomicBoolean(false)

    /** LNES-12 global WebRTC signaling: inbound SDP/ICE frames from a peer. */
    @Volatile var onRtcSignal: ((fromId: String, rtcJson: String) -> Unit)? = null

    /**
     * Inbound binary frame callback: fires with (senderId, payload) when a
     * 64-byte-headered binary frame arrives. Consumers index decoders by senderId.
     */
    @Volatile var onBinaryFrame: ((senderId: String, payload: ByteArray) -> Unit)? = null

    fun isConnected(): Boolean = connected

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun connect() {
        if (shouldRun.getAndSet(true)) return
        openSocket()
    }

    private fun openSocket() {
        if (!shouldRun.get()) return
        if (myNodeId.isBlank()) { Log.w(TAG, "[WS] no node id — global rail not started"); return }
        val url = DLTNConstants.GLOBAL_MESH_WS_BASE + myNodeId
        Log.i(TAG, "[WS] connecting → $url")
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                Log.i(TAG, "[WS] OPEN — global rail live for ${myNodeId.take(8)}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    // WebRTC signaling frames (SDP/ICE) are tagged kind=webrtc and
                    // routed to the call engine; everything else is a DLTN envelope
                    // (messages + CALL_INVITE/ACCEPT/END) for the messenger pipeline.
                    val o = org.json.JSONObject(text)
                    if (o.optString("kind") == "webrtc") {
                        onRtcSignal?.invoke(o.optString("from_id"), o.optString("rtc"))
                        return
                    }
                } catch (_: Exception) { /* not JSON-with-kind — fall through */ }
                try { onSignalEnvelope(text.toByteArray(Charsets.UTF_8)) }
                catch (e: Exception) { Log.w(TAG, "[WS] signal route error: ${e.message}") }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary frames carry the 64-byte dual-ID header defined in LNES-12:
                //   [0..43]  SENDER_ID  (space-padded UTF-8)
                //   [44..63] TARGET_ID or GRP_ROOM_ID (space-padded UTF-8)
                //   [64..]   payload (H.264 NAL unit, PCM chunk, or future codec)
                val raw = bytes.toByteArray()
                if (raw.size < 64) return   // malformed — discard
                try {
                    val (senderId, _) = parseBinaryHeader(raw)
                    val payload = raw.sliceArray(64 until raw.size)
                    onBinaryFrame?.invoke(senderId, payload)
                } catch (e: Exception) {
                    Log.w(TAG, "[WS] binary frame parse error: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                try { webSocket.close(1000, null) } catch (_: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                Log.w(TAG, "[WS] failure: ${t.message} — reconnecting in 5s")
                if (shouldRun.get()) {
                    Thread {
                        try { Thread.sleep(5000) } catch (_: Exception) {}
                        openSocket()
                    }.start()
                }
            }
        })
    }

    fun disconnect() {
        shouldRun.set(false)
        try { ws?.close(1000, "shutdown") } catch (_: Exception) {}
        ws = null
        connected = false
    }

    // ── Signaling ─────────────────────────────────────────────────────────────

    /** Push a WebRTC signaling frame (SDP/ICE JSON) to [targetNodeId] over the WS. */
    fun sendRtcSignal(targetNodeId: String, rtcJson: String): Boolean {
        val sock = ws ?: return false
        return try {
            val frame = JSONObject()
                .put("kind", "webrtc")
                .put("target_id", targetNodeId)
                .put("from_id", myNodeId)
                .put("rtc", rtcJson)
            sock.send(frame.toString())
        } catch (e: Exception) {
            Log.w(TAG, "[WS] sendRtcSignal error: ${e.message}"); false
        }
    }

    /** Send a signed DLTN call envelope to [targetNodeId] over the global rail. */
    fun sendSignalEnvelope(targetNodeId: String, envelopeBytes: ByteArray): Boolean {
        val sock = ws ?: return false
        return try {
            val obj = JSONObject(String(envelopeBytes, Charsets.UTF_8))
            obj.put("target_id", targetNodeId)   // relay routing key
            sock.send(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "[WS] sendSignalEnvelope error: ${e.message}")
            false
        }
    }

    // ── Group Room Management ─────────────────────────────────────────────────

    /** POST /api/v1/mesh/group/{roomId}/join/{nodeId} on the Apex Router. */
    fun joinGroup(roomId: String, nodeId: String) {
        Thread {
            try {
                val url = "${DLTNConstants.APEX_BASE_URL}/api/v1/mesh/group/$roomId/join/$nodeId"
                val req = Request.Builder().url(url)
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    Log.i(TAG, "[GROUP] join $roomId / $nodeId → ${resp.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[GROUP] join failed: ${e.message}")
            }
        }.start()
    }

    // ── Binary Frame Protocol (LNES-12 Dual-ID Header) ────────────────────────

    /**
     * Build a 64-byte framed binary packet:
     *   Bytes  [0..43]  : SENDER_ID (this node), space-padded to 44 bytes
     *   Bytes [44..63]  : TARGET_ID or GRP_ROOM_ID, space-padded to 20 bytes
     *   Bytes [64..]    : payload
     */
    fun buildBinaryFrame(targetOrRoomId: String, payload: ByteArray): ByteArray {
        // Fixed 64-byte header: [0..43] SENDER_ID, [44..63] TARGET_ID, [64..] payload.
        // System.arraycopy into a zero-filled ByteArray guarantees byte-offset invariance
        // regardless of UTF-8 multi-byte characters in any node ID — no padEnd/String tricks.
        val frame = ByteArray(64 + payload.size) // zero-initialized — null bytes pad remaining space
        val senderBytes = myNodeId.toByteArray(Charsets.UTF_8)
        val targetBytes = targetOrRoomId.toByteArray(Charsets.UTF_8)
        System.arraycopy(senderBytes, 0, frame, 0,  minOf(senderBytes.size, 44))
        System.arraycopy(targetBytes, 0, frame, 44, minOf(targetBytes.size, 20))
        System.arraycopy(payload,     0, frame, 64, payload.size)
        return frame
    }

    /**
     * Parse the 64-byte header, returning (senderId, targetId) with whitespace trimmed.
     * Throws if the frame is shorter than 64 bytes.
     */
    fun parseBinaryHeader(frame: ByteArray): Pair<String, String> {
        require(frame.size >= 64) { "frame too short (${frame.size})" }
        val senderId = String(frame.sliceArray(0 until 44), Charsets.UTF_8).trim()
        val targetId = String(frame.sliceArray(44 until 64), Charsets.UTF_8).trim()
        return Pair(senderId, targetId)
    }

    /** Send a binary payload with the 64-byte header over the WebSocket. */
    fun sendBinaryFrame(targetOrRoomId: String, payload: ByteArray): Boolean {
        val sock = ws ?: return false
        return try {
            val frame = buildBinaryFrame(targetOrRoomId, payload)
            sock.send(ByteString.of(*frame))
        } catch (e: Exception) {
            Log.w(TAG, "[WS] sendBinaryFrame error: ${e.message}"); false
        }
    }

    /** POST /api/v1/mesh/group/{roomId}/leave/{nodeId} on the Apex Router. */
    fun leaveGroup(roomId: String, nodeId: String) {
        Thread {
            try {
                val url = "${DLTNConstants.APEX_BASE_URL}/api/v1/mesh/group/$roomId/leave/$nodeId"
                val req = Request.Builder().url(url)
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    Log.i(TAG, "[GROUP] leave $roomId / $nodeId → ${resp.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[GROUP] leave failed: ${e.message}")
            }
        }.start()
    }
}
