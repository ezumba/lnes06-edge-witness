package com.exergynet.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object EdgeTransmitter {

    private const val AGGREGATOR_PORT = 8003
    private const val APEX_HTTPS = "https://explorer-api.exergynet.org/api/job/complete"
    private const val RELAY_URL  = "https://explorer-api.exergynet.org/api/relay/payload"

    /**
     * Main transmission entry point.
     *
     * 1. Auto-discover prover (LAN beacon or relay)
     * 2. If LAN: TCP direct to Desktop (existing binary format)
     * 3. If RELAY: HTTP POST to Apex Router (S3 key + job metadata)
     *
     * @param manualIp override — if user manually set an IP in Config, use it directly
     */
    suspend fun radiatePayload(
        manualIp: String = "",
        jobIdHex: String,
        minerAddress: String,
        rewardMicroUsdc: Long,
        vector: String,
        sensorData: ByteArray,
        s3Key: String = "",
        tcpSecret: ByteArray = ByteArray(0)
    ): Boolean = withContext(Dispatchers.IO) {

        // Compute SHA-256 proof hash over payload
        val proofHash = "0x" + MessageDigest.getInstance("SHA-256")
            .digest(sensorData)
            .joinToString("") { "%02x".format(it) }

        // ── STRIKE 1: Manual IP override (user configured) ─────────────────
        if (manualIp.isNotEmpty()) {
            val ok = trySendTcp(manualIp, AGGREGATOR_PORT, sensorData, tcpSecret)
            if (ok) {
                println(">>> [EDGE] Delivered via manual IP: $manualIp")
                return@withContext true
            }
            println(">>> [EDGE] Manual IP failed, running discovery...")
        }

        // ── STRIKE 2: Auto-discover (LAN beacon or relay) ──────────────────
        val prover = ProverDiscovery.findProver()

        if (prover != null && prover.path == ProverDiscovery.DiscoveryPath.LAN_DIRECT) {
            // Path 1: LAN direct TCP
            val ok = trySendTcp(prover.ip, prover.port, sensorData, tcpSecret)
            if (ok) {
                println(">>> [EDGE] ✓ Delivered via LAN to ${prover.ip}:${prover.port} [${prover.nodeId}]")
                // Also register with L0
                postToApex(jobIdHex, proofHash, minerAddress, rewardMicroUsdc, vector, s3Key)
                return@withContext true
            }
        }

        // Path 2: Relay via Apex Router → Desktop Prover WebSocket
        println(">>> [EDGE] Using Omega Relay path → Desktop Prover...")
        val relayOk = postToDesktopRelay(jobIdHex, proofHash, minerAddress, rewardMicroUsdc, vector, sensorData)
        if (relayOk) {
            // Also register with L0 ledger
            postToApex(jobIdHex, proofHash, minerAddress, rewardMicroUsdc, vector, s3Key)
            println(">>> [EDGE] ✓ Payload relayed to Desktop Prover via Omega Relay.")
            return@withContext true
        }

        println(">>> [FATAL] All transmission paths failed.")
        false
    }

    /**
     * BLUE TEAM (H1): TCP wire format is now [32-byte HMAC-SHA256 tag][payload].
     * The desktop LNES-15 prover recomputes HMAC-SHA256(secret, payload) and rejects
     * any frame whose prefix does not match — making the link non-spoofable and in
     * sync with the hardened desktop. Also adds a 6s connect timeout (M4).
     *
     * If no secret is provisioned the frame is sent unsealed (legacy behaviour) so
     * the integration degrades rather than silently dropping — but production builds
     * must always carry a real secret.
     */
    private fun trySendTcp(ip: String, port: Int, data: ByteArray, tcpSecret: ByteArray): Boolean {
        return try {
            val frame = if (tcpSecret.isNotEmpty()) hmacSha256(tcpSecret, data) + data else data
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, port), 6000)
                socket.soTimeout = 8000
                DataOutputStream(socket.getOutputStream()).use { dos ->
                    dos.write(frame)
                    dos.flush()
                }
            }
            true
        } catch (e: Exception) {
            println(">>> [EDGE] TCP to $ip:$port failed: ${e.message}")
            false
        }
    }

    private fun hmacSha256(secret: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(data) // 32 bytes
    }

    /**
     * LNES-11 OFF-GRID: request/response over the local DLTN TCP socket (Port 8003).
     * Sends [32B HMAC][payload] exactly like trySendTcp, then half-closes the write
     * side and BLOCKS reading the desktop prover's reply (the resolved coordinates,
     * e.g. "geo:lat,lon" or a small JSON). Used when the cloud DNS/HTTPS path fails.
     * Returns the raw reply string, or null on any failure.
     */
    suspend fun requestTcp(
        ip: String,
        port: Int,
        data: ByteArray,
        tcpSecret: ByteArray,
        readTimeoutMs: Int = 90_000,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val frame = if (tcpSecret.isNotEmpty()) hmacSha256(tcpSecret, data) + data else data
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, port), 6000)
                socket.soTimeout = readTimeoutMs   // desktop ZK solve may take a while
                DataOutputStream(socket.getOutputStream()).apply { write(frame); flush() }
                // Signal end-of-request so the desktop can read the full payload, compute, reply.
                try { socket.shutdownOutput() } catch (_: Exception) {}
                val reply = socket.getInputStream().bufferedReader().readText().trim()
                reply.ifEmpty { null }
            }
        } catch (e: Exception) {
            println(">>> [EDGE] TCP request to $ip:$port failed: ${e.message}")
            null
        }
    }

    private fun postToApex(
        jobIdHex: String,
        proofHash: String,
        minerAddress: String,
        rewardMicroUsdc: Long,
        vector: String,
        s3Key: String
    ): Boolean {
        return try {
            val body = JSONObject().apply {
                put("job_id_hex", jobIdHex)
                put("payload_url", if (s3Key.isNotEmpty()) "s3://exergynet-sump-v1/$s3Key" else "")
                put("miner_id", minerAddress)
                put("payment_address", minerAddress)
                put("reward_micro_usdc", rewardMicroUsdc)
                put("vector", vector)
                put("proof_hash", proofHash)
            }.toString()

            val conn = URL(APEX_HTTPS).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            code in 200..201
        } catch (e: Exception) {
            println(">>> [EDGE] Apex HTTP POST failed: ${e.message}")
            false
        }
    }
    private fun postToDesktopRelay(
        jobIdHex: String,
        inputHash: String,
        minerAddress: String,
        rewardMicroUsdc: Long,
        vector: String,
        sensorData: ByteArray
    ): Boolean {
        return try {
            val rewardUsdc = rewardMicroUsdc.toDouble() / 1_000_000.0
            val inputDataB64 = android.util.Base64.encodeToString(sensorData, android.util.Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("job_id", jobIdHex)
                put("opcode", vectorToOpcode(vector))
                put("compute_type", vector.lowercase())
                put("input_hash", inputHash)
                put("input_data", JSONObject().apply {
                    put("vector", vector)
                    put("data_b64", inputDataB64)
                    put("size_bytes", sensorData.size)
                })
                put("reward_usdc", rewardUsdc)
                put("deadline_ts", System.currentTimeMillis() / 1000 + 3600)
                put("requester", "android_edge_witness")
                put("miner_id", minerAddress)
            }.toString()

            val conn = URL(RELAY_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            println(">>> [EDGE] Desktop relay response: $code")
            code in 200..201
        } catch (e: Exception) {
            println(">>> [EDGE] Desktop relay failed: ${e.message}")
            false
        }
    }

    private fun vectorToOpcode(vector: String): String = when (vector.uppercase()) {
        "OPTICAL"          -> "0x01"
        "GEOSPATIAL"       -> "0x02"
        "AMBIENT"          -> "0x03"
        "KINEMATIC"        -> "0x04"
        "NFC_RFID"         -> "0x05"
        "MAGNETOMETER"     -> "0x06"
        "STORAGE_PING"     -> "0x07"
        "NETWORK_DENSITY"  -> "0x08"
        "BIOMETRIC_GATE"   -> "0x09"
        "ASYNC_COMPUTE"    -> "0x0A"
        else               -> "0x08"
    }

}
