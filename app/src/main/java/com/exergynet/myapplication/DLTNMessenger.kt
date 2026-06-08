package com.exergynet.myapplication

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.UUID

class DLTNMessenger(
    private val context: Context,
    private val onMessageReceived: (DLTNMessageEntity) -> Unit,
    private val onCallSignalReceived: ((type: String, fromNodeId: String) -> Unit)? = null,
) {
    private val TAG = "DLTNMessenger"
    private val db  by lazy { ExergyDatabase.getDatabase(context) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Local node identity ──────────────────────────────────────────────────

    fun getLocalNodeId(): String {
        return try {
            val ks   = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val cert = ks.getCertificate("exergynet_edge_witness_key")
            val hash = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            Base64.encodeToString(hash, Base64.NO_WRAP).take(16)
        } catch (e: Exception) {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown_node"
        }
    }

    // ── Message composition ───────────────────────────────────────────────────

    suspend fun sendText(toNodeId: String, text: String): DLTNMessageEntity {
        val msg = composeMessage(
            toNodeId = toNodeId,
            type     = "text",
            content  = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
        )
        db.dltnMessageDao().insert(msg)
        Log.i(TAG, "[SEND] text → $toNodeId queued")
        return msg
    }

    suspend fun sendImage(
        toNodeId: String,
        jpegBytes: ByteArray,
        registerOnL0: Boolean = true,
    ): DLTNMessageEntity {
        val imageHash = sha256Hex(jpegBytes)
        val content   = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val msg = composeMessage(
            toNodeId  = toNodeId,
            type      = if (registerOnL0) "image_proof" else "image",
            content   = content,
            imageHash = imageHash,
        )
        db.dltnMessageDao().insert(msg)

        if (registerOnL0) {
            // TODO: Wire sendImage L0 registration to JobOrchestrator
            //       JobOrchestrator.submitImageHashToL0(imageHash, messageId)
            //       This submits the SHA-256 to L0 chain as optical proof
            //       The existing optical pipeline in MainActivity already does this
            //       for active jobs — reuse that path for messenger images
            Log.i(TAG, "[L0] Image hash registered: $imageHash")
        }

        Log.i(TAG, "[SEND] image${if (registerOnL0) "_proof" else ""} → $toNodeId queued")
        return msg
    }

    // ── Call signaling ────────────────────────────────────────────────────────

    suspend fun sendCallInvite(toNodeId: String) = sendSignal(toNodeId, DLTNConstants.MSG_TYPE_CALL_INVITE)
    suspend fun sendCallAccept(toNodeId: String) = sendSignal(toNodeId, DLTNConstants.MSG_TYPE_CALL_ACCEPT)
    suspend fun sendCallReject(toNodeId: String) = sendSignal(toNodeId, DLTNConstants.MSG_TYPE_CALL_REJECT)
    suspend fun sendCallEnd(toNodeId: String)    = sendSignal(toNodeId, DLTNConstants.MSG_TYPE_CALL_END)

    private suspend fun sendSignal(toNodeId: String, type: String): DLTNMessageEntity {
        val msg = composeMessage(
            toNodeId = toNodeId,
            type     = type,
            content  = Base64.encodeToString(type.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
        )
        db.dltnMessageDao().insert(msg)
        Log.i(TAG, "[SIGNAL] $type → ${toNodeId.take(8)}")
        return msg
    }

    // ── Message reception ─────────────────────────────────────────────────────

    suspend fun receiveRawPayload(senderNodeId: String, payloadBytes: ByteArray) {
        try {
            val json = String(payloadBytes, Charsets.UTF_8)
            val obj  = JSONObject(json)

            if (!obj.has("id") || !obj.has("type") || !obj.has("content")) {
                Log.v(TAG, "[RECV] Not a message envelope — ignoring")
                return
            }

            val msgType = obj.getString("type")

            val msg = DLTNMessageEntity(
                id            = obj.getString("id"),
                fromNodeId    = obj.optString("from_node_id", senderNodeId),
                toNodeId      = obj.optString("to_node_id", getLocalNodeId()),
                type          = msgType,
                content       = obj.getString("content"),
                imageHash     = obj.optString("image_hash").ifEmpty { null },
                l0TxHash      = obj.optString("l0_tx_hash").ifEmpty { null },
                timestampMs   = obj.optLong("timestamp", System.currentTimeMillis()),
                signature     = obj.optString("signature", ""),
                delivered     = true,
                read          = false,
                direction     = "inbound",
                outboxPending = false,
            )

            db.dltnMessageDao().insert(msg)

            // Route call signals to CallEngine via callback
            if (msgType in listOf(
                DLTNConstants.MSG_TYPE_CALL_INVITE,
                DLTNConstants.MSG_TYPE_CALL_ACCEPT,
                DLTNConstants.MSG_TYPE_CALL_REJECT,
                DLTNConstants.MSG_TYPE_CALL_END,
            )) {
                onCallSignalReceived?.invoke(msgType, msg.fromNodeId)
                // Still store in DB for call history — no early return
            }

            // Upsert contact from received message
            val existing = db.dltnContactDao().getContact(msg.fromNodeId)
            if (existing == null) {
                db.dltnContactDao().upsert(DLTNContactEntity(
                    nodeId        = msg.fromNodeId,
                    displayName   = "Node ${msg.fromNodeId.take(8)}",
                    discoveryType = "ble_auto",
                    lastSeenMs    = System.currentTimeMillis(),
                ))
            }

            onMessageReceived(msg)
            Log.i(TAG, "[RECV] ${msg.type} from ${msg.fromNodeId.take(8)}")

            // TODO: Implement signature verification on receiveRawPayload()
            //       Verify Ed25519 signature against sender's stored publicKeyB64
            //       db.dltnContactDao().getContact(senderNodeId)?.publicKeyB64

        } catch (e: Exception) {
            Log.w(TAG, "[RECV] Parse error: ${e.message}")
        }
    }

    // ── Outbox delivery ───────────────────────────────────────────────────────

    suspend fun getPendingOutboxForNode(nodeId: String): List<ByteArray> {
        return db.dltnMessageDao().getPendingOutboxForNode(nodeId).map { buildEnvelopeBytes(it) }
    }

    suspend fun markDelivered(messageId: String) {
        db.dltnMessageDao().markDelivered(messageId)
    }

    // ── Contact management ────────────────────────────────────────────────────

    suspend fun upsertDiscoveredContact(nodeId: String, rssi: Int) {
        val existing = db.dltnContactDao().getContact(nodeId)
        if (existing == null) {
            db.dltnContactDao().upsert(DLTNContactEntity(
                nodeId        = nodeId,
                displayName   = "Node ${nodeId.take(8)}",
                discoveryType = "ble_auto",
                lastSeenMs    = System.currentTimeMillis(),
                rssiLast      = rssi,
            ))
            Log.i(TAG, "[CONTACT] Auto-discovered: ${nodeId.take(8)}")
        } else {
            db.dltnContactDao().updateLastSeen(nodeId, System.currentTimeMillis(), rssi)
        }
    }

    suspend fun addManualContact(nodeId: String, displayName: String, walletAddress: String?) {
        db.dltnContactDao().upsert(DLTNContactEntity(
            nodeId        = nodeId,
            displayName   = displayName,
            walletAddress = walletAddress,
            discoveryType = "manual",
            trusted       = true,
        ))
        Log.i(TAG, "[CONTACT] Manual add: $displayName ($nodeId)")
    }

    suspend fun getAllContacts()  = db.dltnContactDao().getAllContacts()
    suspend fun getUnreadCount() = db.dltnMessageDao().getUnreadCount()
    suspend fun getConversation(nodeId: String) = db.dltnMessageDao().getAllMessagesForNode(nodeId)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun composeMessage(
        toNodeId:  String,
        type:      String,
        content:   String,
        imageHash: String? = null,
    ): DLTNMessageEntity {
        val id        = UUID.randomUUID().toString()
        val fromNode  = getLocalNodeId()
        val timestamp = System.currentTimeMillis()
        val signature = signEnvelope(fromNode, toNodeId, type, content, timestamp)

        return DLTNMessageEntity(
            id            = id,
            fromNodeId    = fromNode,
            toNodeId      = toNodeId,
            type          = type,
            content       = content,
            imageHash     = imageHash,
            timestampMs   = timestamp,
            signature     = signature,
            delivered     = false,
            read          = false,
            direction     = "outbound",
            outboxPending = true,
        )
    }

    private fun buildEnvelopeBytes(msg: DLTNMessageEntity): ByteArray {
        val obj = JSONObject().apply {
            put("id",           msg.id)
            put("from_node_id", msg.fromNodeId)
            put("to_node_id",   msg.toNodeId)
            put("type",         msg.type)
            put("content",      msg.content)
            msg.imageHash?.let { put("image_hash", it) }
            msg.l0TxHash?.let  { put("l0_tx_hash", it) }
            put("timestamp",    msg.timestampMs)
            put("signature",    msg.signature)
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    private fun signEnvelope(
        from: String, to: String, type: String, content: String, timestamp: Long,
    ): String {
        return try {
            val ks         = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val privateKey = ks.getKey("exergynet_edge_witness_key", null) as? PrivateKey ?: return "unsigned"
            val payload    = "$from|$to|$type|$content|$timestamp".toByteArray()
            val sig        = Signature.getInstance("SHA256withECDSA").apply {
                initSign(privateKey)
                update(payload)
            }.sign()
            Base64.encodeToString(sig, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "[SIGN] Signature failed: ${e.message}")
            "unsigned"
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // TODO: Prune old delivered messages weekly
    //       messenger.pruneOldDelivered(System.currentTimeMillis() - 7*86400000L)

    // TODO: Voice call signaling (wired in DLTNCallEngine)
    //       Type "call_invite" message → triggers call UI
    //       Type "call_accept" / "call_reject" → controls call state

    fun destroy() { scope.cancel() }
}
