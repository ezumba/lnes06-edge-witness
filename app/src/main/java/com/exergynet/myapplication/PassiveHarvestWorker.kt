package com.exergynet.myapplication

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import kotlin.coroutines.resume

class PassiveHarvestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val DISCHARGE_THRESHOLD = 20
        private const val APEX_ENDPOINT = "https://explorer-api.exergynet.org/api/passive/batch"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = ConfigManager(applicationContext)

        if (!config.passiveModeEnabled) return@withContext Result.success()

        // Battery guard — skip if below threshold
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (batteryLevel < config.passiveBatteryGuard) {
            println(">>> [PASSIVE] Battery $batteryLevel% below guard ${config.passiveBatteryGuard}%. Skipping.")
            return@withContext Result.success()
        }

        val db = ExergyDatabase.getDatabase(applicationContext)

        // Collect magnetometer
        val magPayload = captureMagnetometer()
        db.passiveSensorDao().insert(
            PassiveSensorEntity(
                vector = "MAGNETOMETER",
                rawPayload = magPayload,
                deviceId = config.evmPayoutAddress.ifEmpty { "unknown" }
            )
        )

        // Collect network density (no new permission needed — wifi.scanResults is cached by OS)
        val netPayload = captureNetworkDensity()
        db.passiveSensorDao().insert(
            PassiveSensorEntity(
                vector = "NETWORK_DENSITY",
                rawPayload = netPayload,
                deviceId = config.evmPayoutAddress.ifEmpty { "unknown" }
            )
        )

        println(">>> [PASSIVE] Readings stored. Pending: ${db.passiveSensorDao().getPendingCount()}")

        // Push live readings to Environmental Dashboard
        try {
            val magJson = org.json.JSONObject(magPayload)
            val netJson = org.json.JSONObject(netPayload)
            val magX = magJson.optDouble("x", 0.0)
            val magY = magJson.optDouble("y", 0.0)
            val magZ = magJson.optDouble("z", 0.0)
            val bssidCount = netJson.optInt("bssid_total", 0)
            // Broadcast to any active Activity via broadcast
            val intent = android.content.Intent("com.exergynet.PASSIVE_READING").apply {
                putExtra("mag_x", magX.toFloat())
                putExtra("mag_y", magY.toFloat())
                putExtra("mag_z", magZ.toFloat())
                putExtra("bssid_count", bssidCount)
            }
            applicationContext.sendBroadcast(intent)
        } catch (e: Exception) {
            println(">>> [PASSIVE] Dashboard push failed: \${e.message}")
        }

        // Discharge when capacitor is full
        if (db.passiveSensorDao().getPendingCount() >= DISCHARGE_THRESHOLD) {
            println(">>> [PASSIVE] Threshold reached. Discharging batch to Apex.")
            dischargeBatch(db, config)
        }

        // Purge old transmitted readings (7 days)
        db.passiveSensorDao().purgeOld(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)

        Result.success()
    }

    private suspend fun dischargeBatch(db: ExergyDatabase, config: ConfigManager) {
        val readings = db.passiveSensorDao().getPendingReadings()
        if (readings.isEmpty()) return

        val batchArray = JSONArray()
        readings.forEach { r ->
            batchArray.put(JSONObject().apply {
                put("vector", r.vector)
                put("payload", r.rawPayload)
                put("captured_at", r.capturedAt)
                put("device_id", r.deviceId)
            })
        }

        // Single SHA-256 over entire batch — Density Collapse
        val batchJson = batchArray.toString()
        val batchHash = MessageDigest.getInstance("SHA-256").digest(batchJson.toByteArray())
        val batchHashHex = "0x" + batchHash.joinToString("") { "%02x".format(it) }

        // Single StrongBox sign
        val sigHex = try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val pk = ks.getKey("exergynet_edge_witness_key", null) as java.security.PrivateKey
            val sig = Signature.getInstance("SHA256withECDSA").apply {
                initSign(pk); update(batchHash)
            }.sign()
            sig.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            println(">>> [PASSIVE] StrongBox sign failed: ${e.message}")
            return
        }

        val postBody = JSONObject().apply {
            put("batch", batchArray)
            put("batch_hash", batchHashHex)
            put("strongbox_sig", sigHex)
            put("payment_address", config.evmPayoutAddress)
            put("reading_count", readings.size)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        try {
            val conn = URL(APEX_ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 8000; conn.readTimeout = 10000; conn.doOutput = true
            conn.outputStream.use { it.write(postBody.toByteArray()) }

            if (conn.responseCode in 200..201) {
                db.passiveSensorDao().markTransmitted(readings.map { it.id })
                println(">>> [PASSIVE] Batch of ${readings.size} readings discharged. Hash: $batchHashHex")
            } else {
                println(">>> [PASSIVE] Discharge HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            println(">>> [PASSIVE] Discharge exception: ${e.message}")
        }
    }

    private suspend fun captureMagnetometer(): String = suspendCancellableCoroutine { cont ->
        val sm = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (sensor == null) { cont.resume("{\"error\":\"no_mag\"}"); return@suspendCancellableCoroutine }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                sm.unregisterListener(this)
                if (cont.isActive) cont.resume(
                    JSONObject().apply {
                        put("x", e.values[0]); put("y", e.values[1]); put("z", e.values[2])
                        put("ts", System.currentTimeMillis())
                    }.toString()
                )
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        cont.invokeOnCancellation { sm.unregisterListener(listener) }
    }

    @Suppress("DEPRECATION")
    private fun captureNetworkDensity(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val results = wm.scanResults
            JSONObject().apply {
                put("bssid_total", results.size)
                put("bssid_close", results.count { it.level > -70 })
                put("ts", System.currentTimeMillis())
            }.toString()
        } catch (e: Exception) {
            "{\"error\":\"${e.message}\"}"
        }
    }
}
