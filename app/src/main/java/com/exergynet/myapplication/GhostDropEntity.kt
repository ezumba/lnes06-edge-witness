package com.exergynet.myapplication

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * LNES-10 Ghost Drop — local persistence layer.
 *
 * Every sealed drop is written here FIRST before any network attempt.
 * syncStatus drives the upload lifecycle:
 *
 *   PENDING  → created locally; not yet confirmed by the L0 Router.
 *   SYNCED   → L0 Router accepted; dropId holds the server-assigned ID.
 *   RELAYED  → uploaded by a peer node that received this via DLTN mesh relay.
 *
 * The [attachmentJson] column stores the raw attachment JSON string (or "") so
 * the WorkManager can reconstruct the full POST body without the original activity.
 */
@Entity(tableName = "ghost_drops")
data class GhostDropEntity(
    @PrimaryKey val localId: String,       // UUID, generated at creation
    val dropId: String = "",               // server-assigned ID (empty until SYNCED)
    val message: String,
    val type: String,                      // "open" | "closed"
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val ttlSecs: Int,
    val groups: String = "",
    val category: String = "INTEL",
    val timestamp: Long,
    val nonceHex: String,
    val signature: String,                 // StrongBox ECDSA sig, base64 no-wrap
    val minerId: String,
    val attachmentJson: String = "",       // raw JSON or ""
    val syncStatus: String = "PENDING",   // PENDING | SYNCED | RELAYED
    val syncAttempts: Int = 0,
    val l2TxHash: String? = null,         // Base L2 settlement tx hash (Sprint Beta)
    val createdMs: Long,
)
