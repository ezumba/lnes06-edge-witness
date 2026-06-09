package com.exergynet.myapplication

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
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
 * No WebRTC. No Firebase/FCM. Raw PCM audio is piped straight into the existing
 * DLTNAudioEngine through stream adapters.
 *
 * Relay wire protocol (see the Rust L0 patch):
 *   • TEXT frame   : a DLTN call envelope JSON with an added top-level
 *                    "target_id" (the peer node-id). The relay forwards the text
 *                    verbatim to target_id's socket. The receiver feeds it into
 *                    DLTNMessenger.receiveRawPayload → the UI rings like a local call.
 *   • BINARY frame : [16-byte ASCII target node-id, space-padded] + [PCM audio].
 *                    The relay reads the 16-byte routing header and forwards the
 *                    remaining audio bytes (header stripped) to the target.
 *
 * The audio framing inside the payload is the same [Int len][len bytes] format
 * the DLTNAudioEngine already uses, so no codec/transcode is involved.
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

    // Active-call audio routing state.
    @Volatile private var audioIn: QueueInputStream? = null

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
                // Signaling envelope (CALL_INVITE / ACCEPT / END). Route to the
                // messenger so the existing call pipeline rings / updates state.
                try { onSignalEnvelope(text.toByteArray(Charsets.UTF_8)) }
                catch (e: Exception) { Log.w(TAG, "[WS] signal route error: ${e.message}") }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Audio payload — relay has already stripped the 16-byte routing
                // header, so these are raw [Int len][len bytes] audio frames.
                audioIn?.feed(bytes.toByteArray())
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
        closeAudioBridge()
    }

    // ── Signaling ─────────────────────────────────────────────────────────────

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

    // ── Audio bridge ────────────────────────────────────────────────────────

    /**
     * Open stream adapters that pipe the DLTNAudioEngine over the WebSocket.
     * Returns (input, output) to hand directly to DLTNAudioEngine.start(input, output).
     * Returns null if the global rail is not connected.
     */
    fun openAudioBridge(peerNodeId: String): Pair<InputStream, OutputStream>? {
        if (!connected) return null
        closeAudioBridge()
        val qin = QueueInputStream()
        audioIn = qin
        val out = WsAudioOutputStream(peerNodeId)
        Log.i(TAG, "[WS] audio bridge OPEN ↔ ${peerNodeId.take(8)}")
        return qin to out
    }

    fun closeAudioBridge() {
        audioIn?.close()
        audioIn = null
    }

    private fun header16(nodeId: String): ByteArray {
        val raw = nodeId.toByteArray(Charsets.US_ASCII)
        val h = ByteArray(16) { 0x20 }   // space-padded fixed 16-byte routing header
        System.arraycopy(raw, 0, h, 0, minOf(raw.size, 16))
        return h
    }

    /**
     * OutputStream the audio engine writes PCM frames into. The engine emits one
     * [Int len][len bytes] frame per flush(); each flush is sent as a single WS
     * binary frame prefixed with the 16-byte routing header.
     */
    private inner class WsAudioOutputStream(private val targetId: String) : OutputStream() {
        private val buf = java.io.ByteArrayOutputStream(2048)
        override fun write(b: Int) { buf.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { buf.write(b, off, len) }
        override fun flush() {
            val payload = buf.toByteArray()
            buf.reset()
            if (payload.isEmpty()) return
            val sock = ws ?: return
            val frame = header16(targetId) + payload
            try { sock.send(frame.toByteString()) } catch (_: Exception) {}
        }
        override fun close() { try { flush() } catch (_: Exception) {} }
    }

    /**
     * Blocking InputStream backed by a queue of byte chunks fed by inbound WS
     * binary frames. The audio playback loop reads framed PCM from it. Reads
     * return -1 once closed so the engine's loop exits cleanly on hang-up.
     */
    private class QueueInputStream : InputStream() {
        private val q = LinkedBlockingQueue<ByteArray>()
        private var cur: ByteArray? = null
        private var pos = 0
        @Volatile private var closed = false

        fun feed(b: ByteArray) { if (!closed && b.isNotEmpty()) q.offer(b) }

        private fun ensure(): Boolean {
            while ((cur == null || pos >= cur!!.size) && !closed) {
                val next = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                cur = next; pos = 0
            }
            return !closed && cur != null && pos < cur!!.size
        }

        override fun read(): Int {
            if (!ensure()) return -1
            return cur!![pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensure()) return -1
            val n = minOf(len, cur!!.size - pos)
            System.arraycopy(cur!!, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() { closed = true; q.clear() }
    }
}
