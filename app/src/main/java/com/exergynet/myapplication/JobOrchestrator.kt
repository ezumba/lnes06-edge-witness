package com.exergynet.myapplication // MUST MATCH YOUR NAMESPACE

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlin.coroutines.resume

// 1. The Absolute Thermodynamic Vector Matrix
enum class JobType(val opcode: Byte) {
    OPTICAL(0x01), GEOSPATIAL(0x02), AMBIENT(0x03), KINEMATIC(0x04),
    NFC_RFID(0x05), MAGNETOMETER(0x06), STORAGE_PING(0x07),
    NETWORK_DENSITY(0x08), BIOMETRIC_GATE(0x09), ASYNC_COMPUTE(0x0A)
}

// 2. The Agentic Payload Definition
data class EdgeJob(
    val jobId: ByteArray,
    val jobType: JobType,
    val parameters: JSONObject,
    val rewardMicroUsdc: Long,
    val isActiveJob: Boolean = false
)

object JobOrchestrator {

    /**
     * Executes standard jobs that read from sensors automatically.
     */
    suspend fun executeJob(context: Context, job: EdgeJob, pubKey: ByteArray, targetIp: String, callback: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            callback("[ORCHESTRATOR] Initializing Vector: ${job.jobType.name}")

            val rawSensorData: ByteArray = try {
                captureRawSensorData(context, job, callback)
            } catch (e: Exception) {
                callback("[FATAL] Sensor Hardware Failure: ${e.message}")
                return@withContext
            }

            sealAndRadiate(context, job, rawSensorData, pubKey, callback)
        }
    }

    /**
     * Reads the actual hardware sensor payload for a non-optical job and returns the
     * raw bytes (typically UTF-8 JSON/text reflecting the live reading). Exposed so
     * callers (e.g. the HTTPS relay path) can hash REAL captured data instead of a
     * forgeable zero-byte constant (BLUE TEAM H3). OPTICAL must use pre-captured bytes.
     */
    suspend fun captureRawSensorData(context: Context, job: EdgeJob, callback: (String) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            when (job.jobType) {
                JobType.GEOSPATIAL -> captureGeospatial(context, job.parameters)
                JobType.AMBIENT -> captureAmbient(context, job.parameters)
                JobType.KINEMATIC -> captureKinematic(context, job.parameters)
                JobType.NFC_RFID -> captureNfcRfid(context, job.parameters, callback)
                JobType.MAGNETOMETER -> captureMagnetometer(context, job.parameters)
                JobType.STORAGE_PING -> executeStoragePing(context, job.parameters)
                JobType.NETWORK_DENSITY -> captureNetworkDensity(context, job.parameters)
                JobType.BIOMETRIC_GATE -> executeBiometricGate(context, job.parameters)
                JobType.ASYNC_COMPUTE -> {
                    // Inject jobIdHex for ComputeWorker tracking
                    job.parameters.put("job_id_hex", job.jobId.joinToString("") { "%02x".format(it) })
                    executeAsyncCompute(context, job.parameters)
                }
                JobType.OPTICAL -> throw IllegalArgumentException("OPTICAL requires pre-captured optical bytes")
            }
        }

    /**
     * Executes an Optical Job using pre-captured byte arrays (HASHES) from the OS Memory.
     */
    suspend fun executeJobWithData(
        context: Context,
        job: EdgeJob,
        pubKey: ByteArray,
        targetIp: String,
        rawSensorData: ByteArray,
        callback: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            callback("[ORCHESTRATOR] Initializing Vector: ${job.jobType.name}")
            callback("[ORCHESTRATOR] Physical Reality Acquired (${rawSensorData.size} bytes).")
            sealAndRadiate(context, job, rawSensorData, pubKey, callback)
        }
    }

    private suspend fun sealAndRadiate(context: Context, job: EdgeJob, rawSensorData: ByteArray, pubKey: ByteArray, callback: (String) -> Unit) {
        val payloadGeometry = ByteBuffer.allocate(1 + rawSensorData.size)
            .put(job.jobType.opcode)
            .put(rawSensorData)
            .array()

        // KINEMATIC INJECTION: persist to SQLite immediately so the Pending tab
        // sees the job the instant capture begins — before signing or radiation.
        val hexJobId = "0x" + job.jobId.joinToString("") { "%02x".format(it) }
        val db = ExergyDatabase.getDatabase(context)
        db.jobStateDao().insertJob(JobStateEntity(
            jobIdHex = hexJobId,
            jobType = job.jobType.name,
            rewardMicroUsdc = job.rewardMicroUsdc,
            status = JobStatus.QUEUED_LOCAL
        ))

        callback("[ORCHESTRATOR] Engaging StrongBox...")

        val signature = try {
            ExergyStrongBox.signThermodynamicPayload(job.jobId, payloadGeometry)
        } catch (e: Exception) {
            db.jobStateDao().updateJobStatus(hexJobId, JobStatus.FAILED_PROOF)
            callback("[FATAL] StrongBox Signing Failed: ${e.message}")
            return
        }

        // 1. Pack the absolute transmission matrix:[32B JobID | PubKey | Signature | PayloadGeometry]
        val fullTransmissionMatrix = ByteBuffer.allocate(32 + pubKey.size + signature.size + payloadGeometry.size)
            .put(job.jobId)
            .put(pubKey)
            .put(signature)
            .put(payloadGeometry)
            .array()

        // 2. Write to Android SSD
        val payloadFile = File(context.cacheDir, "payload_$hexJobId.bin")
        try {
            payloadFile.writeBytes(fullTransmissionMatrix)
            db.jobStateDao().updatePayloadPath(hexJobId, payloadFile.absolutePath)
        } catch (e: Exception) {
            callback("[FATAL] Disk Write Failure: ${e.message}")
            return
        }

        callback("[ORCHESTRATOR] Hardware Signature forged. Payload saved to SSD (${fullTransmissionMatrix.size} bytes).")

        // AGENT DIRECTIVE: Passive Batching Queue — bypass for active UI-triggered jobs
        if (!job.isActiveJob && (job.jobType == JobType.MAGNETOMETER || job.jobType == JobType.NETWORK_DENSITY)) {
            callback(">>> [PASSIVE] Payload sealed and batched to local DB.")
        } else {
            // 4. Enqueue the Background Worker (ACTIVE MODE)
            val workData = androidx.work.Data.Builder().putString("JOB_ID_HEX", hexJobId).build()
            val workRequest = OneTimeWorkRequestBuilder<RadiationWorker>()
                .setInputData(workData)
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)

            callback("JOB_QUEUED: Payload signed and scheduled for background radiation.")
        }
    }

    // ========================================================================
    // PHYSICAL HARDWARE BINDINGS
    // ========================================================================

    private suspend fun captureMagnetometer(context: Context, params: JSONObject): ByteArray = suspendCancellableCoroutine { continuation ->
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (magnetometer == null) {
            continuation.resume("MAG_ERROR:HARDWARE_NOT_FOUND".toByteArray(StandardCharsets.UTF_8))
            return@suspendCancellableCoroutine
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sensorManager.unregisterListener(this)
                val x = String.format("%.2f", event.values[0])
                val y = String.format("%.2f", event.values[1])
                val z = String.format("%.2f", event.values[2])
                val result = "MAG_X:$x,MAG_Y:$y,MAG_Z:$z".toByteArray(StandardCharsets.UTF_8)
                if (continuation.isActive) continuation.resume(result)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_FASTEST)
        continuation.invokeOnCancellation { sensorManager.unregisterListener(listener) }
    }

    // --- REMAINING STUBS ---
    private suspend fun captureGeospatial(context: Context, params: JSONObject): ByteArray = suspendCancellableCoroutine { continuation ->
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        // 1. Verify OS-Level Clearances
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            continuation.resume("GEO_ERROR:PERMISSION_DENIED".toByteArray(StandardCharsets.UTF_8))
            return@suspendCancellableCoroutine
        }

        // 2. Define the Satellite Interception Callback
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                // KINEMATIC KILL: Shut off the GPS radio the exact millisecond we secure the coordinates to prevent Exergy drain.
                locationManager.removeUpdates(this)

                // Format the planetary coordinates with expanded hardware metadata
                val lat = String.format("%.6f", location.latitude)
                val lon = String.format("%.6f", location.longitude)
                val alt = String.format("%.2f", location.altitude)
                val acc = String.format("%.2f", location.accuracy)
                val time = location.time // Epoch MS

                val result = "LAT:$lat,LON:$lon,ALT:$alt,ACC:$acc,TIME:$time".toByteArray(StandardCharsets.UTF_8)

                if (continuation.isActive) continuation.resume(result)
            }

            // Required fallback for older Android API levels if the user physically turns off GPS
            override fun onProviderDisabled(provider: String) {
                if (provider == android.location.LocationManager.GPS_PROVIDER) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume("GEO_ERROR:GPS_RADIO_OFF".toByteArray(StandardCharsets.UTF_8))
                }
            }
        }

        try {
            // 3. Force an absolute live ping to the global GNSS satellite array
            locationManager.requestLocationUpdates(
                android.location.LocationManager.GPS_PROVIDER,
                0L,
                0f,
                listener,
                android.os.Looper.getMainLooper()
            )
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume("GEO_ERROR:${e.message}".toByteArray(StandardCharsets.UTF_8))
        }

        continuation.invokeOnCancellation {
            locationManager.removeUpdates(listener)
        }
    }
    @SuppressLint("MissingPermission")
    private suspend fun captureAmbient(context: Context, params: JSONObject): ByteArray = suspendCancellableCoroutine { continuation ->

        // 1. Verify Microphone Permission
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            continuation.resume("AMB_ERROR:AUDIO_PERMISSION_DENIED".toByteArray(StandardCharsets.UTF_8))
            return@suspendCancellableCoroutine
        }

        // 2. Initialize Barometer (Pressure)
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val pressureSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE)
        var pressureHpa = "N/A"

        val pressureListener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                sensorManager.unregisterListener(this)
                pressureHpa = String.format("%.2f", event.values[0])
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }

        if (pressureSensor != null) {
            sensorManager.registerListener(pressureListener, pressureSensor, android.hardware.SensorManager.SENSOR_DELAY_FASTEST)
        }

        // 3. Initialize Microphone (Decibel Calculation)
        var maxAmplitude = 0
        var decibels = "N/A"

        val minBufferSize = android.media.AudioRecord.getMinBufferSize(
            8000,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == android.media.AudioRecord.ERROR_BAD_VALUE || minBufferSize == android.media.AudioRecord.ERROR) {
            sensorManager.unregisterListener(pressureListener)
            continuation.resume("AMB_ERROR:AUDIO_INIT_FAILED".toByteArray(StandardCharsets.UTF_8))
            return@suspendCancellableCoroutine
        }

        val audioRecord = android.media.AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC,
            8000,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize
        )

        try {
            audioRecord.startRecording()
            
            // AGENT DIRECTIVE: Sample for strict duration of 2000ms
            val samplingDurationMs = 2000L
            val startTime = System.currentTimeMillis()
            val buffer = ShortArray(minBufferSize)
            
            while (System.currentTimeMillis() - startTime < samplingDurationMs) {
                val readResult = audioRecord.read(buffer, 0, minBufferSize)
                if (readResult > 0) {
                    for (i in 0 until readResult) {
                        val amplitude = kotlin.math.abs(buffer[i].toInt())
                        if (amplitude > maxAmplitude) {
                            maxAmplitude = amplitude
                        }
                    }
                }
            }

            // Calculate dB (approximate relative SPL)
            if (maxAmplitude > 0) {
                val db = 20 * Math.log10(maxAmplitude.toDouble() / 32767.0)
                val normalizedDb = 100 + db
                decibels = String.format("%.1f", normalizedDb)
            }
        } catch (e: Exception) {
            decibels = "ERROR"
        } finally {
            audioRecord.stop()
            audioRecord.release()
            sensorManager.unregisterListener(pressureListener)
        }

        if (continuation.isActive) {
            val result = "SPL_DB:$decibels,PRES_HPA:$pressureHpa".toByteArray(StandardCharsets.UTF_8)
            continuation.resume(result)
        }
    }
    private suspend fun captureKinematic(context: Context, params: JSONObject): ByteArray = suspendCancellableCoroutine { continuation ->
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Linear Acceleration isolates physical movement, removing Earth's gravity
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (accelSensor == null) {
            continuation.resume("KINEMATIC_ERROR:HARDWARE_NOT_FOUND".toByteArray(StandardCharsets.UTF_8))
            return@suspendCancellableCoroutine
        }

        // AGENT DIRECTIVE: Record 5 seconds of motion data
        val recordingDurationMs = 5000L
        val startTime = System.currentTimeMillis()
        val motionData = org.json.JSONArray()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (System.currentTimeMillis() - startTime < recordingDurationMs) {
                    val frame = org.json.JSONArray()
                    frame.put(event.values[0])
                    frame.put(event.values[1])
                    frame.put(event.values[2])
                    motionData.put(frame)
                } else {
                    sensorManager.unregisterListener(this)
                    if (continuation.isActive) {
                        val result = "LINEAR_ACCEL_SERIES:$motionData".toByteArray(StandardCharsets.UTF_8)
                        continuation.resume(result)
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // Engage the hardware at maximum velocity (SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_FASTEST)

        continuation.invokeOnCancellation {
            sensorManager.unregisterListener(listener)
        }
    }
    private suspend fun captureNfcRfid(context: Context, params: JSONObject, callback: (String) -> Unit): ByteArray = suspendCancellableCoroutine { continuation ->
        // We must ensure the context is an Activity to bind the NFC Foreground Dispatch
        val activity = context as? android.app.Activity

        if (activity == null) {
            continuation.resume("NFC_ERROR:INVALID_CONTEXT_FOR_HARDWARE".toByteArray(StandardCharsets.UTF_8))
            return@suspendCancellableCoroutine
        }

        val nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(context)

        if (nfcAdapter == null) {
            continuation.resume("NFC_ERROR:HARDWARE_NOT_FOUND".toByteArray(StandardCharsets.UTF_8))
            return@suspendCancellableCoroutine
        }

        if (!nfcAdapter.isEnabled) {
            continuation.resume("NFC_ERROR:NFC_DISABLED_BY_USER".toByteArray(StandardCharsets.UTF_8))
            return@suspendCancellableCoroutine
        }

        // The callback that the OS triggers when the phone physically touches an NFC tag
        val readerCallback = android.nfc.NfcAdapter.ReaderCallback { tag ->
            // KINEMATIC KILL: Instantly disable the reader mode to prevent battery drain
            nfcAdapter.disableReaderMode(activity)

            // Extract the physical UID (Unique Identifier) of the tag
            val uidBytes = tag.id
            val uidHex = uidBytes.joinToString(separator = ":") { byte -> "%02X".format(byte) }

            // AGENT DIRECTIVE: Inject GPS Anchor
            callback("[NFC] Injecting GPS Anchor...")
            CoroutineScope(Dispatchers.IO).launch {
                val geoData = captureGeospatial(context, JSONObject())
                val geoString = String(geoData, StandardCharsets.UTF_8)
                val result = "NFC_UID:$uidHex|GEO_ANCHOR:$geoString".toByteArray(StandardCharsets.UTF_8)

                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }

        try {
            // Engage the NFC hardware antenna in Reader Mode
            // FLAG_READER_NFC_A | FLAG_READER_NFC_B | FLAG_READER_NFC_F | FLAG_READER_NFC_V
            val flags = 1 or 2 or 4 or 8
            nfcAdapter.enableReaderMode(activity, readerCallback, flags, null)

            // Note: In a production UI, you would update the screen here to say "Please tap NFC tag now..."
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume("NFC_ERROR:${e.message}".toByteArray(StandardCharsets.UTF_8))
        }

        continuation.invokeOnCancellation {
            nfcAdapter.disableReaderMode(activity)
        }
    }
    private suspend fun executeStoragePing(context: Context, params: JSONObject): ByteArray = withContext(Dispatchers.IO) {
        // 1. Extract the Agent's Challenge Parameters
        // The Agent asks: "Prove you have 'shard_id' and hash it against 'challenge_nonce'"
        val shardId = params.optString("shard_id", "")
        val challengeNonce = params.optString("challenge_nonce", "")

        if (shardId.isEmpty() || challengeNonce.isEmpty()) {
            return@withContext "STORAGE_ERROR:MISSING_PARAMETERS".toByteArray(StandardCharsets.UTF_8)
        }

        try {
            // 2. Locate the Physical File on Disk
            // In a production DePIN network, shards would be stored in the app's internal filesDir
            val storageDir = java.io.File(context.filesDir, "exergy_shards")
            val targetFile = java.io.File(storageDir, shardId)

            if (!targetFile.exists() || !targetFile.isFile) {
                return@withContext "STORAGE_ERROR:SHARD_NOT_FOUND".toByteArray(StandardCharsets.UTF_8)
            }

            // 3. Cryptographic Condensation (Proof of Possession)
            // We read the file in chunks to prevent OOM errors if the shard is large (e.g., 50MB+)
            val digest = java.security.MessageDigest.getInstance("SHA-256")

            // First, digest the Agent's challenge nonce
            digest.update(challengeNonce.toByteArray(StandardCharsets.UTF_8))

            // Then, digest the physical file bytes
            targetFile.inputStream().use { fis ->
                val buffer = ByteArray(8192) // 8KB buffer
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }

            val proofHash = digest.digest()
            val hexHash = proofHash.joinToString(separator = "") { byte -> "%02X".format(byte) }

            // 4. Return the Verified State
            return@withContext "SHARD_VERIFIED:$hexHash".toByteArray(StandardCharsets.UTF_8)

        } catch (e: Exception) {
            return@withContext "STORAGE_ERROR:${e.message}".toByteArray(StandardCharsets.UTF_8)
        }
    }
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun captureNetworkDensity(context: Context, params: JSONObject): ByteArray = suspendCancellableCoroutine { continuation ->
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager

        // 1. Verify OS-Level Clearances
        // Android requires ACCESS_FINE_LOCATION to scan Wi-Fi because networks can imply location
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            continuation.resume("NET_ERROR:LOCATION_PERMISSION_DENIED".toByteArray(StandardCharsets.UTF_8))
            return@suspendCancellableCoroutine
        }

        try {
            // 2. Physical Reality Capture
            // We request the cached scan results. Initiating a live scan (startScan) is heavily throttled by modern Android OS (once per 2 mins).
            // The cached results are updated constantly by the OS in the background and provide an accurate density snapshot.
            val scanResults = wifiManager.scanResults

            // 3. Density Analysis
            val bssidCount = scanResults.size

            // Calculate a proxy for "noise" or "crowdedness" based on signal strength (RSSI)
            // We count how many networks are physically close (stronger than -70 dBm)
            val closeNetworks = scanResults.count { it.level > -70 }

            // AGENT DIRECTIVE: Inject GPS Anchor
            CoroutineScope(Dispatchers.IO).launch {
                val geoData = captureGeospatial(context, JSONObject())
                val geoString = String(geoData, StandardCharsets.UTF_8)
                val result = "BSSID_TOTAL:$bssidCount,BSSID_CLOSE:$closeNetworks|GEO_ANCHOR:$geoString".toByteArray(StandardCharsets.UTF_8)

                if (continuation.isActive) continuation.resume(result)
            }

        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume("NET_ERROR:${e.message}".toByteArray(StandardCharsets.UTF_8))
        }
    }
    private suspend fun executeBiometricGate(context: Context, params: JSONObject): ByteArray = suspendCancellableCoroutine { continuation ->
        // KINEMATIC UPDATE: BiometricPrompt specifically requires a FragmentActivity
        val activity = context as? androidx.fragment.app.FragmentActivity

        if (activity == null) {
            continuation.resume("BIO_ERROR:INVALID_CONTEXT_FOR_UI".toByteArray(StandardCharsets.UTF_8))
            return@suspendCancellableCoroutine
        }

        // 1. Verify Biometric Hardware Exists
        val biometricManager = androidx.biometric.BiometricManager.from(context)
        when (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> { /* Hardware is ready */ }
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                continuation.resume("BIO_ERROR:NO_HARDWARE".toByteArray(StandardCharsets.UTF_8))
                return@suspendCancellableCoroutine
            }
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                continuation.resume("BIO_ERROR:HARDWARE_BUSY".toByteArray(StandardCharsets.UTF_8))
                return@suspendCancellableCoroutine
            }
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                continuation.resume("BIO_ERROR:USER_NOT_ENROLLED".toByteArray(StandardCharsets.UTF_8))
                return@suspendCancellableCoroutine
            }
            else -> {
                continuation.resume("BIO_ERROR:UNSUPPORTED_DEVICE".toByteArray(StandardCharsets.UTF_8))
                return@suspendCancellableCoroutine
            }
        }

        // 2. Define the Biometric Prompt UI
        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("ExergyNet Identity Verification")
            .setSubtitle("Confirm biological presence to authorize M2M payload.")
            .setNegativeButtonText("Cancel Kinesis")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        // 3. Define the OS Hardware Callbacks
        val executor = androidx.core.content.ContextCompat.getMainExecutor(context)
        val biometricPrompt = androidx.biometric.BiometricPrompt(activity, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {

                // KINEMATIC VICTORY: The OS confirms a human finger/face matched the secure enclave.
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val timestamp = System.currentTimeMillis().toString()
                    val payload = "BIOMETRIC_VERIFIED:$timestamp".toByteArray(StandardCharsets.UTF_8)
                    if (continuation.isActive) continuation.resume(payload)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (continuation.isActive) continuation.resume("BIO_ERROR:$errString".toByteArray(StandardCharsets.UTF_8))
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        // 4. Force execution on the Main UI thread so the prompt is visible
        activity.runOnUiThread {
            try {
                biometricPrompt.authenticate(promptInfo)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume("BIO_ERROR:PROMPT_CRASH_${e.message}".toByteArray(StandardCharsets.UTF_8))
            }
        }

        continuation.invokeOnCancellation {
            biometricPrompt.cancelAuthentication()
        }
    }
    private suspend fun executeAsyncCompute(context: Context, params: JSONObject): ByteArray = withContext(Dispatchers.Default) {

        // 1. THE TETHER CONSTRAINT (Thermodynamic Safety Gate)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val isCharging = batteryManager.isCharging
        val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

        if (!isCharging || batteryLevel < 50) {
            return@withContext "COMPUTE_ERROR:DEVICE_NOT_TETHERED_OR_LOW_BATTERY".toByteArray(StandardCharsets.UTF_8)
        }

        // 2. EXTRACT WORKLOAD PARAMETERS
        val modelUrl = params.optString("model_url", "")
        val dataPayload = params.optString("data_payload", "")

        if (modelUrl.isEmpty() || dataPayload.isEmpty()) {
            return@withContext "COMPUTE_ERROR:MISSING_MODEL_PARAMETERS".toByteArray(StandardCharsets.UTF_8)
        }

        // AGENT DIRECTIVE: Start ComputeWorker for background execution
        val jobIdHex = params.optString("job_id_hex", "")
        val workData = androidx.work.Data.Builder()
            .putString("JOB_ID_HEX", jobIdHex)
            .putString("DATA_PAYLOAD", dataPayload)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ComputeWorker>()
            .setInputData(workData)
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)

        // 3. Return instantly to trigger UI transition
        return@withContext "COMPUTE_STARTED".toByteArray(StandardCharsets.UTF_8)
    }
}