package com.exergynet.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.abs

@SuppressLint("MissingPermission")
class AREMSynchronizer(
    private val context: Context,
    private val assa: ASSAScavenger? = null,
) {
    private val TAG = "AREMSynchronizer"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val telephony by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    @Volatile private var epochMs: Long = System.currentTimeMillis()
    @Volatile private var cyclePeriodMs: Long = DLTNConstants.AREM_CYCLE_MS
    @Volatile private var lastCellId: Int = -1
    @Volatile private var lastRssi: Int = DLTNConstants.AREM_RSSI_MIN_TRUSTED
    @Volatile private var lastCellRefreshMs: Long = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    init {
        scope.launch { refreshLoop() }
    }

    private suspend fun refreshLoop() {
        while (scope.isActive) {
            calibrateEpoch()
            delay(cyclePeriodMs)
        }
    }

    // ── Epoch calibration ─────────────────────────────────────────────────────

    fun calibrateEpoch() {
        val now = System.currentTimeMillis()

        // Prefer ASSA GNSS when available
        if (assa != null && assa.hasGnssLock()) {
            epochMs = assa.getBestEpochMs()
            Log.d(TAG, "[AREM] Epoch from GNSS: $epochMs")
            return
        }

        // Fallback: cellular MIB timing approximation via cell refresh cadence
        if (now - lastCellRefreshMs > DLTNConstants.AREM_CELL_REFRESH_MS) {
            refreshCellInfo()
            lastCellRefreshMs = now
        }

        epochMs = now
    }

    private fun refreshCellInfo() {
        try {
            val cells = telephony.allCellInfo ?: return
            for (cell in cells) {
                when (cell) {
                    is CellInfoLte   -> {
                        lastCellId = cell.cellIdentity.ci
                        lastRssi   = cell.cellSignalStrength.dbm
                    }
                    is CellInfoNr    -> {
                        lastCellId = cell.cellIdentity.hashCode() and 0xFFFF
                        lastRssi   = cell.cellSignalStrength.dbm
                    }
                    is CellInfoGsm   -> {
                        lastCellId = cell.cellIdentity.cid
                        lastRssi   = cell.cellSignalStrength.dbm
                    }
                    is CellInfoWcdma -> {
                        lastCellId = cell.cellIdentity.cid
                        lastRssi   = cell.cellSignalStrength.dbm
                    }
                }
                if (lastCellId != -1) break
            }
            Log.d(TAG, "[AREM] Cell: id=$lastCellId rssi=$lastRssi")
        } catch (e: Exception) {
            Log.w(TAG, "[AREM] Cell refresh failed: ${e.message}")
        }
    }

    // ── Temporal gate ─────────────────────────────────────────────────────────

    fun isInDiscoveryWindow(): Boolean {
        val now      = System.currentTimeMillis()
        val elapsed  = (now - epochMs) % cyclePeriodMs
        val inWindow = elapsed < DLTNConstants.AREM_WINDOW_MS + DLTNConstants.AREM_JITTER_TOLERANCE
        Log.v(TAG, "[AREM] Window check: elapsed=${elapsed}ms → $inWindow")
        return inWindow
    }

    fun msUntilNextWindow(): Long {
        val now     = System.currentTimeMillis()
        val elapsed = (now - epochMs) % cyclePeriodMs
        return if (elapsed < DLTNConstants.AREM_WINDOW_MS + DLTNConstants.AREM_JITTER_TOLERANCE) {
            0L
        } else {
            cyclePeriodMs - elapsed
        }
    }

    // ── Spatial gate ──────────────────────────────────────────────────────────

    fun passesSpatialGate(peerPayload: AREMPayload, peerRssi: Int): Boolean {
        // Cell ID must match (same tower coverage area)
        if (lastCellId != -1 && peerPayload.cellId != -1 && lastCellId != peerPayload.cellId) {
            Log.d(TAG, "[AREM] Spatial FAIL: cell mismatch local=$lastCellId peer=${peerPayload.cellId}")
            return false
        }

        // RSSI difference must be within gate
        val rssiDelta = abs(lastRssi - peerPayload.rssi)
        if (rssiDelta > DLTNConstants.AREM_RSSI_SPATIAL_GATE) {
            Log.d(TAG, "[AREM] Spatial FAIL: rssi delta=$rssiDelta > ${DLTNConstants.AREM_RSSI_SPATIAL_GATE}")
            return false
        }

        // ASSA layered gates when available
        if (assa != null) {
            if (!assa.passesBssidSpatialGate(peerPayload.bssidMap)) return false
            if (!assa.passesMagneticGate(peerPayload.magBytes)) return false
        }

        Log.d(TAG, "[AREM] Spatial PASS: cell=${peerPayload.cellId} rssiΔ=$rssiDelta")
        return true
    }

    // ── Advertisement payload ─────────────────────────────────────────────────

    fun buildAdvertisementPayload(): ByteArray {
        val cellHi  = (lastCellId shr 8) and 0xFF
        val cellLo  = lastCellId and 0xFF
        val rssiByte = (lastRssi + 128).coerceIn(0, 255)
        val checksum = (cellHi xor cellLo xor rssiByte) and 0xFF
        return byteArrayOf(cellHi.toByte(), cellLo.toByte(), rssiByte.toByte(), checksum.toByte())
    }

    fun parseAdvertisementPayload(bytes: ByteArray): AREMPayload? {
        if (bytes.size < 4) return null
        val cellHi   = bytes[0].toInt() and 0xFF
        val cellLo   = bytes[1].toInt() and 0xFF
        val rssiByte = bytes[2].toInt() and 0xFF
        val checksum = bytes[3].toInt() and 0xFF
        if ((cellHi xor cellLo xor rssiByte) and 0xFF != checksum) {
            Log.w(TAG, "[AREM] Payload checksum failed")
            return null
        }
        val cellId = (cellHi shl 8) or cellLo
        val rssi   = rssiByte - 128
        return AREMPayload(cellId = cellId, rssi = rssi, bssidMap = emptyMap(), magBytes = null)
    }

    // ── Status ────────────────────────────────────────────────────────────────

    fun getStatus(): Map<String, Any> = mapOf(
        "epochMs"       to epochMs,
        "cyclePeriodMs" to cyclePeriodMs,
        "cellId"        to lastCellId,
        "cellRssi"      to lastRssi,
        "inWindow"      to isInDiscoveryWindow(),
        "msToWindow"    to msUntilNextWindow(),
        "assaLayers"    to (assa?.spatialConfidence() ?: "NONE"),
    )

    fun destroy() { scope.cancel() }

    data class AREMPayload(
        val cellId: Int,
        val rssi: Int,
        val bssidMap: Map<String, Int>,
        val magBytes: ByteArray?,
    )
}
