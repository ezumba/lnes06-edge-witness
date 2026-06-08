package com.exergynet.myapplication

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * DLTN — Relay Job Model + Settlement Dispatcher
 *
 * Each relayed payload from a peer node becomes a DLTNRelayJob.
 * Settlement fires to /api/passive/batch alongside passive sensor batches.
 * reward_micro_usdc is fixed at DLTNConstants.RELAY_REWARD_MICRO_USDC (500).
 */
data class DLTNRelayJob(
    val jobIdHex: String,          // original job_id from peer
    val peerId: String,            // peer node's miner_id / device fingerprint
    val vectorLabel: String,       // always MESH_RELAY
    val payloadHash: String,       // SHA-256 of the relayed payload bytes
    val payloadSizeBytes: Int,
    val relayTimestampMs: Long = System.currentTimeMillis(),
    val rewardMicroUsdc: Long = DLTNConstants.RELAY_REWARD_MICRO_USDC
)

object DLTNRelaySettlement {

    private const val TAG = "DLTNRelaySettlement"

    private const val APEX_PRIMARY  = "https://explorer-api.exergynet.org/api/passive/batch"
    private const val APEX_FALLBACK = "http://18.209.174.113:8080/api/passive/batch"

    /**
     * Posts a batch of relay jobs to the Apex Router.
     * Piggybacks the existing /api/passive/batch endpoint —
     * no new server endpoint required.
     *
     * Call from a background thread (WorkManager or coroutine).
     */
    fun postRelayBatch(minerId: String, jobs: List<DLTNRelayJob>): Boolean {
        if (jobs.isEmpty()) return true

        val payload = buildPayload(minerId, jobs)
        return tryPost(APEX_PRIMARY, payload) || tryPost(APEX_FALLBACK, payload)
    }

    private fun buildPayload(minerId: String, jobs: List<DLTNRelayJob>): String {
        val readings = JSONArray()
        jobs.forEach { job ->
            readings.put(JSONObject().apply {
                put("job_id_hex",        job.jobIdHex)
                put("miner_id",          minerId)
                put("peer_id",           job.peerId)
                put("vector",            job.vectorLabel)          // "MESH_RELAY"
                put("reward_micro_usdc", job.rewardMicroUsdc)      // 500
                put("proof_hash",        job.payloadHash)
                put("payload_size_bytes",job.payloadSizeBytes)
                put("timestamp_ms",      job.relayTimestampMs)
            })
        }
        return JSONObject().apply {
            put("miner_id", minerId)
            put("vector",   DLTNConstants.RELAY_VECTOR_LABEL)
            put("readings", readings)
        }.toString()
    }

    private fun tryPost(url: String, body: String): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod       = "POST"
                connectTimeout      = 8_000
                readTimeout         = 8_000
                doOutput            = true
                setRequestProperty("Content-Type", "application/json")
            }
            val out: OutputStream = conn.outputStream
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
            val code = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "POST $url → HTTP $code")
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "POST failed $url: ${e.message}")
            false
        }
    }
}
