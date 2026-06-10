package com.exergynet.myapplication

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * LNES-10 Sprint Alpha — Offline Survival Membrane.
 *
 * Uploads every PENDING ghost drop to the L0 Router.
 * Scheduled with NETWORK_CONNECTED constraint so it fires automatically
 * when connectivity returns after an internet shutdown.
 *
 * Each drop is attempted up to [DLTNConstants.DROP_SYNC_MAX_ATTEMPTS] times;
 * beyond that the drop is left in PENDING state (the record is preserved, but
 * the WorkManager won't retry it automatically — an operator can force a resync).
 *
 * Drops received via DLTN mesh relay (syncStatus = RELAYED) are also uploaded,
 * enabling viral relay: any node with connectivity drains the queue for its
 * offline mesh peers.
 */
class DropSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val TAG = "DropSyncWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db     = ExergyDatabase.getDatabase(applicationContext)
        val dao    = db.ghostDropDao()
        val config = ConfigManager(applicationContext)
        val ip     = config.aggregatorIp.ifEmpty { "explorer-api.exergynet.org" }

        val pending = dao.getPending()
        if (pending.isEmpty()) {
            Log.i(TAG, "[SYNC] No pending drops — nothing to do")
            return@withContext Result.success()
        }

        Log.i(TAG, "[SYNC] Uploading ${pending.size} pending drop(s) to $ip")
        var anyFailed = false

        for (drop in pending) {
            if (drop.syncAttempts >= DLTNConstants.DROP_SYNC_MAX_ATTEMPTS) {
                Log.w(TAG, "[SYNC] Drop ${drop.localId} exceeded max attempts — skipping")
                continue
            }
            dao.incrementAttempts(drop.localId)

            try {
                val bodyJson = buildDropJson(drop)
                val conn = URL("https://$ip/api/v1/drops/create")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 12_000
                conn.readTimeout    = 18_000
                conn.outputStream.use { it.write(bodyJson.toByteArray()) }

                val code     = conn.responseCode
                val respBody = runCatching {
                    if (code == 200) conn.inputStream.bufferedReader().readText()
                    else conn.errorStream?.bufferedReader()?.readText() ?: ""
                }.getOrElse { "" }

                if (code == 200) {
                    val resp   = runCatching { JSONObject(respBody) }.getOrElse { JSONObject() }
                    val dropId = resp.optString("drop_id", "DROP-${drop.timestamp}")
                    dao.markSynced(drop.localId, dropId)
                    val txHash = resp.optString("l2_tx_hash", "")
                    if (txHash.isNotBlank()) dao.setL2TxHash(drop.localId, txHash)
                    Log.i(TAG, "[SYNC] ✓ Drop ${drop.localId.take(8)} → $dropId${if (txHash.isNotBlank()) " ⛓ $txHash" else ""}")
                } else {
                    Log.w(TAG, "[SYNC] Drop ${drop.localId.take(8)} rejected HTTP $code: $respBody")
                    anyFailed = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "[SYNC] Drop ${drop.localId.take(8)} upload failed: ${e.message}")
                anyFailed = true
            }
        }

        if (anyFailed) Result.retry() else Result.success()
    }

    private fun buildDropJson(drop: GhostDropEntity): String {
        val obj = JSONObject().apply {
            put("message",   drop.message)
            put("type",      drop.type)
            put("lat",       drop.lat)
            put("lon",       drop.lon)
            put("radius_m",  drop.radiusM)
            put("ttl_secs",  drop.ttlSecs)
            put("timestamp", drop.timestamp)
            put("nonce",     drop.nonceHex)
            put("signature", drop.signature)
            put("miner_id",  drop.minerId)
            put("category",  drop.category)
            if (drop.groups.isNotBlank()) put("groups", drop.groups)
            // Re-attach inline media if present.
            if (drop.attachmentJson.isNotBlank()) {
                runCatching {
                    val att = JSONObject(drop.attachmentJson)
                    val b64 = att.optString("dataB64")
                    if (b64.isNotEmpty()) {
                        put("attachment_b64",  b64)
                        put("attachment_mime", att.optString("mime", "image/jpeg"))
                        put("attachment_name", att.optString("name", "attachment"))
                    }
                }
            }
        }
        return obj.toString()
    }

    companion object {
        private const val WORK_NAME = "ghost_drop_sync"

        /**
         * Enqueue a one-time sync run that executes as soon as the device has
         * network connectivity. Safe to call multiple times — ExistingWorkPolicy.KEEP
         * means a queued run won't be duplicated.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DropSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)

            Log.i("DropSyncWorker", "[SYNC] Enqueued — will fire on next connectivity")
        }
    }
}
