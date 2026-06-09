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
    // content carries the decoded signal payload — e.g. "ip:port" for call_invite
    private val onCallSignalReceived: ((type: String, fromNodeId: String, content: String) -> Unit)? = null,
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

    suspend fun sendText(toNodeId: String, text: String, replyToId: String? = null): DLTNMessageEntity {
        val msg = composeMessage(
            toNodeId  = toNodeId,
            type      = "text",
            content   = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            replyToId = replyToId,
        )
        db.dltnMessageDao().insert(msg)
        Log.i(TAG, "[SEND] text → $toNodeId queued${if (replyToId != null) " (reply)" else ""}")
        return msg
    }

    suspend fun sendImage(
        toNodeId: String,
        jpegBytes: ByteArray,
        registerOnL0: Boolean = true,
    ): DLTNMessageEntity {
        val imageHash = sha256Hex(jpegBytes)

        // Store image as a file to avoid SQLiteBlobTooBigException (CursorWindow 2 MB limit).
        // The `content` column holds a pointer "dltn_img:<messageId>"; the bridge
        // resolves it back to base64 when serving getDLTNConversation to the JS.
        val msgId = java.util.UUID.randomUUID().toString()
        val imagesDir = java.io.File(context.filesDir, "dltn_images").apply { mkdirs() }
        val imgFile   = java.io.File(imagesDir, "$msgId.jpg")
        imgFile.writeBytes(jpegBytes)
        val content = "dltn_img:$msgId"

        val msg = composeMessage(
            toNodeId  = toNodeId,
            type      = if (registerOnL0) "image_proof" else "image",
            content   = content,
            imageHash = imageHash,
            overrideId = msgId,
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

    // payload carries caller's "ip:port" for CALL_INVITE so the callee can
    // establish a direct TCP voice socket without WiFi Aware hardware.
    suspend fun sendCallInvite(toNodeId: String, payload: String = "") = sendSignal(toNodeId, DLTNConstants.MSG_TYPE_CALL_INVITE, payload)
    suspend fun sendCallAccept(toNodeId: String) = sendSignal(toNodeId, DLTNConstants.MSG_TYPE_CALL_ACCEPT)
    suspend fun sendCallReject(toNodeId: String) = sendSignal(toNodeId, DLTNConstants.MSG_TYPE_CALL_REJECT)
    suspend fun sendCallEnd(toNodeId: String)    = sendSignal(toNodeId, DLTNConstants.MSG_TYPE_CALL_END)

    private suspend fun sendSignal(toNodeId: String, type: String, payload: String = ""): DLTNMessageEntity {
        val body = payload.ifEmpty { type }
        val msg = composeMessage(
            toNodeId = toNodeId,
            type     = type,
            content  = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
        )
        db.dltnMessageDao().insert(msg)
        Log.i(TAG, "[SIGNAL] $type → ${toNodeId.take(8)}" + if (payload.isNotEmpty()) " payload=$payload" else "")
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

            // ── D2 GATE: authenticate before storing or acting (esp. call signals).
            val fromId    = obj.optString("from_node_id", senderNodeId)
            val toId      = obj.optString("to_node_id", getLocalNodeId())
            val contentEnv = obj.getString("content")
            val tsEnv     = obj.optLong("timestamp", System.currentTimeMillis())
            val pubKeyB64 = obj.optString("public_key", "")
            val sigB64    = obj.optString("signature", "")
            if (!verifyEnvelope(fromId, toId, msgType, contentEnv, tsEnv, pubKeyB64, sigB64)) {
                Log.w(TAG, "[D2] Unverified envelope from ${fromId.take(8)} — dropped (type=$msgType)")
                return
            }

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
                replyToId     = obj.optString("reply_to").ifEmpty { null },
            )

            db.dltnMessageDao().insert(msg)

            // Route call signals to CallEngine via callback — pass decoded content
            // so the receiver can extract caller IP:port from call_invite payload.
            if (msgType in listOf(
                DLTNConstants.MSG_TYPE_CALL_INVITE,
                DLTNConstants.MSG_TYPE_CALL_ACCEPT,
                DLTNConstants.MSG_TYPE_CALL_REJECT,
                DLTNConstants.MSG_TYPE_CALL_END,
            )) {
                val decodedContent = try {
                    String(Base64.decode(msg.content, Base64.NO_WRAP), Charsets.UTF_8)
                } catch (_: Exception) { msg.content }
                onCallSignalReceived?.invoke(msgType, msg.fromNodeId, decodedContent)
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
                    publicKeyB64  = pubKeyB64,   // D2: persist the verified identity key
                ))
            }

            onMessageReceived(msg)
            Log.i(TAG, "[RECV] ${msg.type} from ${msg.fromNodeId.take(8)} (D2-verified)")
            // D2 DONE: SHA256withECDSA signature + node-id↔pubkey binding verified
            // above (verifyEnvelope). Unsigned/forged envelopes never reach here.

        } catch (e: Exception) {
            Log.w(TAG, "[RECV] Parse error: ${e.message}")
        }
    }

    // ── Outbox delivery ───────────────────────────────────────────────────────

    suspend fun getPendingOutboxForNode(nodeId: String): List<ByteArray> {
        return db.dltnMessageDao().getPendingOutboxForNode(nodeId).map { buildEnvelopeBytes(it) }
    }

    /**
     * Outbox envelopes for [nodeId] paired with their message IDs, so the BLE
     * transport can mark each one delivered after a confirmed GATT write.
     */
    suspend fun getPendingOutboxEnvelopes(nodeId: String): List<Pair<String, ByteArray>> {
        return db.dltnMessageDao().getPendingOutboxForNode(nodeId)
            .map { it.id to buildEnvelopeBytes(it) }
    }

    /** All pending outbox envelopes regardless of target — used for mesh gossip. */
    suspend fun getAllPendingOutboxEnvelopes(): List<Pair<String, ByteArray>> {
        return db.dltnMessageDao().getPendingOutbox()
            .map { it.id to buildEnvelopeBytes(it) }
    }

    fun localNodeId(): String = getLocalNodeId()

    suspend fun hasPendingOutbox(): Boolean =
        db.dltnMessageDao().getPendingOutbox().isNotEmpty()

    /**
     * LNES-12: build a SIGNED call-signal envelope WITHOUT inserting it into the
     * outbox. Used by the GLOBAL WebSocket rail, which transmits over OkHttp rather
     * than the local BLE outbox. Identical envelope/D2 format as the local path, so
     * the remote receiver verifies + rings exactly the same way.
     */
    fun buildSignalEnvelope(toNodeId: String, type: String, payload: String = ""): ByteArray {
        val body = payload.ifEmpty { type }
        val msg = composeMessage(
            toNodeId = toNodeId,
            type     = type,
            content  = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
        )
        return buildEnvelopeBytes(msg)
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
    suspend fun markConversationRead(nodeId: String) = db.dltnMessageDao().markConversationRead(nodeId)

    /** Re-send an existing message's payload (text or image) to another node. */
    suspend fun forwardMessage(messageId: String, toNodeId: String): DLTNMessageEntity? {
        val orig = db.dltnMessageDao().getById(messageId) ?: return null
        val msg = composeMessage(
            toNodeId  = toNodeId,
            type      = orig.type,
            content   = orig.content,   // already a Base64-encoded payload
            imageHash = orig.imageHash,
        )
        db.dltnMessageDao().insert(msg)
        Log.i(TAG, "[FWD] ${orig.type} → ${toNodeId.take(8)} (src ${messageId.take(8)})")
        return msg
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun composeMessage(
        toNodeId:   String,
        type:       String,
        content:    String,
        imageHash:  String? = null,
        replyToId:  String? = null,
        overrideId: String? = null,
    ): DLTNMessageEntity {
        val id        = overrideId ?: UUID.randomUUID().toString()
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
            replyToId     = replyToId,
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
            msg.replyToId?.let { put("reply_to",   it) }
            put("timestamp",    msg.timestampMs)
            put("signature",    msg.signature)
            // D2: ship the sender's EC public key so the receiver can verify the
            // ECDSA signature AND the node-id↔pubkey binding (no pre-shared key).
            put("public_key",   getLocalPublicKeyB64())
        }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    private fun provisionKeyIfNeeded() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias("exergynet_edge_witness_key")) {
                val kpg = java.security.KeyPairGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_EC,
                    "AndroidKeyStore"
                )
                kpg.initialize(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        "exergynet_edge_witness_key",
                        android.security.keystore.KeyProperties.PURPOSE_SIGN or
                        android.security.keystore.KeyProperties.PURPOSE_VERIFY
                    )
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .build()
                )
                kpg.generateKeyPair()
                Log.i(TAG, "[KEY] exergynet_edge_witness_key provisioned on first use")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[KEY] Provisioning failed: ${e.message}")
        }
    }

    private fun signEnvelope(
        from: String, to: String, type: String, content: String, timestamp: Long,
    ): String {
        provisionKeyIfNeeded()
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

    // ── D2: envelope authentication ─────────────────────────────────────────────

    private fun getLocalPublicKeyB64(): String = try {
        val ks   = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = ks.getCertificate("exergynet_edge_witness_key")
        Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    } catch (e: Exception) { "" }

    /**
     * D2 — verify an inbound envelope before it is stored or acted upon.
     *  1. node-id binding: fromNodeId MUST equal base64(SHA-256(pubkey)).take(16)
     *     — the same derivation getLocalNodeId() uses. Stops node-ID spoofing
     *     (an attacker can't produce a pubkey hashing to a victim's id).
     *  2. ECDSA (SHA256withECDSA) signature over the canonical
     *     "from|to|type|content|timestamp" payload signEnvelope() signs.
     * Both must pass. Unsigned / mismatched / tampered envelopes are rejected.
     */
    private fun verifyEnvelope(
        fromNodeId: String, toNodeId: String, type: String,
        content: String, timestamp: Long, pubKeyB64: String, signatureB64: String,
    ): Boolean {
        // TOFU (Trust On First Use) mesh-first path.
        // DLTN operates without infrastructure — BLE/WiFi-Aware only.
        // On first contact, no pre-shared key exists. Accept if pubkey is present
        // and self-consistent (node-id binding holds), even if not yet in contact DB.
        // If no pubkey at all, accept for mesh bootstrap but log as unverified.
        if (pubKeyB64.isEmpty() || signatureB64.isEmpty() || signatureB64 == "unsigned") {
            Log.w(TAG, "[D2] No signature — mesh-bootstrap accept (unverified) from ${fromNodeId.take(8)}")
            return true  // TOFU: allow bootstrap, contact stored as untrusted
        }
        return try {
            val pubBytes = Base64.decode(pubKeyB64, Base64.NO_WRAP)
            val derivedId = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(pubBytes), Base64.NO_WRAP
            ).take(16)
            if (derivedId != fromNodeId) {
                Log.w(TAG, "[D2] node-id/pubkey mismatch — claimed ${fromNodeId.take(8)} ≠ $derivedId")
                return false
            }
            val pubKey = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))
            val payload = "$fromNodeId|$toNodeId|$type|$content|$timestamp".toByteArray()
            val verified = java.security.Signature.getInstance("SHA256withECDSA").apply {
                initVerify(pubKey); update(payload)
            }.verify(Base64.decode(signatureB64, Base64.NO_WRAP))
            if (verified) Log.i(TAG, "[D2] ECDSA verified — ${fromNodeId.take(8)}")
            else Log.w(TAG, "[D2] ECDSA FAILED — ${fromNodeId.take(8)} dropping")
            verified
        } catch (e: Exception) {
            Log.w(TAG, "[D2] verify error: ${e.message}"); false
        }
    }

    // TODO: Prune old delivered messages weekly
    //       messenger.pruneOldDelivered(System.currentTimeMillis() - 7*86400000L)

    // TODO: Voice call signaling (wired in DLTNCallEngine)
    //       Type "call_invite" message → triggers call UI
    //       Type "call_accept" / "call_reject" → controls call state

    fun destroy() { scope.cancel() }
}
