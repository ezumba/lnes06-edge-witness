package com.exergynet.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * LNES-06 Prover Discovery Engine
 *
 * Path 1 — LAN (WiFi):
 *   Listen on UDP 8004 for beacon from Desktop Prover.
 *   Also send "EXERGYNET_DISCOVER" ping to broadcast.
 *   Returns prover IP within ~5s if on same network.
 *
 * Path 2 — Relay (Mobile Data):
 *   Falls back to HTTPS relay via Apex Router.
 *   Phone posts job + S3 key → Apex logs it → available for any prover to claim.
 */
object ProverDiscovery {

    private const val BEACON_PORT = 8004
    private const val DISCOVERY_TIMEOUT_MS = 6000L
    private const val RELAY_URL = "https://explorer-api.exergynet.org/api/job/complete"

    data class ProverAddress(
        val ip: String,
        val port: Int,
        val nodeId: String,
        val path: DiscoveryPath
    )

    enum class DiscoveryPath { LAN_DIRECT, RELAY }

    /**
     * Main entry — tries LAN first, falls back to relay.
     * Returns null only if both paths fail.
     */
    suspend fun findProver(): ProverAddress? {
        return tryLanDiscovery() ?: relayAddress()
    }

    /**
     * Path 1: UDP LAN discovery.
     * Sends EXERGYNET_DISCOVER to broadcast AND listens for Desktop beacon.
     * Whichever arrives first wins.
     */
    private suspend fun tryLanDiscovery(): ProverAddress? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            try {
                val socket = DatagramSocket(BEACON_PORT)
                socket.broadcast = true
                socket.soTimeout = DISCOVERY_TIMEOUT_MS.toInt()

                // Send active ping to broadcast
                val ping = "EXERGYNET_DISCOVER".toByteArray()
                val broadcast = InetAddress.getByName("255.255.255.255")
                socket.send(DatagramPacket(ping, ping.size, broadcast, BEACON_PORT))

                // Wait for any beacon response
                val buf = ByteArray(512)
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                socket.close()

                val response = String(packet.data, 0, packet.length)
                val json = JSONObject(response)

                if (json.optString("service") == "exergynet-prover") {
                    ProverAddress(
                        ip = json.getString("ip"),
                        port = json.getInt("tcp_port"),
                        nodeId = json.optString("node_id", "unknown"),
                        path = DiscoveryPath.LAN_DIRECT
                    )
                } else null
            } catch (e: Exception) {
                println(">>> [DISCOVERY] LAN scan: ${e.message} — trying relay...")
                null
            }
        }
    }

    /**
     * Path 2: Relay via Apex Router.
     * When phone is on mobile data, returns the relay coordinates.
     * The actual job payload goes to S3, then POST to /api/job/complete.
     * The Desktop Prover polls the Apex Router for MOBILE_WITNESS jobs.
     */
    private fun relayAddress(): ProverAddress {
        println(">>> [DISCOVERY] Using relay path via Apex Router.")
        return ProverAddress(
            ip = "relay",       // sentinel — EdgeTransmitter uses HTTP not TCP
            port = 443,
            nodeId = "apex-relay",
            path = DiscoveryPath.RELAY
        )
    }
}
