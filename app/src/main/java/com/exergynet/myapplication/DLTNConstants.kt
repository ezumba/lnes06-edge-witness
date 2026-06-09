package com.exergynet.myapplication

/**
 * DLTN — Decentralized Local Transceiver Network
 * All tunable thresholds in one place. Edit here only.
 *
 * VECTOR: MESH_RELAY
 * SETTLEMENT: 500 micro_USDC per relay hop (~$0.0005)
 */
object DLTNConstants {

    // ── BLE Discovery ────────────────────────────────────────────────────────
    /** Service UUID broadcast by every LNES-06 Edge Witness node */
    const val DLTN_BLE_SERVICE_UUID = "0000FE2A-0000-1000-8000-00805F9B34FB"

    /** Characteristic UUID for relay payload exchange over BLE (iOS fallback) */
    const val DLTN_BLE_CHAR_UUID    = "0000FE2B-0000-1000-8000-00805F9B34FB"

    /**
     * RSSI Wake Gate — Phase B spatial constraint.
     * -70 dBm ≈ 10 metres in open space.
     * Connections at lower RSSI (more negative) are aborted.
     */
    const val RSSI_GATE_DBM = -70

    /** BLE advertise duty cycle interval (ms). Higher = lower battery drain. */
    const val BLE_ADVERTISE_INTERVAL_MS = 1000L

    // ── WiFi Aware (Android primary) ─────────────────────────────────────────
    /** WiFi Aware service name — must match across all LNES-06 builds */
    const val WIFI_AWARE_SERVICE_NAME = "exergynet-dltn-v1"

    /** Port for WiFi Aware socket exchange */
    const val WIFI_AWARE_PORT = 7432

    /** Transfer timeout before tearing down WiFi Aware session (ms) */
    const val WIFI_AWARE_TIMEOUT_MS = 15_000L

    // ── BLE Fallback (iOS peers) ──────────────────────────────────────────────
    /** Max payload size over BLE fallback channel (bytes). MTU-safe. */
    const val BLE_FALLBACK_MAX_PAYLOAD_BYTES = 512

    // ── Settlement ────────────────────────────────────────────────────────────
    /**
     * Relay reward per forwarded job hop.
     * 500 micro_USDC = $0.0005 — non-zero, adoption-safe, revenue-offset.
     */
    const val RELAY_REWARD_MICRO_USDC = 500L

    /** Vector label used in settlement POST and Earnings tab */
    const val RELAY_VECTOR_LABEL = "MESH_RELAY"

    // ── Notification Channel ──────────────────────────────────────────────────
    const val DLTN_NOTIFICATION_CHANNEL_ID   = "dltn_relay_channel"
    const val DLTN_NOTIFICATION_CHANNEL_NAME = "Mesh Relay"
    const val DLTN_FOREGROUND_NOTIFICATION_ID = 42

    // A channel's importance is fixed at creation time and cannot be raised later,
    // so an "Active Call" state needs its own IMPORTANCE_HIGH channel rather than
    // mutating the low-importance relay channel.
    const val DLTN_CALL_CHANNEL_ID   = "dltn_call_channel"
    const val DLTN_CALL_CHANNEL_NAME = "Active Call"

    // ── Voice Calls ───────────────────────────────────────────────────────────
    const val WIFI_AWARE_VOICE_PORT   = 7433
    const val VOICE_SAMPLE_RATE_HZ    = 16000
    const val VOICE_BUFFER_BYTES      = 640
    const val VOICE_TIMEOUT_MS        = 30_000L
    // WiFi-Aware voice link PSK (encrypts the data path; closes red-team D4).
    // ⚠ Placeholder — both peers share this static key. Replace with a per-call
    // negotiated secret (e.g. ECDH keyed off the signed call_invite) before
    // production: a static PSK gives link encryption but no per-session secrecy.
    const val WIFI_AWARE_VOICE_PSK    = "exergynet-dltn-voice-psk-v1"
    const val MSG_TYPE_CALL_INVITE    = "call_invite"
    const val MSG_TYPE_CALL_ACCEPT    = "call_accept"
    const val MSG_TYPE_CALL_REJECT    = "call_reject"
    const val MSG_TYPE_CALL_END       = "call_end"
    const val CALL_NOTIFICATION_ID    = 43

    // ── AREM — Ambient RF Exploitation Module ────────────────────────────────
    const val AREM_CYCLE_MS           = 10_000L
    const val AREM_WINDOW_MS          = 40L
    const val AREM_JITTER_TOLERANCE   = 5L
    const val AREM_FALLBACK_CYCLE_MS  = 10_000L
    const val AREM_RSSI_SPATIAL_GATE  = 8
    const val AREM_RSSI_MIN_TRUSTED   = -100
    const val AREM_CELL_REFRESH_MS    = 5_000L

    // ── ASSA — Ambient Spectrum Scavenging Architecture ──────────────────────
    const val ASSA_GNSS_MAX_AGE_MS        = 30_000L
    const val ASSA_GNSS_MIN_ACCURACY_M    = 50f
    const val ASSA_BSSID_MIN_MATCH        = 2
    const val ASSA_BSSID_RSSI_TOLERANCE   = 15
    const val ASSA_BSSID_MAX_AGE_MS       = 15_000L
    const val ASSA_MAG_DELTA_UT           = 3.0f
    const val ASSA_MAG_MIN_SAMPLES        = 5
}
