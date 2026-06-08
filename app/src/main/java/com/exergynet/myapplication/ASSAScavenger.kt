package com.exergynet.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.abs

@SuppressLint("MissingPermission")
class ASSAScavenger(private val context: Context) {

    private val TAG = "ASSAScavenger"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── GNSS passive lock ────────────────────────────────────────────────────

    @Volatile private var lastGnssLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastGnssLocation = location
            Log.d(TAG, "[GNSS] Fix: acc=${location.accuracy}m age=${ageMs(location)}ms")
        }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ── WiFi BSSID fingerprint ────────────────────────────────────────────────

    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    @Volatile private var bssidSnapshot: Map<String, Int> = emptyMap()
    @Volatile private var bssidSnapshotAgeMs: Long = Long.MAX_VALUE

    // ── Magnetometer ──────────────────────────────────────────────────────────

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val magReadings = ArrayDeque<FloatArray>(DLTNConstants.ASSA_MAG_MIN_SAMPLES * 2)
    @Volatile private var magBaseline: FloatArray? = null

    private val magListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            synchronized(magReadings) {
                if (magReadings.size >= DLTNConstants.ASSA_MAG_MIN_SAMPLES * 2) magReadings.removeFirst()
                magReadings.addLast(event.values.copyOf())
                if (magBaseline == null && magReadings.size >= DLTNConstants.ASSA_MAG_MIN_SAMPLES) {
                    magBaseline = averageMag(magReadings.toList())
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    init {
        attachGnssListener()
        attachMagnetometer()
        scope.launch { bssidRefreshLoop() }
    }

    private fun attachGnssListener() {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, locationListener)
            Log.i(TAG, "[GNSS] Passive listener attached")
        } catch (e: Exception) {
            Log.w(TAG, "[GNSS] Cannot attach: ${e.message}")
        }
    }

    private fun attachMagnetometer() {
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (mag != null) {
            sensorManager.registerListener(magListener, mag, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "[MAG] Magnetometer attached")
        } else {
            Log.w(TAG, "[MAG] No magnetometer")
        }
    }

    private suspend fun bssidRefreshLoop() {
        while (scope.isActive) {
            refreshBssidSnapshot()
            delay(DLTNConstants.ASSA_BSSID_MAX_AGE_MS)
        }
    }

    private fun refreshBssidSnapshot() {
        try {
            val results = wifiManager.scanResults
            bssidSnapshot = results.associate { it.BSSID to it.level }
            bssidSnapshotAgeMs = System.currentTimeMillis()
            Log.d(TAG, "[BSSID] Snapshot: ${bssidSnapshot.size} APs")
        } catch (e: Exception) {
            Log.w(TAG, "[BSSID] Refresh failed: ${e.message}")
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getBestEpochMs(): Long {
        val gnss = lastGnssLocation
        return if (gnss != null && ageMs(gnss) < DLTNConstants.ASSA_GNSS_MAX_AGE_MS &&
            gnss.accuracy < DLTNConstants.ASSA_GNSS_MIN_ACCURACY_M) {
            gnss.time
        } else {
            System.currentTimeMillis()
        }
    }

    fun hasGnssLock(): Boolean {
        val gnss = lastGnssLocation ?: return false
        return ageMs(gnss) < DLTNConstants.ASSA_GNSS_MAX_AGE_MS &&
               gnss.accuracy < DLTNConstants.ASSA_GNSS_MIN_ACCURACY_M
    }

    fun passesBssidSpatialGate(peerBssidPayload: Map<String, Int>): Boolean {
        if (peerBssidPayload.isEmpty()) return true
        val ageOk = (System.currentTimeMillis() - bssidSnapshotAgeMs) < DLTNConstants.ASSA_BSSID_MAX_AGE_MS
        if (!ageOk) return true // stale snapshot — don't gate
        var matches = 0
        for ((bssid, peerRssi) in peerBssidPayload) {
            val localRssi = bssidSnapshot[bssid] ?: continue
            if (abs(localRssi - peerRssi) <= DLTNConstants.ASSA_BSSID_RSSI_TOLERANCE) matches++
        }
        val pass = matches >= DLTNConstants.ASSA_BSSID_MIN_MATCH
        Log.d(TAG, "[BSSID] Gate: $matches/${peerBssidPayload.size} matches → $pass")
        return pass
    }

    fun passesMagneticGate(peerMagBytes: ByteArray?): Boolean {
        if (peerMagBytes == null || peerMagBytes.size < 6) return true
        val baseline = magBaseline ?: return true
        val px = bytesToFloat(peerMagBytes, 0)
        val py = bytesToFloat(peerMagBytes, 2)
        val pz = bytesToFloat(peerMagBytes, 4)
        val dx = abs(baseline[0] - px)
        val dy = abs(baseline[1] - py)
        val dz = abs(baseline[2] - pz)
        val pass = dx < DLTNConstants.ASSA_MAG_DELTA_UT &&
                   dy < DLTNConstants.ASSA_MAG_DELTA_UT &&
                   dz < DLTNConstants.ASSA_MAG_DELTA_UT
        Log.d(TAG, "[MAG] Gate: Δ=(${dx}/${dy}/${dz}) → $pass")
        return pass
    }

    fun buildBssidPayload(): Map<String, Int> = bssidSnapshot.toMap()

    /** Current magnetometer baseline vector [x,y,z] µT, or null if not yet sampled.
     *  Used by the LNES-11 Sim(3) proprioception payload. */
    fun magneticVector(): FloatArray? = magBaseline?.copyOf()

    fun spatialConfidence(): String {
        val gnss   = if (hasGnssLock()) "GNSS" else ""
        val bssid  = if (bssidSnapshot.size >= DLTNConstants.ASSA_BSSID_MIN_MATCH) "BSSID" else ""
        val mag    = if (magBaseline != null) "MAG" else ""
        val layers = listOf(gnss, bssid, mag).filter { it.isNotEmpty() }
        return if (layers.isEmpty()) "NONE" else layers.joinToString("+")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ageMs(loc: Location) = System.currentTimeMillis() - loc.time

    private fun averageMag(samples: List<FloatArray>): FloatArray {
        val avg = FloatArray(3)
        for (s in samples) { avg[0] += s[0]; avg[1] += s[1]; avg[2] += s[2] }
        val n = samples.size.toFloat()
        return floatArrayOf(avg[0] / n, avg[1] / n, avg[2] / n)
    }

    private fun bytesToFloat(buf: ByteArray, offset: Int): Float {
        val raw = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        return raw.toShort().toFloat() / 10f
    }

    fun destroy() {
        scope.cancel()
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locationListener)
        } catch (_: Exception) {}
        sensorManager.unregisterListener(magListener)
    }
}
