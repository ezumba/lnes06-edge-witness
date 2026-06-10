package com.exergynet.myapplication // MUST MATCH YOUR NAMESPACE

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import androidx.work.ExistingPeriodicWorkPolicy

class MainActivity : FragmentActivity() {
    private lateinit var configManager: ConfigManager
    private lateinit var webView: WebView

    private var capturedOpticalBytes: ByteArray? = null
    private var tempImageUri: Uri? = null
    private var isProfileCapture: Boolean = false
    private var isMessageCapture: Boolean = false   // Batch 3: camera → DLTN image message

    // STABILITY PATCH: Process Death Management
    private var webViewReady = false
    private var pendingImageHash: String? = null
    private val CAMERA_REQUEST_CODE  = 1001
    private val GALLERY_REQUEST_CODE = 1002

    // GPS position cache for dead drop proximity checks
    private var currentLat: Double = 39.7776
    private var currentLon: Double = -86.2945

    // KINEMATIC FIX: RPC Throttling & Balance Caching
    private var lastBalanceFetchMs = 0L
    private val BALANCE_POLL_INTERVAL_MS = 30_000L // Poll RPC every 30 seconds max
    private var cachedBalanceUsd = -1.0

    // DLTN inbound message receiver — refreshes the open conversation in real time
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val fromNodeId = intent?.getStringExtra("fromNodeId") ?: return
            val type       = intent.getStringExtra("type") ?: ""
            callJs("onDLTNMessageReceived", fromNodeId, type)
        }
    }

    // DLTN call state receiver
    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra("state") ?: return
            val peer  = intent.getStringExtra("peer") ?: ""
            // TASK 3: true when the OS severed the socket mid-call (not a hang-up).
            val dropped = intent.getBooleanExtra("dropped", false)
            val reason  = intent.getStringExtra("reason") ?: ""
            callJs("onCallStateChanged", state, peer.take(8), dropped, reason)
        }
    }

    // 1. SURVIVE THE OS KILL: Save the image URI before the OS murders the app
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tempImageUri?.let { outState.putString("TEMP_IMAGE_URI", it.toString()) }
    }

    // Drop-Intel attachment picker (image/video/document). Images are downscaled +
    // base64'd for inline transport; large media returns metadata only (mesh-chunked
    // transport is a follow-up). Result → JS via onAttachmentPicked.
    private val pickAttachmentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processAttachment(uri)
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            sendToLog("[SYSTEM] Geospatial tracking authorized.")
        }
        // Once the user grants the BLE runtime permissions, ignite the mesh
        // immediately — the service's initial start() likely deferred because the
        // permissions weren't granted yet at boot.
        val scanOk = permissions[Manifest.permission.BLUETOOTH_SCAN] == true
        val connOk = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (scanOk || connOk) {
            sendToLog("[SYSTEM] BLE mesh permissions granted — igniting transceiver.")
            // The service may still be constructing; retry briefly until instance is live.
            lifecycleScope.launch {
                repeat(5) {
                    val svc = DLTNForegroundService.instance
                    if (svc != null) { svc.ensureMeshStarted(); return@launch }
                    kotlinx.coroutines.delay(400)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)

        // 2. RESURRECTION: Recover the URI if the app was killed
        if (savedInstanceState != null) {
            val uriString = savedInstanceState.getString("TEMP_IMAGE_URI")
            if (uriString != null) tempImageUri = Uri.parse(uriString)
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // BLUE TEAM (C2): lock the file:// origin down. No cross-origin file
            // access, no file-URL universal access, no mixed content. Injected JS
            // can no longer read local files or reach arbitrary origins.
            settings.allowUniversalAccessFromFileURLs = false
            settings.allowFileAccessFromFileURLs = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // A bare WebChromeClient silently swallows window.alert/confirm/prompt, which
            // made the chat 3-dot menu (Search/Delete) appear non-functional. Render them
            // as native dialogs so JS dialog calls actually work.
            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage(message ?: "")
                        .setPositiveButton("OK") { _, _ -> result.confirm() }
                        .setOnCancelListener { result.cancel() }
                        .show()
                    return true
                }
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage(message ?: "")
                        .setPositiveButton("OK") { _, _ -> result.confirm() }
                        .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }
                        .show()
                    return true
                }
                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                    val input = EditText(this@MainActivity).apply { setText(defaultValue ?: "") }
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage(message ?: "")
                        .setView(input)
                        .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                        .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }
                        .show()
                    return true
                }
            }
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    webViewReady = true

                    try {
                        ExergyStrongBox.generateHardwareKey()
                    } catch (e: Exception) {
                        sendToLog(">>> [FATAL] StrongBox Crash: ${e.message}")
                    }

                    // 3. REHYDRATE UI: If we died mid-job, force the HTML back to the active screen
                    val savedJobId = configManager.activeJobId
                    val savedOpcode = configManager.activeJobOpcode
                    if (savedJobId.isNotEmpty()) {
                        callJs("restoreActiveJob", savedJobId, savedOpcode)
                    }

                    // 4. DELIVER THE PAYLOAD: If the camera returned before the HTML loaded, deliver the hash now
                    if (pendingImageHash != null) {
                        callJs("onImageCaptured", pendingImageHash)
                        pendingImageHash = null
                    }
                }
            }
        }
        setContentView(webView)

        webView.addJavascriptInterface(ExergyKinesisBridge(), "ExergyKinesis")
        webViewReady = false
        webView.loadUrl("file:///android_asset/index.html")

        // Deep link from notification tap
        intent?.getStringExtra(NotificationHelper.EXTRA_TARGET_SCREEN)?.let { screen ->
            val jobId = intent.getStringExtra(NotificationHelper.EXTRA_JOB_ID) ?: ""
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1200) // wait for WebView ready
                runOnUiThread {
                    // BLUE TEAM (C1): intent extras are attacker-reachable (exported activity);
                    // JSON-quote every value before it enters the JS context.
                    val q = { s: String -> org.json.JSONObject.quote(s) }
                    val js = if (jobId.isNotEmpty())
                        "javascript:go(${q(screen)});currentCaptureJobId=${q(jobId)};"
                    else
                        "javascript:go(${q(screen)});"
                    webView.evaluateJavascript(js, null)
                }
            }
        }

        // Base permissions always required
        // BLUE TEAM (H4): CALL_PHONE and READ_CONTACTS no longer requested — the
        // bridge methods that used them (makePhoneCall, getPhoneContacts) were pruned.
        val permissionList = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            // WebRTC voice capture needs RECORD_AUDIO granted at runtime, or the
            // audio track is silent. CAMERA (above) covers the video capturer.
            Manifest.permission.RECORD_AUDIO
        )
        // DLTN: BLUETOOTH_SCAN / CONNECT / ADVERTISE are runtime (dangerous)
        // permissions on API 31+ (Android 12+). Without SCAN the mesh can't
        // discover peers; without CONNECT every GATT op (server, connect, read,
        // write) throws SecurityException and is silently swallowed → no message
        // or call ever crosses. All three MUST be requested, not just ADVERTISE.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionList.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionList.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionList.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        // DLTN: NEARBY_WIFI_DEVICES requires API 33+ (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionList.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        requestPermissions.launch(permissionList.toTypedArray())

        // AGENT DIRECTIVE: Enqueue SettlementPoller for periodic polling (Production)
        val periodicRequest = PeriodicWorkRequestBuilder<SettlementPoller>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueue(periodicRequest)

        // DLTN Mesh Relay — Phase A passive beacon
        try {
            val dltnIntent = android.content.Intent(
                this,
                DLTNForegroundService::class.java
            )
            startForegroundService(dltnIntent)
            android.util.Log.i("MainActivity", "[DLTN] Mesh relay service ignited")
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "[DLTN] Service start failed: ${e.message}")
        }

        // Register DLTN call-state receiver
        val callFilter = IntentFilter("com.exergynet.DLTN_CALL_STATE")
        registerReceiver(callStateReceiver, callFilter, RECEIVER_NOT_EXPORTED)

        // Register DLTN inbound message receiver so conversations auto-refresh
        val msgFilter = IntentFilter("com.exergynet.DLTN_MESSAGE_RECEIVED")
        registerReceiver(messageReceiver, msgFilter, RECEIVER_NOT_EXPORTED)

        // AGENT DIRECTIVE: Live State Synapse (Kotlin -> JS)
        lifecycleScope.launch(Dispatchers.IO) {
            val db = ExergyDatabase.getDatabase(this@MainActivity)
            while (true) {
                val jobs = db.jobStateDao().getAllJobs()
                val jsonJobs = JSONArray()
                for (job in jobs) {
                    val obj = JSONObject()
                    obj.put("jobIdHex", job.jobIdHex)
                    obj.put("vector", job.jobType)
                    obj.put("status", job.status.name)
                    obj.put("rewardMicroUsdc", job.rewardMicroUsdc)
                    obj.put("updatedAt", job.updatedAt)
                    jsonJobs.put(obj)
                }
                val safeJson = jsonJobs.toString()

                // AGENT DIRECTIVE: Fetch Wallet Balance from Base Mainnet (High Priority)
                val currentWallet = configManager.evmPayoutAddress
                if (currentWallet.isNotEmpty() && configManager.isValidEvmAddress(currentWallet)) {
                    if (System.currentTimeMillis() - lastBalanceFetchMs > BALANCE_POLL_INTERVAL_MS) {
                        try {
                            cachedBalanceUsd = fetchBaseUsdcBalance(currentWallet)
                            lastBalanceFetchMs = System.currentTimeMillis()
                        } catch (e: Exception) {
                            // Keep cached balance if RPC times out
                        }
                    }
                }

                val pendingPassiveCount = db.passiveSensorDao().getPendingCount()
                val totalPassiveValueUsd = String.format("%.6f", pendingPassiveCount * 0.00068)

                withContext(Dispatchers.Main) {
                    if (webViewReady) {
                        callJs("hydrateLiveJobs", safeJson)

                        // Push live balance to UI if successfully fetched
                        if (cachedBalanceUsd >= 0) {
                            webView.evaluateJavascript("javascript:updateBalance($cachedBalanceUsd);", null)
                        }

                        // STABILITY PATCH: Sync Onboarding Status to UI
                        val isOnboarded = configManager.isOnboarded
                        webView.evaluateJavascript("javascript:syncOnboardingStatus($isOnboarded);", null)

                        // AGENT DIRECTIVE: Sync Compute Progress from DB to JS
                        val computingJob = jobs.firstOrNull { it.status == JobStatus.PROVING }
                        if (computingJob != null) {
                            val elapsed = System.currentTimeMillis() - computingJob.updatedAt
                            val percent = (elapsed / 300).toInt().coerceAtMost(100)
                            webView.evaluateJavascript("javascript:updateComputeProgress($percent);", null)
                        }

                        callJs("updateEnvPassiveStats", pendingPassiveCount, totalPassiveValueUsd)
                    }
                }

                kotlinx.coroutines.delay(2000)
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(callStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(messageReceiver)   } catch (_: Exception) {}
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == CAMERA_REQUEST_CODE) {
            if (resultCode == RESULT_OK && tempImageUri != null) {
                if (isMessageCapture) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val inputStream = contentResolver.openInputStream(tempImageUri!!)
                            val jpegBytes = inputStream?.readBytes() ?: ByteArray(0)
                            inputStream?.close()
                            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                            withContext(Dispatchers.Main) { callJs("onMessageImageReady", base64) }
                        } catch (e: Exception) {
                            sendToLog(">>> [DLTN] Message image read failed: ${e.message}")
                        } finally { isMessageCapture = false }
                    }
                    return
                }
                if (isProfileCapture) {
                    sendToLog("[PROFILE] New profile picture captured. Optimizing...")
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val inputStream = contentResolver.openInputStream(tempImageUri!!)
                            val jpegBytes = inputStream?.readBytes() ?: ByteArray(0)
                            inputStream?.close()
                            
                            val destFile = File(filesDir, "profile_picture.jpg")
                            destFile.writeBytes(jpegBytes)
                            configManager.profilePicturePath = destFile.absolutePath
                            
                            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                            withContext(Dispatchers.Main) {
                                callJs("onProfilePictureUpdated", base64)
                                sendToLog("[PROFILE] Identity visual updated.")
                            }
                        } catch (e: Exception) {
                            sendToLog(">>> [ERROR] Profile picture save failed: ${e.message}")
                        } finally {
                            isProfileCapture = false
                        }
                    }
                    return
                }

                sendToLog("[OPTICAL] Image written to physical disk. Engaging condensation...")

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val inputStream = contentResolver.openInputStream(tempImageUri!!)
                        val jpegBytes = inputStream?.readBytes() ?: ByteArray(0)
                        inputStream?.close()

                        // KINEMATIC DISK CLEANUP: Securely purge the hardware buffer after hashing
                        try {
                            val path = tempImageUri?.path?.substringAfter(":")
                            if (path != null) {
                                val file = File(cacheDir, "exergy_images/${path.substringAfterLast("/")}")
                                if (file.exists()) file.delete()
                            }
                        } catch (e: Exception) { /* Silent fail for non-critical cleanup */ }

                        if (jpegBytes.isEmpty()) return@launch

                        val digest = MessageDigest.getInstance("SHA-256")
                        val hash = digest.digest(jpegBytes)
                        capturedOpticalBytes = jpegBytes // Store full JPEG for radiation
                        
                        val hashString = Base64.encodeToString(hash, Base64.NO_WRAP)

                        withContext(Dispatchers.Main) {
                            sendToLog("[OPTICAL] Physical truth hashed: ${hashString.substring(0, 10)}...")
                            // 5. THE RACE CONDITION FIX: Queue the hash if the HTML is still loading
                            if (webViewReady) {
                                callJs("onImageCaptured", hashString)
                            } else {
                                pendingImageHash = hashString
                            }
                        }
                    } catch (e: Throwable) {
                        withContext(Dispatchers.Main) { sendToLog(">>> [FATAL] Cryptographic Condensation Crash: ${e.message}") }
                    }
                }
            } else {
                sendToLog(">>> [ERROR] Optical capture failed or was aborted.")
            }
        }

        if (requestCode == GALLERY_REQUEST_CODE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val jpegBytes = inputStream?.readBytes() ?: ByteArray(0)
                    inputStream?.close()
                    val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                    withContext(Dispatchers.Main) { callJs("onMessageImageReady", base64) }
                } catch (e: Exception) {
                    sendToLog(">>> [DLTN] Gallery image read failed: ${e.message}")
                } finally { isMessageCapture = false }
            }
            return
        }
    }

    // BLUE TEAM (C1): the ONLY sanctioned Kotlin→JS path. Every value is
    // JSON-quoted via JSONObject.quote (handles \, ", newlines, </script>,
    // U+2028/2029, control chars) so attacker-controlled data can never break
    // out of the string literal into executable script. Numbers/bools pass raw.
    private fun callJs(fn: String, vararg args: Any?) {
        val sb = StringBuilder("javascript:").append(fn).append('(')
        args.forEachIndexed { i, a ->
            if (i > 0) sb.append(',')
            when (a) {
                null -> sb.append("null")
                is Boolean, is Int, is Long, is Double, is Float -> sb.append(a.toString())
                else -> sb.append(org.json.JSONObject.quote(a.toString()))
            }
        }
        sb.append(");")
        runOnUiThread { webView.evaluateJavascript(sb.toString(), null) }
    }

    private fun sendToLog(msg: String) {
        callJs("appendSystemLog", msg)
    }

    inner class ExergyKinesisBridge {
        @JavascriptInterface
        fun loadConfiguration() {
            val ip = configManager.aggregatorIp
            val wallet = configManager.evmPayoutAddress
            val profilePath = configManager.profilePicturePath
            
            var profilePicBase64 = ""
            if (profilePath.isNotEmpty()) {
                try {
                    val file = File(profilePath)
                    if (file.exists()) {
                        profilePicBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    }
                } catch (e: Exception) {}
            }

            val networkMode = configManager.networkMode
            callJs("hydrateConfiguration", ip, wallet, profilePicBase64)
            callJs("onNetworkModeLoaded", networkMode)
            // STABILITY PATCH: Notify JS of onboarding status on load
            callJs("syncOnboardingStatus", configManager.isOnboarded)
        }

        @JavascriptInterface
        fun saveOnboardingStatus(onboarded: Boolean) {
            configManager.isOnboarded = onboarded
        }

        @JavascriptInterface
        fun saveConfiguration(ip: String, wallet: String) {
            if (wallet.isNotEmpty() && !configManager.isValidEvmAddress(wallet)) {
                sendToLog(">>> [ERROR] Invalid EIP-55 Checksum Address. Payout rejected.")
                return
            }
            configManager.aggregatorIp = ip
            configManager.evmPayoutAddress = wallet
        }

        @JavascriptInterface
        fun abandonActiveJob() {
            val jobIdHex = configManager.activeJobId
            if (jobIdHex.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = ExergyDatabase.getDatabase(this@MainActivity)
                    db.jobStateDao().updateJobStatus(jobIdHex, JobStatus.INTERRUPTED)
                }
            }
            configManager.activeJobId = ""
            configManager.activeJobOpcode = ""
            capturedOpticalBytes = null
            sendToLog("[SYSTEM] Active job state purged.")
        }

        @JavascriptInterface
        fun savePassiveMode(enabled: Boolean) {
            configManager.passiveModeEnabled = enabled
            sendToLog("[CONFIG] Passive Witness Mode ${if (enabled) "ENABLED" else "DISABLED"}")
            if (enabled) {
                val passive = androidx.work.PeriodicWorkRequestBuilder<PassiveHarvestWorker>(
                    15, TimeUnit.MINUTES
                ).build()
                WorkManager.getInstance(this@MainActivity).enqueueUniquePeriodicWork(
                    "passive_harvest",
                    ExistingPeriodicWorkPolicy.KEEP,
                    passive
                )
                sendToLog("[PASSIVE] WorkManager harvest scheduled every 15 minutes.")
            } else {
                WorkManager.getInstance(this@MainActivity).cancelUniqueWork("passive_harvest")
                sendToLog("[PASSIVE] WorkManager harvest cancelled.")
            }
        }

        @JavascriptInterface
        fun savePassiveSettings(batteryGuard: Int, scheduleStart: String, scheduleEnd: String) {
            configManager.passiveBatteryGuard = batteryGuard
            configManager.passiveScheduleStart = scheduleStart
            configManager.passiveScheduleEnd = scheduleEnd
            sendToLog("[CONFIG] Passive settings saved: $batteryGuard%, $scheduleStart-$scheduleEnd")
        }

        @JavascriptInterface
        fun getPassiveSettings() {
            val bg = configManager.passiveBatteryGuard
            val start = configManager.passiveScheduleStart
            val end = configManager.passiveScheduleEnd
            callJs("hydratePassiveSettings", bg, start, end)
        }

        @JavascriptInterface
        fun getPassiveMode() {
            val isEnabled = configManager.passiveModeEnabled
            runOnUiThread { webView.evaluateJavascript("javascript:setPassiveToggle($isEnabled);", null) }
        }

        @JavascriptInterface
        fun getJobHistory() {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = ExergyDatabase.getDatabase(this@MainActivity)
                val settledJobs = db.jobStateDao().getAllJobs().filter { it.status == JobStatus.SETTLED }
                val jsonJobs = JSONArray()
                for (job in settledJobs) {
                    val obj = JSONObject()
                    obj.put("jobIdHex", job.jobIdHex)
                    obj.put("vector", job.jobType)
                    obj.put("status", job.status.name)
                    obj.put("rewardMicroUsdc", job.rewardMicroUsdc)
                    obj.put("updatedAt", job.updatedAt)
                    jsonJobs.put(obj)
                }
                val safeJson = jsonJobs.toString()
                withContext(Dispatchers.Main) { callJs("hydrateJobHistory", safeJson) }
            }
        }

        @JavascriptInterface
        fun checkTetherStatus() {
            sendToLog("[SYSTEM] Checking battery/tether status...")
            val intentFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, intentFilter)

            val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == android.os.BatteryManager.BATTERY_STATUS_FULL

            val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: 100
            val batteryPct = (level * 100 / scale.toFloat()).toInt()

            runOnUiThread {
                webView.evaluateJavascript("javascript:onTetherStatusReceived($isCharging, $batteryPct);", null)
            }
        }

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            openSecureBrowser(url)
        }

        @JavascriptInterface
        fun getCurrentLocation() {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            if (location != null) {
                currentLat = location.latitude
                currentLon = location.longitude
                runOnUiThread {
                    webView.evaluateJavascript("javascript:onCurrentLocationReceived(${location.latitude}, ${location.longitude});", null)
                    webView.evaluateJavascript("javascript:window._streetsUpdateUserPos&&window._streetsUpdateUserPos(${location.latitude},${location.longitude});", null)
                }
            }
        }

        @JavascriptInterface
        fun requestLocationVerification(targetLat: Double, targetLon: Double, geofenceMeters: Int) {
            // BLUE TEAM (M2): the geofenceMeters==0 auto-success backdoor is removed.
            // A geofence of 0 now means "no proximity constraint" — NOT "skip GPS".
            // The device must still acquire a valid live GPS fix; only then is the
            // witness verified. No fix → verification fails.
            val requireProximity = geofenceMeters > 0
            if (requireProximity) {
                sendToLog("[GPS] Polling satellite array for target: $targetLat, $targetLon...")
            } else {
                sendToLog("[GPS] Global job — acquiring a valid live GPS fix (no proximity constraint)...")
            }
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                sendToLog(">>> [ERROR] GPS Permission Denied.")
                runOnUiThread { webView.evaluateJavascript("javascript:onLocationVerified(false, 9999);", null) }
                return
            }

            val handler = Handler(Looper.getMainLooper())
            var settled = false

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (settled) return
                    settled = true
                    locationManager.removeUpdates(this)
                    handler.removeCallbacksAndMessages(null)

                    if (!requireProximity) {
                        // No proximity constraint, but we DID obtain a valid live fix.
                        sendToLog("[GPS] Valid live fix acquired: ${location.latitude}, ${location.longitude}")
                        runOnUiThread { webView.evaluateJavascript("javascript:onLocationVerified(true, 0);", null) }
                        return
                    }

                    val results = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, targetLat, targetLon, results)
                    val distance = results[0]

                    if (distance <= geofenceMeters.toFloat()) {
                        sendToLog("[GPS] Lock acquired. Variance: ${distance.toInt()}m")
                        runOnUiThread { webView.evaluateJavascript("javascript:onLocationVerified(true, ${distance.toInt()});", null) }
                    } else {
                        sendToLog(">>> [HALT] Geofence violation. You are ${distance.toInt()}m from target (Allowed: ${geofenceMeters}m).")
                        runOnUiThread { webView.evaluateJavascript("javascript:onLocationVerified(false, ${distance.toInt()});", null) }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                sendToLog(">>> [ERROR] GPS provider unavailable: ${e.message}")
                runOnUiThread { webView.evaluateJavascript("javascript:onLocationVerified(false, -1);", null) }
                return
            }

            // 8-second timeout fallback
            handler.postDelayed({
                if (!settled) {
                    settled = true
                    locationManager.removeUpdates(listener)
                    sendToLog(">>> [TIMEOUT] GPS lock timed out after 8s. Ensure location services are active.")
                    runOnUiThread { webView.evaluateJavascript("javascript:onLocationVerified(false, -1);", null) }
                }
            }, 8000L)
        }

        @JavascriptInterface
        fun openNativeMaps(lat: Double, lon: Double, label: String) {
            val geoUri = Uri.parse("geo:0,0?q=$lat,$lon(${Uri.encode(label)})")
            val intent = Intent(Intent.ACTION_VIEW, geoUri)
            startActivity(intent)
        }

        @JavascriptInterface
        fun testAggregatorConnection(ip: String) {
            appendToLog("[NETWORK] Testing connection to $ip...")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val cleanIp = ip.substringBefore(":")
                    val url = if (cleanIp.contains(".")) URL("http://$ip/api/v1/jobs/nearby") 
                             else URL("https://$ip/api/v1/jobs/nearby")
                    
                    val start = System.currentTimeMillis()
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    
                    val code = conn.responseCode
                    val latency = System.currentTimeMillis() - start
                    
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("javascript:onConnectionTestResult(true, $latency, $code);", null)
                    }
                } catch (e: Exception) {
                    callJs("onConnectionTestResult", false, -1, 0, e.message ?: "")
                }
            }
        }

        private fun appendToLog(msg: String) {
            sendToLog(msg)
        }

        @JavascriptInterface
        fun fetchLiveMarketplace() {
            sendToLog("[NETWORK] Native bridge polling L0 Indexer...")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val aggregatorHost = configManager.aggregatorIp.ifEmpty { "explorer-api.exergynet.org" }
                    val protocol = if (aggregatorHost.substringBefore(":").all { it.isDigit() || it == '.' }) "http" else "https"
                    val url = URL("$protocol://$aggregatorHost/api/v1/jobs/nearby")

                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    
                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                        withContext(Dispatchers.Main) { callJs("onMarketplaceDataReceived", response) }
                    } else {
                        withContext(Dispatchers.Main) { callJs("onMarketplaceDataReceived", "[]") }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { callJs("onMarketplaceDataReceived", "ERROR:${e.message}") }
                }
            }
        }

        @JavascriptInterface
        fun saveActiveJobState(jobIdHex: String, opcode: String) {
            configManager.activeJobId = jobIdHex
            configManager.activeJobOpcode = opcode
        }

        @JavascriptInterface
        fun requestCamera() {
            isProfileCapture = false
            sendToLog("[OPTICAL] Initializing secure camera hardware buffer...")
            runOnUiThread {
                try {
                    val imagesDir = File(cacheDir, "exergy_images").apply { mkdirs() }
                    
                    // KINEMATIC SECURITY PATCH: Randomized, non-deterministic file allocation.
                    // Eradicates Time-of-Check to Time-of-Use (TOCTOU) file-swap attacks.
                    val imageFile = File.createTempFile("exergy_opt_", ".jpg", imagesDir)

                    tempImageUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        imageFile
                    )

                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, tempImageUri)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    startActivityForResult(intent, CAMERA_REQUEST_CODE)

                } catch (e: Exception) {
                    sendToLog(">>> [FATAL] Secure FileProvider Failure: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun requestProfilePicture() {
            isProfileCapture = true
            sendToLog("[PROFILE] Triggering camera for identity visual...")
            runOnUiThread {
                try {
                    val imagesDir = File(cacheDir, "exergy_images").apply { mkdirs() }
                    val imageFile = File.createTempFile("exergy_profile_", ".jpg", imagesDir)
                    tempImageUri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", imageFile)
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, tempImageUri)
                    startActivityForResult(intent, CAMERA_REQUEST_CODE)
                } catch (e: Exception) {
                    sendToLog(">>> [ERROR] Profile camera failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun openSecureBrowser(url: String) {
            // STRICT VALIDATION GATE: Only allow explicit ExergyNet and Base infrastructure domains
            if (url.startsWith("https://basescan.org/") || 
                url.startsWith("https://sepolia.basescan.org/") || 
                url.startsWith("https://exergynet.org/")) {
                
                sendToLog("[SYSTEM] Opening verified external domain: $url")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } else {
                sendToLog(">>> [SECURITY HALT] Unverified external URL blocked by native bridge.")
            }
        }

        @JavascriptInterface
        fun routeHardwareRequest(opcode: String) {
            if (opcode == "0x01") {
                requestCamera()
            } else {
                sendToLog("[SYSTEM] Hardware path armed for OpCode: $opcode")
                callJs("onHardwareDataAcquired", opcode, "Hardware sensor locked and armed.")
            }
        }

        @JavascriptInterface
        fun authenticateBiometric() {
            runOnUiThread {
                val executor = ContextCompat.getMainExecutor(this@MainActivity)
                val biometricPrompt = BiometricPrompt(this@MainActivity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            sendToLog("[SECURITY] Biometric identity verified.")
                            webView.evaluateJavascript("javascript:onBiometricResult(true);", null)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            sendToLog(">>> [SECURITY] Auth Error: $errString")
                            callJs("onBiometricResult", false, errString.toString())
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            sendToLog(">>> [SECURITY] Auth Failed.")
                        }
                    })

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Witness Identity Required")
                    .setSubtitle("Authenticate to access the ExergyNet membrane.")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()

                biometricPrompt.authenticate(promptInfo)
            }
        }

        @JavascriptInterface
        fun triggerNewJobNotification(title: String, reward: String) {
            NotificationHelper.showNewJobNotification(this@MainActivity, title, reward)
        }

        @JavascriptInterface
        fun triggerDynamicStrike(opcode: String, jobIdHex: String, rewardMicroUsdc: Long) {
            val rawIp = configManager.aggregatorIp.trim()
            val targetIp = rawIp.substringBefore(":")

            // RELAY GATE: domain endpoint = HTTPS relay, numeric IP = TCP direct
            val isRelayMode = targetIp.isNotEmpty() && !targetIp.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))

            if (targetIp.isEmpty()) {
                sendToLog(">>>[HALT] Aggregator IP is missing. Please save configuration first.")
                return
            }

            if (isRelayMode) {
                sendToLog("[RELAY] Domain detected: $rawIp — routing via HTTPS Apex relay...")
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val byteOpcode = opcode.replace("0x", "").toByte(16)
                        val jobType = JobType.values().firstOrNull { it.opcode == byteOpcode }
                        val vectorName = jobType?.name ?: "UNKNOWN"

                        // BLUE TEAM (H3 remnant): never hash a zero/empty byte array — that is a
                        // forgeable constant carrying no thermodynamic weight. OPTICAL must have a
                        // real capture; every other vector hashes the ACTUAL live sensor reading.
                        val payloadBytes: ByteArray = if (jobType == JobType.OPTICAL) {
                            capturedOpticalBytes ?: run {
                                withContext(Dispatchers.Main) {
                                    sendToLog(">>> [HALT] No optical capture in memory. Capture an image before relaying.")
                                    webView.evaluateJavascript("javascript:setRadiateButtonState('idle');", null)
                                }
                                return@launch
                            }
                        } else {
                            if (jobType == null) {
                                withContext(Dispatchers.Main) {
                                    sendToLog(">>> [HALT] Unknown vector $opcode — refusing to submit a forgeable proof.")
                                    webView.evaluateJavascript("javascript:setRadiateButtonState('idle');", null)
                                }
                                return@launch
                            }
                            val edgeJob = EdgeJob(
                                jobId = hexStringToByteArray(jobIdHex),
                                jobType = jobType,
                                parameters = JSONObject(),
                                rewardMicroUsdc = rewardMicroUsdc,
                                isActiveJob = true
                            )
                            JobOrchestrator.captureRawSensorData(this@MainActivity, edgeJob) { msg -> sendToLog(msg) }
                        }

                        if (payloadBytes.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                sendToLog(">>> [HALT] Sensor capture returned no data — proof aborted.")
                                webView.evaluateJavascript("javascript:setRadiateButtonState('idle');", null)
                            }
                            return@launch
                        }

                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val proofHash = "0x" + digest.digest(payloadBytes)
                            .joinToString("") { "%02x".format(it) }

                        val networkTag = if (configManager.networkMode == "testnet") "Base Sepolia" else "Base Mainnet"
                        val body = org.json.JSONObject().apply {
                            put("job_id_hex", jobIdHex)
                            put("miner_id", configManager.evmPayoutAddress)
                            put("payment_address", configManager.evmPayoutAddress)
                            put("reward_micro_usdc", rewardMicroUsdc)
                            put("vector", vectorName)
                            put("proof_hash", proofHash)
                            put("payload_url", "")
                            put("network", networkTag)
                        }.toString()

                        val relayUrl = java.net.URL("https://$rawIp/api/job/complete")
                        val conn = relayUrl.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.connectTimeout = 12000
                        conn.readTimeout = 12000
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body.toByteArray()) }
                        val code = conn.responseCode

                        withContext(Dispatchers.Main) {
                            if (code in 200..299) {
                                sendToLog("[RELAY] ✓ Job submitted via HTTPS. Code: $code")
                                handleStrikeResult("JOB_QUEUED via relay", jobIdHex, rewardMicroUsdc, vectorName, proofHash)
                            } else {
                                sendToLog(">>>[RELAY] Server returned HTTP $code")
                                webView.evaluateJavascript("javascript:setRadiateButtonState('idle');", null)
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            sendToLog(">>>[RELAY] Failed: ${e.message}")
                            webView.evaluateJavascript("javascript:setRadiateButtonState('idle');", null)
                        }
                    }
                }
                return  // Do NOT fall through to TCP path
            }

            val jobIdBytes = hexStringToByteArray(jobIdHex)
            val byteOpcode = opcode.replace("0x", "").toByte(16)

            val jobType = JobType.values().firstOrNull { it.opcode == byteOpcode }
            if (jobType == null) {
                sendToLog(">>>[FATAL ERROR] Invalid OpCode requested by UI.")
                return
            }

            // STABILITY PATCH v3: Save Active Job State
            configManager.activeJobId = jobIdHex
            configManager.activeJobOpcode = opcode

            sendToLog("----------------------------")
            sendToLog("[EDGE] Payload formulation initiated for $jobType...")

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val incomingJob = EdgeJob(
                        jobId = jobIdBytes,
                        jobType = jobType,
                        parameters = JSONObject(),
                        rewardMicroUsdc = rewardMicroUsdc,
                        isActiveJob = true
                    )

                    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    val pubKey = keyStore.getCertificate("exergynet_edge_witness_key").publicKey.encoded

                    if (jobType == JobType.OPTICAL) {
                        if (capturedOpticalBytes == null) {
                            sendToLog(">>> [HALT] No optical data in memory. Capture image first.")
                            return@launch
                        }
                        JobOrchestrator.executeJobWithData(this@MainActivity, incomingJob, pubKey, targetIp, capturedOpticalBytes!!) { logMessage ->
                            handleStrikeResult(logMessage, jobIdHex, rewardMicroUsdc)
                        }
                    } else {
                        JobOrchestrator.executeJob(this@MainActivity, incomingJob, pubKey, targetIp) { logMessage ->
                            handleStrikeResult(logMessage, jobIdHex, rewardMicroUsdc)
                        }
                    }
                } catch (e: Exception) {
                    sendToLog(">>> [FATAL ERROR]: ${e.message}")
                }
            }
        }

        private fun handleStrikeResult(logMessage: String, jobIdHex: String, reward: Long, jobType: String = "", proofHash: String = "") {
            sendToLog(logMessage)
            if (logMessage.contains("JOB_QUEUED") || logMessage.contains("OMEGA VICTORY")) {
                configManager.activeJobId = ""
                configManager.activeJobOpcode = ""

                // BLUE TEAM (H3): no more SecureRandom "ghost" proofs. The hash shown
                // to the UI is the SAME deterministic SHA-256 of the captured sensor
                // payload that was submitted on-chain. Relay path passes the real hash
                // it computed; other paths recompute SHA-256 over the captured bytes.
                val sealedProofHash = if (proofHash.isNotEmpty()) {
                    proofHash
                } else {
                    val bytes = capturedOpticalBytes ?: ByteArray(0)
                    "0x" + MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                }

                callJs("onProofSealed", jobIdHex, reward, sealedProofHash)

                // Relay-mode jobs bypass sealAndRadiate, so they have no DB entry.
                // Create one here and walk it through the pipeline so the Pending tab
                // and SettlementPoller can see it.
                if (jobType.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = ExergyDatabase.getDatabase(this@MainActivity)
                        db.jobStateDao().insertJob(
                            JobStateEntity(jobIdHex = jobIdHex, jobType = jobType,
                                rewardMicroUsdc = reward, status = JobStatus.QUEUED_LOCAL)
                        )
                        kotlinx.coroutines.delay(2000)
                        db.jobStateDao().updateJobStatus(jobIdHex, JobStatus.TRANSMITTING)
                        kotlinx.coroutines.delay(3000)
                        db.jobStateDao().updateJobStatus(jobIdHex, JobStatus.AGGREGATING)
                    }
                }

                val delayedPoller = OneTimeWorkRequestBuilder<SettlementPoller>()
                    .setInitialDelay(12, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(this@MainActivity).enqueue(delayedPoller)
            }
        }



        @JavascriptInterface
        fun saveNetworkMode(mode: String) {
            val clean = if (mode == "testnet") "testnet" else "mainnet"
            configManager.networkMode = clean
            sendToLog("[NETWORK] Mode set to: ${clean.uppercase()} — ${
                if (clean == "testnet") "Base Sepolia (84532)" else "Base Mainnet (8453)"
            }")
        }

        @JavascriptInterface
        fun getNetworkMode() {
            callJs("onNetworkModeLoaded", configManager.networkMode)
        }

        @JavascriptInterface
        fun scanForProver() {
            sendToLog("[DISCOVERY] Scanning LAN for Desktop Prover beacon (6s timeout)...")
            lifecycleScope.launch(Dispatchers.IO) {
                val prover = ProverDiscovery.findProver()
                withContext(Dispatchers.Main) {
                    if (prover != null) {
                        val resolvedIp = if (prover.path == ProverDiscovery.DiscoveryPath.LAN_DIRECT)
                            prover.ip else "explorer-api.exergynet.org"
                        val path = prover.path.name
                        configManager.aggregatorIp = resolvedIp
                        sendToLog("[DISCOVERY] Prover found: ${prover.ip}:${prover.port} via $path [${prover.nodeId}]")
                        // Pass resolvedIp (not raw sentinel) so the JS input field shows the correct endpoint
                        callJs("onProverDiscovered", resolvedIp, prover.port, path, prover.nodeId)
                    } else {
                        sendToLog(">>> [DISCOVERY] No prover found on LAN. Enter IP manually or use relay.")
                        callJs("onProverDiscovered", null, 0, "NONE", "")
                    }
                }
            }
        }

        // ── Communications Bridge ──────────────────────────────────────────
        // BLUE TEAM (C3/H4): makePhoneCall, sendSmsMessage, and getPhoneContacts
        // were pruned. ExergyNet is a compute/sensor verification network, not a
        // telephony dialer — auto-dial (toll fraud), SMS, and contact exfil have no
        // legitimate function here and were the highest-impact bridge surface.
        // openDialer is retained: it only opens the system dialer (ACTION_DIAL),
        // requires no permission, and cannot place a call without user action.

        @JavascriptInterface
        fun openDialer(number: String) {
            runOnUiThread {
                try {
                    val cleaned = number.replace(Regex("[^+0-9]"), "")
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleaned"))
                    startActivity(intent)
                } catch (e: Exception) {
                    sendToLog(">>> [COMMS] Dialer failed: ${e.message}")
                }
            }
        }

        // ── End Communications Bridge ──────────────────────────────────────

        // ── DLTN Messenger Bridge ──────────────────────────────────────────

        @JavascriptInterface
        fun sendDLTNMessage(toNodeId: String, text: String) {
            val svc = getDLTNService() ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                val msg = svc.messenger.sendText(toNodeId, text)
                callJs("onDLTNMessageQueued", msg.id, msg.toNodeId)
            }
        }

        // Capture a photo for a DLTN image message → onMessageImageReady(base64).
        @JavascriptInterface
        fun captureMessageImage() {
            isMessageCapture = true
            runOnUiThread {
                try {
                    val imagesDir = File(cacheDir, "exergy_images").apply { mkdirs() }
                    val imageFile = File.createTempFile("exergy_msg_", ".jpg", imagesDir)
                    tempImageUri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", imageFile)
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, tempImageUri)
                    // FileProvider URIs require explicit write permission granted to the
                    // camera app — without this flag Android blocks the write and the
                    // camera process crashes, surfacing as "LNES-06 keeps stopping".
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    startActivityForResult(intent, CAMERA_REQUEST_CODE)
                } catch (e: Exception) {
                    sendToLog(">>> [DLTN] Message camera failed: ${e.message}")
                    isMessageCapture = false
                }
            }
        }

        // Pick an existing image from the gallery for a message.
        @JavascriptInterface
        fun pickMessageImageFromGallery() {
            isMessageCapture = true
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(Intent.createChooser(intent, "Choose image"), GALLERY_REQUEST_CODE)
                } catch (e: Exception) {
                    sendToLog(">>> [DLTN] Gallery picker failed: ${e.message}")
                    isMessageCapture = false
                }
            }
        }

        // Reply to a specific message (threads via replyToId).
        @JavascriptInterface
        fun replyDLTNMessage(toNodeId: String, text: String, replyToId: String) {
            val svc = getDLTNService() ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                val msg = svc.messenger.sendText(toNodeId, text, replyToId.ifEmpty { null })
                callJs("onDLTNMessageQueued", msg.id, msg.toNodeId)
            }
        }

        // Send an image (base64 JPEG from the UI) to a mesh node. Large images
        // realistically transfer only over the WiFi-Aware bridge, not BLE MTU.
        @JavascriptInterface
        fun sendDLTNImage(toNodeId: String, base64Jpeg: String) {
            val svc = getDLTNService() ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jpeg = Base64.decode(base64Jpeg, Base64.NO_WRAP)
                    val msg = svc.messenger.sendImage(toNodeId, jpeg, registerOnL0 = true)
                    callJs("onDLTNMessageQueued", msg.id, msg.toNodeId)
                } catch (e: Exception) {
                    sendToLog(">>> [DLTN] Image send failed: ${e.message}")
                }
            }
        }

        // Forward an existing message (text or image) to another node.
        @JavascriptInterface
        fun forwardDLTNMessage(messageId: String, toNodeId: String) {
            val svc = getDLTNService() ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                val msg = svc.messenger.forwardMessage(messageId, toNodeId)
                if (msg != null) callJs("onDLTNMessageQueued", msg.id, msg.toNodeId)
                else sendToLog(">>> [DLTN] Forward failed: message $messageId not found")
            }
        }

        // Mark a conversation read (clears unread badge) + report unread total.
        @JavascriptInterface
        fun markConversationRead(nodeId: String) {
            val svc = getDLTNService() ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                svc.messenger.markConversationRead(nodeId)
                callJs("onDLTNUnreadCount", svc.messenger.getUnreadCount())
            }
        }

        @JavascriptInterface
        fun getDLTNUnreadCount() {
            val svc = getDLTNService() ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                callJs("onDLTNUnreadCount", svc.messenger.getUnreadCount())
            }
        }

        @JavascriptInterface
        fun getMeshDiagnostics() {
            val svc = getDLTNService()
            val json = svc?.meshDiagnostics() ?: "{\"running\":false,\"error\":\"service not running\"}"
            // Also report whether the app holds the BLE runtime permissions, since
            // that is the most common reason the mesh is dark.
            val scan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED else true
            val conn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED else true
            val adv = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED else true
            val merged = try {
                JSONObject(json).apply {
                    put("permScan", scan); put("permConnect", conn); put("permAdvertise", adv)
                }.toString()
            } catch (_: Exception) { json }
            callJs("onMeshDiagnostics", merged)
        }

        // Re-request BLE permissions on demand (from the diagnostics panel).
        @JavascriptInterface
        fun requestMeshPermissions() {
            runOnUiThread {
                val list = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    list.add(Manifest.permission.BLUETOOTH_SCAN)
                    list.add(Manifest.permission.BLUETOOTH_CONNECT)
                    list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
                if (list.isNotEmpty()) requestPermissions.launch(list.toTypedArray())
            }
        }

        @JavascriptInterface
        fun getDLTNContacts() {
            val svc = getDLTNService() ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                // The Messages CHAT list is a CONVERSATION list, not just a list of
                // BLE-discovered peers. Merge three sources so a peer you have ever
                // messaged or saved always appears:
                //   1. dltn_contacts  — auto-discovered (BLE) + peers who messaged us
                //   2. node_book      — manually saved contacts (Node Book tab)
                //   3. message threads — anyone with messages in either direction
                val db       = ExergyDatabase.getDatabase(this@MainActivity)
                val contacts = svc.messenger.getAllContacts()
                val nodeBook = db.nodeBookDao().getAll()
                val threads  = db.dltnMessageDao().getAllContactNodeIds()
                val localId  = svc.messenger.localNodeId()

                val aliasOf   = HashMap<String, String>()
                val lastSeenOf = HashMap<String, Long>()
                val rssiOf    = HashMap<String, Int>()
                val trustedOf = HashMap<String, Boolean>()
                val order     = LinkedHashSet<String>()

                for (c in contacts) {
                    aliasOf[c.nodeId]    = c.displayName
                    lastSeenOf[c.nodeId] = c.lastSeenMs
                    rssiOf[c.nodeId]     = c.rssiLast
                    trustedOf[c.nodeId]  = c.trusted
                    order.add(c.nodeId)
                }
                for (n in nodeBook) {
                    aliasOf[n.nodeId]    = n.alias   // a saved alias always wins
                    lastSeenOf[n.nodeId] = maxOf(lastSeenOf[n.nodeId] ?: 0L, n.lastSeen)
                    trustedOf[n.nodeId]  = true
                    order.add(n.nodeId)
                }
                for (id in threads) {
                    if (id.isBlank() || id == localId) continue
                    order.add(id)   // ensure messaged peers show even if not saved/discovered
                }

                val arr = JSONArray()
                for (id in order) {
                    arr.put(JSONObject().apply {
                        put("nodeId",      id)
                        put("displayName", aliasOf[id] ?: "Node ${id.take(8)}")
                        put("lastSeenMs",  lastSeenOf[id] ?: 0L)
                        put("rssiLast",    rssiOf[id] ?: -100)
                        put("trusted",     trustedOf[id] ?: false)
                    })
                }
                withContext(Dispatchers.Main) { callJs("onDLTNContactsLoaded", arr.toString()) }
            }
        }

        @JavascriptInterface
        fun getDLTNConversation(nodeId: String) {
            val svc = getDLTNService() ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                val CALL_SIG_TYPES = setOf("call_invite","call_accept","call_reject","call_end")
                val msgs = svc.messenger.getConversation(nodeId)
                val arr  = JSONArray()
                for (m in msgs) {
                    // Call-signalling messages are transport control — never show in conversation UI.
                    if (m.type in CALL_SIG_TYPES) continue
                    // Image messages store "dltn_img:<id>" as content to avoid
                    // SQLiteBlobTooBigException. Resolve to base64 here so JS
                    // sees the same format it always expected.
                    val resolvedContent = if (m.content.startsWith("dltn_img:")) {
                        val msgId  = m.content.removePrefix("dltn_img:")
                        val imgFile = File(filesDir, "dltn_images/$msgId.jpg")
                        if (imgFile.exists()) {
                            android.util.Base64.encodeToString(imgFile.readBytes(), android.util.Base64.NO_WRAP)
                        } else m.content
                    } else m.content
                    arr.put(JSONObject().apply {
                        put("id",          m.id)
                        put("fromNodeId",  m.fromNodeId)
                        put("toNodeId",    m.toNodeId)
                        put("type",        m.type)
                        put("content",     resolvedContent)
                        put("timestampMs", m.timestampMs)
                        put("direction",   m.direction)
                        put("delivered",   m.delivered)
                        put("read",        m.read)
                        put("imageHash",   m.imageHash ?: "")
                        put("replyToId",   m.replyToId ?: "")
                    })
                }
                withContext(Dispatchers.Main) { callJs("onDLTNConversationLoaded", arr.toString()) }
            }
        }

        @JavascriptInterface
        fun addDLTNContact(nodeId: String, displayName: String, walletAddress: String) {
            val svc = getDLTNService() ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                svc.messenger.addManualContact(
                    nodeId        = nodeId,
                    displayName   = displayName,
                    walletAddress = walletAddress.ifEmpty { null },
                )
                callJs("onDLTNContactAdded", nodeId)
            }
        }

        // ── DLTN Call Bridge ───────────────────────────────────────────────

        // SOVEREIGN ROUTING: place a voice call to a peer using its miner_id / node-id
        // as the absolute routing coordinate. Routes through the DLTN mesh
        // (call-invite signaling + Phase C WiFi-Aware/BLE voice socket) and NEVER
        // touches the Android cellular dialer. There is no tel:/PSTN path.
        @JavascriptInterface
        fun initiateDltnCall(peerId: String) {
            val nodeId = peerId.trim()
            if (nodeId.isEmpty()) {
                sendToLog(">>> [CALL] Empty node id — call aborted")
                return
            }
            val svc = getDLTNService() ?: run {
                sendToLog(">>> [CALL] DLTN mesh service not available")
                return
            }
            sendToLog("[CALL] Opening sovereign DLTN call to ${nodeId.take(10)}… (Wi-Fi Aware / BLE, no cellular)")
            // BUG 3 FIX: do NOT send the invite here. startOutgoingCall() emits the
            // call_invite itself via sendSignal, and only when callState == IDLE.
            // The previous explicit messenger.sendCallInvite() was ungated, so every
            // repeated tap queued another invite bubble (call-signal flood). Routing
            // solely through startOutgoingCall() makes the IDLE guard authoritative.
            svc.callEngine.startOutgoingCall(nodeId, withVideo = false)
        }

        // Start a call with the camera ON from the first frame (separate Video button).
        @JavascriptInterface
        fun initiateDltnVideoCall(peerId: String) {
            val nodeId = peerId.trim()
            if (nodeId.isEmpty()) return
            val svc = getDLTNService() ?: return
            sendToLog("[CALL] Opening sovereign DLTN VIDEO call to ${nodeId.take(10)}…")
            svc.callEngine.startOutgoingCall(nodeId, withVideo = true)
        }

        // Back-compat alias for existing UI callers (map popup, conversation view).
        @JavascriptInterface
        fun initiateCall(nodeId: String) = initiateDltnCall(nodeId)

        @JavascriptInterface
        fun acceptCall() {
            val svc = getDLTNService() ?: return
            val peer = svc.callEngine.getRemotePeer()
            lifecycleScope.launch(Dispatchers.IO) { svc.messenger.sendCallAccept(peer) }
            svc.callEngine.acceptIncomingCall(peer)
        }

        @JavascriptInterface
        fun rejectCall() {
            val svc = getDLTNService() ?: return
            val peer = svc.callEngine.getRemotePeer()
            lifecycleScope.launch(Dispatchers.IO) { svc.messenger.sendCallReject(peer) }
            svc.callEngine.rejectCall()
        }

        @JavascriptInterface
        fun endCall() {
            val svc = getDLTNService() ?: return
            val peer = svc.callEngine.getRemotePeer()
            lifecycleScope.launch(Dispatchers.IO) { svc.messenger.sendCallEnd(peer) }
            svc.callEngine.endCall()
        }

        // ── AREM / ASSA Status Bridge ──────────────────────────────────────

        @JavascriptInterface
        fun getAREMStatus() {
            val svc = getDLTNService() ?: return
            val status = svc.arem.getStatus()
            val obj = JSONObject(status.mapValues { it.value.toString() })
            callJs("onAREMStatusReceived", obj.toString())
        }

        @JavascriptInterface
        fun getASSAStatus() {
            val svc = getDLTNService() ?: return
            val obj = JSONObject().apply {
                put("hasGnssLock",      svc.assa.hasGnssLock())
                put("spatialConfidence", svc.assa.spatialConfidence())
            }
            callJs("onASSAStatusReceived", obj.toString())
        }

        // ── ExergyNet Node Book — sovereign cryptographic address book ─────
        // Replaces the OS contacts provider entirely. Stores node_id → alias in
        // Room. No phone numbers, no READ_CONTACTS, no telecom.

        @JavascriptInterface
        fun saveToNodeBook(nodeId: String, alias: String) {
            val id = nodeId.trim()
            if (id.isEmpty()) { sendToLog(">>> [NODEBOOK] Empty node id — not saved"); return }
            lifecycleScope.launch(Dispatchers.IO) {
                ExergyDatabase.getDatabase(this@MainActivity).nodeBookDao().upsert(
                    NodeBookEntity(
                        nodeId = id,
                        alias = alias.ifBlank { "Node ${id.take(8)}" },
                        lastSeen = System.currentTimeMillis()
                    )
                )
                withContext(Dispatchers.Main) { callJs("onNodeBookSaved", id) }
            }
        }

        @JavascriptInterface
        fun getNodeBook() {
            lifecycleScope.launch(Dispatchers.IO) {
                val nodes = ExergyDatabase.getDatabase(this@MainActivity).nodeBookDao().getAll()
                val arr = JSONArray()
                for (n in nodes) {
                    arr.put(JSONObject().apply {
                        put("nodeId",   n.nodeId)
                        put("alias",    n.alias)
                        put("lastSeen", n.lastSeen)
                    })
                }
                withContext(Dispatchers.Main) { callJs("onNodeBookLoaded", arr.toString()) }
            }
        }

        @JavascriptInterface
        fun deleteFromNodeBook(nodeId: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                ExergyDatabase.getDatabase(this@MainActivity).nodeBookDao().delete(nodeId)
                withContext(Dispatchers.Main) { callJs("onNodeBookDeleted", nodeId) }
            }
        }

        /**
         * Called when the user corrects a wrong contact node ID.
         * Steps:
         *  1. Delete the old NodeBook entry (wrong ID)
         *  2. Upsert a new NodeBook entry with the correct ID + same alias
         *  3. Update DLTNContactEntity: remove old, upsert new
         *  4. Repoint all messages that had toNodeId=old to toNodeId=new
         *     so pending outbox messages go to the right peer and conversation
         *     history merges cleanly under the correct node ID.
         */
        @JavascriptInterface
        fun correctContactNodeId(oldNodeId: String, newNodeId: String, alias: String) {
            val old = oldNodeId.trim(); val new = newNodeId.trim()
            if (old.isEmpty() || new.isEmpty() || old == new) return
            lifecycleScope.launch(Dispatchers.IO) {
                val db    = ExergyDatabase.getDatabase(this@MainActivity)
                val svc   = getDLTNService()

                // 1. Update NodeBook
                db.nodeBookDao().delete(old)
                db.nodeBookDao().upsert(NodeBookEntity(
                    nodeId   = new,
                    alias    = alias.ifBlank { "Node ${new.take(8)}" },
                    lastSeen = System.currentTimeMillis(),
                ))

                // 2. Update DLTNContact (messenger layer)
                if (svc != null) {
                    val oldContact = svc.messenger.getAllContacts().firstOrNull { it.nodeId == old }
                    if (oldContact != null) {
                        svc.messenger.addManualContact(
                            nodeId      = new,
                            displayName = alias.ifBlank { oldContact.displayName },
                            walletAddress = null,
                        )
                    }
                    // Remove old contact entry so the wrong ID no longer appears
                    db.dltnContactDao().delete(old)
                }

                // 3. Repoint all messages from old ID to new ID
                db.dltnMessageDao().repointMessagesToNode(old, new)
                db.dltnMessageDao().repointMessagesFromNode(old, new)

                withContext(Dispatchers.Main) {
                    callJs("onContactNodeIdCorrected", old, new)
                }
            }
        }

        // ── Sovereign identity / share / QR ────────────────────────────────
        // The node's permanent cryptographic identity (scalable alphanumeric handle),
        // NOT a sequential "Witness #N". Used for routing, sharing, and QR.
        @JavascriptInterface
        fun getNodeIdentity(): String = configManager.getMinerId()

        // ── LNES-11 proprioception: GPS-free positioning via the Desktop Swarm ──
        // The phone does NOT compute its own absolute position. It ships its ASSA
        // fingerprint (+ a Sim(3) local trajectory / global anchors) to the L0
        // router, which runs the Kabsch-Procrustes alignment on the desktop swarm
        // and returns a ZK-verified [lat, lon]. We then move the map puck — no GPS.
        @JavascriptInterface
        fun requestSim3Alignment() {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val svc = getDLTNService()
                    val assa = svc?.assa
                    val minerId = configManager.getMinerId()
                    val timestamp = System.currentTimeMillis()

                    // ASSA fingerprint
                    val bssids = JSONObject()
                    assa?.buildBssidPayload()?.forEach { (k, v) -> bssids.put(k, v) }
                    val magArr = JSONArray()
                    assa?.magneticVector()?.forEach { magArr.put(it.toDouble()) }

                    // Sim(3) inputs. If ARCore/UWB are unavailable we send a minimal
                    // mock local trajectory; the swarm aligns it to known global anchors.
                    val localTrajectory = JSONArray().apply {
                        put(JSONArray(listOf(0, 0, 0)))
                        put(JSONArray(listOf(1, 0, 0)))
                    }
                    val globalAnchors = JSONArray()  // populated by the swarm from mesh peers

                    val payload = JSONObject().apply {
                        put("miner_id", minerId)
                        put("timestamp", timestamp)
                        put("fingerprint", JSONObject().apply {
                            put("bssids", bssids)
                            put("magnetic", magArr)
                            put("spatial_confidence", assa?.spatialConfidence() ?: "NONE")
                        })
                        put("local_trajectory", localTrajectory)
                        put("global_anchors", globalAnchors)
                    }

                    // StrongBox signature over the canonical payload bytes.
                    val payloadBytes = payload.toString().toByteArray()
                    val signature = try {
                        val sig = ExergyStrongBox.signThermodynamicPayload(minerId.toByteArray(), payloadBytes)
                        Base64.encodeToString(sig, Base64.NO_WRAP)
                    } catch (e: Exception) { "" }
                    payload.put("signature", signature)

                    val finalBytes = payload.toString().toByteArray()
                    val host = configManager.aggregatorIp.ifEmpty { "explorer-api.exergynet.org" }

                    // ── PRIMARY STRIKE: cloud (Apex L0 router over HTTPS) ──────────
                    try {
                        withContext(Dispatchers.Main) { sendToLog("[LNES-11] Requesting Sim(3) alignment (cloud)…") }
                        val conn = (URL("https://$host/api/v1/position/sync").openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            connectTimeout = 12000
                            readTimeout = 20000
                            doOutput = true
                        }
                        conn.outputStream.use { it.write(finalBytes) }
                        val code = conn.responseCode
                        if (code !in 200..299) throw java.io.IOException("router HTTP $code")
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val obj = JSONObject(resp)

                        val status = obj.optString("status")
                        if (status == "pending_swarm") {
                            val jobId = obj.optString("job_id_hex")
                            if (jobId.isEmpty()) { withContext(Dispatchers.Main) { callJs("onPositionSyncFailed", "no job_id_hex") }; return@launch }
                            withContext(Dispatchers.Main) { sendToLog("[LNES-11] Queued to swarm: $jobId — awaiting ZK proof…") }
                            pollSwarmPosition(host, jobId)
                            return@launch
                        }
                        val (rLat, rLon) = parseCoords(obj, "")
                        if (!rLat.isNaN() && !rLon.isNaN()) {
                            withContext(Dispatchers.Main) {
                                sendToLog("[LNES-11] ✓ Fix: $rLat, $rLon (cloud, no GPS)")
                                callJs("_streetsUpdateUserPos", rLat, rLon); callJs("onPositionSynced", rLat, rLon)
                            }
                            return@launch
                        }
                        // Cloud reachable but gave no usable answer — fall through to local mesh.
                        throw java.io.IOException("no coordinates in cloud response")
                    } catch (cloud: java.io.IOException) {
                        // ── SECONDARY STRIKE: local DLTN TCP:8003 (true off-grid) ──
                        // Triggered by UnknownHostException / SocketTimeoutException /
                        // ConnectException (all IOException) when Wi-Fi/cellular is dark.
                        withContext(Dispatchers.Main) {
                            sendToLog("[LNES-11] Cloud unreachable (${cloud.message}) — routing over local DLTN TCP:8003…")
                        }
                        val targetIp = host.substringBefore(":")
                        val secret = configManager.tcpSecret.toByteArray(Charsets.UTF_8)
                        val reply = EdgeTransmitter.requestTcp(targetIp, 8003, finalBytes, secret)
                        if (reply.isNullOrEmpty()) {
                            withContext(Dispatchers.Main) { callJs("onPositionSyncFailed", "local mesh: no reply on :8003") }
                            return@launch
                        }
                        // Desktop replies with coords as JSON or "geo:lat,lon".
                        val rObj = try { JSONObject(reply) } catch (_: Exception) { JSONObject() }
                        val (la, lo) = parseCoords(rObj, reply)
                        if (la.isNaN() || lo.isNaN()) {
                            withContext(Dispatchers.Main) { callJs("onPositionSyncFailed", "local mesh: unparseable reply") }
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            sendToLog("[LNES-11] ✓ Fix: $la, $lo (local DLTN mesh, fully off-grid)")
                            callJs("_streetsUpdateUserPos", la, lo); callJs("onPositionSynced", la, lo)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { callJs("onPositionSyncFailed", e.message ?: "network") }
                }
            }
        }

        // Poll the L0 ledger for the swarm's ZK-verified fix on this job (every 3s, ~60s cap).
        private suspend fun pollSwarmPosition(host: String, jobId: String) {
            val maxAttempts = 20
            for (attempt in 1..maxAttempts) {
                kotlinx.coroutines.delay(3000)
                try {
                    val c = (URL("https://$host/api/l0/transactions").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000; readTimeout = 12000
                    }
                    if (c.responseCode !in 200..299) continue
                    val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                    for (i in 0 until arr.length()) {
                        val tx = arr.optJSONObject(i) ?: continue
                        if (tx.optString("job_id_hex") != jobId) continue
                        if (!tx.optString("status").contains("VERIFIED")) continue
                        // resolved coords ride in payload_url (geo:LAT,LON) or a resolved_coords field
                        val (la, lo) = parseCoords(tx, tx.optString("payload_url"))
                        if (!la.isNaN() && !lo.isNaN()) {
                            withContext(Dispatchers.Main) {
                                sendToLog("[LNES-11] ✓ Swarm fix: $la, $lo (ZK-STARK VERIFIED, no GPS)")
                                callJs("_streetsUpdateUserPos", la, lo)
                                callJs("onPositionSynced", la, lo)
                            }
                            return
                        }
                    }
                } catch (_: Exception) { /* keep polling */ }
            }
            withContext(Dispatchers.Main) { callJs("onPositionSyncFailed", "swarm timeout") }
        }

        // Extract [lat,lon] from a JSON object and/or a payload_url string.
        private fun parseCoords(obj: JSONObject, payloadUrl: String): Pair<Double, Double> {
            obj.optJSONArray("resolved_coords")?.let { if (it.length() >= 2) return it.optDouble(0) to it.optDouble(1) }
            val lat = obj.optDouble("latitude", obj.optDouble("lat", Double.NaN))
            val lon = obj.optDouble("longitude", obj.optDouble("lon", Double.NaN))
            if (!lat.isNaN() && !lon.isNaN()) return lat to lon
            obj.optJSONArray("position")?.let { if (it.length() >= 2) return it.optDouble(0) to it.optDouble(1) }
            // payload_url like "geo:39.77,-86.29" or "...#geo=39.77,-86.29"
            val m = Regex("geo[:=]\\s*(-?\\d+\\.?\\d*)\\s*,\\s*(-?\\d+\\.?\\d*)").find(payloadUrl)
            if (m != null) return (m.groupValues[1].toDoubleOrNull() ?: Double.NaN) to (m.groupValues[2].toDoubleOrNull() ?: Double.NaN)
            return Double.NaN to Double.NaN
        }

        // Offline QR encode: node-id → PNG data URL (ZXing). JS sets it as <img src>.
        @JavascriptInterface
        fun nodeQrPng(text: String): String {
            return try {
                val size = 560
                val matrix = com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
                val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
                for (x in 0 until size) for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                sendToLog(">>> [QR] encode failed: ${e.message}")
                ""
            }
        }

        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(send, "Share Node ID"))
                } catch (e: Exception) {
                    sendToLog(">>> [SHARE] Failed: ${e.message}")
                }
            }
        }

        // Native ML Kit (Google Code Scanner) — full-screen QR scan, no custom camera UI.
        // The scanner is an OPTIONAL Play Services module downloaded on demand; on some
        // devices/sessions it is evicted and startScan() fails until it re-downloads.
        // We proactively request the module install, then scan; any failure falls
        // through to the manual paste dialog (onNodeQrScanError) so adding a contact
        // is never blocked by Play Services.
        @JavascriptInterface
        fun scanNodeQr() {
            runOnUiThread {
                try {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    val scanner = GmsBarcodeScanning.getClient(this@MainActivity, options)

                    val doScan = {
                        scanner.startScan()
                            .addOnSuccessListener { bc -> callJs("onNodeQrScanned", bc.rawValue ?: "") }
                            .addOnCanceledListener { sendToLog("[QR] Scan cancelled") }
                            .addOnFailureListener { e -> callJs("onNodeQrScanError", e.message ?: "scan failed") }
                    }

                    // Ensure the scanner module is present first (downloads if evicted).
                    try {
                        val moduleInstall = com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(this@MainActivity)
                        moduleInstall.areModulesAvailable(scanner)
                            .addOnSuccessListener { resp ->
                                if (resp.areModulesAvailable()) {
                                    doScan()
                                } else {
                                    val req = com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder()
                                        .addApi(scanner).build()
                                    moduleInstall.installModules(req)
                                        .addOnSuccessListener { doScan() }
                                        .addOnFailureListener { e -> callJs("onNodeQrScanError", e.message ?: "scanner module unavailable") }
                                }
                            }
                            .addOnFailureListener {
                                // Availability check itself failed — just try scanning.
                                doScan()
                            }
                    } catch (_: Exception) {
                        doScan()
                    }
                } catch (e: Exception) {
                    callJs("onNodeQrScanError", e.message ?: "scanner unavailable")
                }
            }
        }

        // Drop-Intel attachment picker. Result → JS onAttachmentPicked(json).
        @JavascriptInterface
        fun pickAttachment() {
            runOnUiThread {
                try { pickAttachmentLauncher.launch("*/*") }
                catch (e: Exception) { sendToLog(">>> [ATTACH] picker unavailable: ${e.message}") }
            }
        }

        // ── Vanguard chat (native bridge) ──────────────────────────────────
        // JS fetch() from the file:// page is blocked cross-origin (allowUniversal
        // AccessFromFileURLs=false, by design). So the chat request runs here in
        // Kotlin and streams deltas back via callJs. Keeps the WebView locked down.
        @JavascriptInterface
        fun vanguardSend(messagesJson: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val base = "https://dt.portal.exergynet.org"
                try {
                    val token = vanguardToken(base)
                    if (token.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) { callJs("onVanguardError", "auth") }
                        return@launch
                    }
                    val body = JSONObject().apply {
                        put("model", "vanguard")
                        put("messages", JSONArray(messagesJson))
                        put("stream", true)
                    }.toString()

                    val conn = (URL("$base/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", "Bearer $token")
                        connectTimeout = 15000
                        readTimeout = 90000
                        doOutput = true
                    }
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    if (conn.responseCode !in 200..299) {
                        withContext(Dispatchers.Main) { callJs("onVanguardError", "http ${conn.responseCode}") }
                        return@launch
                    }
                    conn.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line!!.trim()
                            if (!l.startsWith("data:")) continue
                            val raw = l.removePrefix("data:").trim()
                            if (raw == "[DONE]") break
                            try {
                                val obj = JSONObject(raw)
                                val t = obj.optString("type")
                                if (t == "kernel" || t == "status") continue
                                val delta = obj.optJSONArray("choices")
                                    ?.optJSONObject(0)?.optJSONObject("delta")?.optString("content") ?: ""
                                if (delta.isNotEmpty()) withContext(Dispatchers.Main) { callJs("onVanguardDelta", delta) }
                            } catch (_: Exception) { /* skip non-JSON frames */ }
                        }
                    }
                    withContext(Dispatchers.Main) { callJs("onVanguardDone") }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { callJs("onVanguardError", e.message ?: "network") }
                }
            }
        }

        private fun vanguardToken(base: String): String? {
            return try {
                val conn = (URL("$base/api/dt-token").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 12000
                    readTimeout = 12000
                    doOutput = true
                }
                conn.outputStream.use { it.write("{\"password\":\"Exergynet2026@\"}".toByteArray()) }
                if (conn.responseCode !in 200..299) return null
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(resp).optString("token").ifEmpty { null }
            } catch (e: Exception) {
                null
            }
        }

        @JavascriptInterface
        fun sealDeadDrop(
            message: String,
            type: String,
            lat: Double,
            lon: Double,
            radiusM: Int,
            ttlSecs: Int,
            groups: String,
            category: String,
            attachmentJson: String,
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val timestamp = System.currentTimeMillis()
                    // BLUE TEAM (M3): unique per-signature nonce defeats replay. The nonce
                    // is folded into the signed byte-array AND sent in the body so the
                    // Apex Router can reconstruct the exact payload to verify + dedupe.
                    val nonceHex = newNonceHex()
                    val payloadStr = "$message|$type|$lat|$lon|$radiusM|$ttlSecs|$groups|$category|$timestamp|$nonceHex"
                    val signature  = ExergyStrongBox.signThermodynamicPayload(
                        payloadStr.toByteArray(), ByteArray(0)
                    )
                    val sigB64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)
                    val ip = configManager.aggregatorIp.ifEmpty { "explorer-api.exergynet.org" }
                    val bodyJson = JSONObject().apply {
                        put("message",   message)
                        put("type",      type)
                        put("lat",       lat)
                        put("lon",       lon)
                        put("radius_m",  radiusM)
                        put("ttl_secs",  ttlSecs)
                        put("timestamp", timestamp)
                        put("nonce",     nonceHex)
                        put("signature", sigB64)
                        put("miner_id",  configManager.getMinerId())
                        put("category",  category.ifBlank { "INTEL" })
                        if (groups.isNotBlank()) put("groups", groups)
                        // Optional attachment (image inline base64; doc/video metadata only).
                        try {
                            val att = JSONObject(attachmentJson.ifBlank { "{}" })
                            val b64 = att.optString("dataB64")
                            if (b64.isNotEmpty()) {
                                put("attachment_b64",  b64)
                                put("attachment_mime", att.optString("mime", "image/jpeg"))
                                put("attachment_name", att.optString("name", "attachment"))
                            }
                        } catch (_: Exception) {}
                    }
                    val conn = URL("https://$ip/api/v1/drops/create").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 10000
                    conn.readTimeout    = 15000
                    conn.outputStream.use { it.write(bodyJson.toString().toByteArray()) }
                    val respBody = conn.inputStream.bufferedReader().readText()
                    val dropId   = try { JSONObject(respBody).optString("drop_id", "DROP-$timestamp") }
                                   catch (_: Exception) { "DROP-$timestamp" }
                    callJs("onDropSealed", dropId)
                } catch (e: Exception) {
                    sendToLog("[DROP] Seal failed: ${e.message}")
                    callJs("onDropSealFailed", e.message ?: "unknown")
                }
            }
        }

        @JavascriptInterface
        fun interrogateDrop(dropId: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val ip   = configManager.aggregatorIp.ifEmpty { "explorer-api.exergynet.org" }
                    val encodedId = java.net.URLEncoder.encode(dropId, "UTF-8")
                    val conn = URL("https://$ip/api/v1/drops/read?drop_id=$encodedId&lat=$currentLat&lon=$currentLon")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout    = 15000
                    val code     = conn.responseCode
                    val body     = if (code == 200) conn.inputStream.bufferedReader().readText()
                                   else conn.errorStream?.bufferedReader()?.readText() ?: "{}"
                    val contents = if (code == 200) {
                        try { JSONObject(body).optString("message", "No content") }
                        catch (_: Exception) { "No content" }
                    } else {
                        try { "[Proximity required: ${JSONObject(body).optString("error","move closer")}]" }
                        catch (_: Exception) { "[Move within drop radius to read]" }
                    }
                    val attB64  = if (code == 200) try { JSONObject(body).optString("attachment_b64", "") } catch (_: Exception) { "" } else ""
                    val attMime = if (code == 200) try { JSONObject(body).optString("attachment_mime", "") } catch (_: Exception) { "" } else ""
                    withContext(Dispatchers.Main) { callJs("onDropContentsReceived", dropId, contents, attB64, attMime) }
                } catch (e: Exception) {
                    sendToLog("[DROP] Interrogate failed: ${e.message}")
                }
            }
        }

        // ── LNES-10 Drop Moderation Bridge ────────────────────────────────

        @JavascriptInterface
        fun flagDrop(dropId: String, reason: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val ip = configManager.aggregatorIp.ifEmpty { "explorer-api.exergynet.org" }
                    val timestamp = System.currentTimeMillis()
                    // BLUE TEAM (M3): per-signature nonce defeats replay of flag reports.
                    val nonceHex = newNonceHex()
                    val payloadStr = "$dropId|$reason|$timestamp|$nonceHex"
                    val signature = ExergyStrongBox.signThermodynamicPayload(
                        payloadStr.toByteArray(), ByteArray(0)
                    )
                    val sigB64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)
                    val bodyJson = JSONObject().apply {
                        put("drop_id",       dropId)
                        put("reason",        reason)
                        put("nonce",         nonceHex)
                        put("reporter_node_id", configManager.getMinerId())
                        put("signature",     sigB64)
                        put("timestamp",     timestamp)
                    }
                    val conn = URL("https://$ip/api/v1/drops/flag").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.outputStream.use { it.write(bodyJson.toString().toByteArray()) }
                    val code = conn.responseCode
                    sendToLog("[DROP] Flag submitted for $dropId. HTTP $code")
                } catch (e: Exception) {
                    sendToLog("[DROP] Flag failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun postDropReply(dropId: String, message: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val ip = configManager.aggregatorIp.ifEmpty { "explorer-api.exergynet.org" }
                    val timestamp = System.currentTimeMillis()
                    val payloadStr = "$dropId|$message|$timestamp"
                    val signature = ExergyStrongBox.signThermodynamicPayload(
                        payloadStr.toByteArray(), ByteArray(0)
                    )
                    val sigB64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)
                    val bodyJson = JSONObject().apply {
                        put("drop_id",   dropId)
                        put("message",   message)
                        put("node_id",   configManager.getMinerId())
                        put("signature", sigB64)
                        put("timestamp", timestamp)
                    }
                    val conn = URL("https://$ip/api/v1/drops/reply").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.outputStream.use { it.write(bodyJson.toString().toByteArray()) }
                    val code = conn.responseCode
                    sendToLog("[DROP] Reply posted to $dropId. HTTP $code")
                } catch (e: Exception) {
                    sendToLog("[DROP] Reply failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun loadDropReplies(dropId: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val ip = configManager.aggregatorIp.ifEmpty { "explorer-api.exergynet.org" }
                    val conn = URL("https://$ip/api/v1/drops/replies?drop_id=$dropId").openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val body = conn.inputStream.bufferedReader().readText()
                    withContext(Dispatchers.Main) { callJs("onDropRepliesLoaded", dropId, body) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { callJs("onDropRepliesLoaded", dropId, "[]") }
                }
            }
        }

        // ── End LNES-10 Drop Moderation Bridge ────────────────────────────

        private fun getDLTNService(): DLTNForegroundService? = DLTNForegroundService.instance

        // ── End DLTN Bridge ────────────────────────────────────────────────

        // BLUE TEAM (M3): cryptographically-random 8-byte nonce (hex) for anti-replay
        // salting of StrongBox-signed payloads. SecureRandom here is correct usage —
        // it generates real entropy, unlike the deleted H3 "ghost proof".
        private fun newNonceHex(): String {
            val nonce = ByteArray(8)
            java.security.SecureRandom().nextBytes(nonce)
            return nonce.joinToString("") { "%02x".format(it) }
        }

        private fun hexStringToByteArray(s: String): ByteArray {
            val cleanStr = s.replace("0x", "")
            val data = ByteArray(cleanStr.length / 2)
            for (i in cleanStr.indices step 2) {
                data[i / 2] = ((Character.digit(cleanStr[i], 16) shl 4) + Character.digit(cleanStr[i + 1], 16)).toByte()
            }
            return data
        }
    }

    // Reads a picked attachment. Images → downscaled JPEG base64 (inline-safe).
    // Other media (video/document) → metadata only (no inline bytes; flagged).
    private fun processAttachment(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val name = queryDisplayName(uri)
                val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                val obj = JSONObject().apply {
                    put("name", name); put("mime", mime); put("size", raw.size)
                }
                if (mime.startsWith("image/") && raw.isNotEmpty()) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    if (bmp != null) {
                        val maxDim = 1280
                        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height))
                        val scaled = if (scale < 1f)
                            android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width*scale).toInt(), (bmp.height*scale).toInt(), true)
                        else bmp
                        var quality = 75
                        var bytes: ByteArray
                        do {
                            val baos = java.io.ByteArrayOutputStream()
                            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, baos)
                            bytes = baos.toByteArray()
                            quality -= 15
                        } while (bytes.size > 300_000 && quality >= 30)
                        obj.put("mime", "image/jpeg")
                        obj.put("dataB64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        obj.put("size", bytes.size)
                    }
                } else {
                    // video / document: too large to inline as JSON. Carry metadata only.
                    obj.put("dataB64", "")
                    obj.put("note", "media_pending_mesh_transport")
                }
                withContext(Dispatchers.Main) { callJs("onAttachmentPicked", obj.toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callJs("onAttachmentPicked", JSONObject().put("error", e.message ?: "read failed").toString()) }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) ?: "attachment" else "attachment"
            } ?: "attachment"
        } catch (_: Exception) { "attachment" }
    }

    private fun fetchBaseUsdcBalance(address: String): Double {
        return try {
            val rpcUrl = "https://mainnet.base.org"
            val cleanAddr = address.lowercase().replace("0x", "")
            val dataSelector = "0x70a08231000000000000000000000000$cleanAddr"
            val usdcContract = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"

            val jsonBody = """
                {"jsonrpc":"2.0","id":1,"method":"eth_call","params":[{"to":"$usdcContract","data":"$dataSelector"},"latest"]}
            """.trimIndent()

            val url = java.net.URL(rpcUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000 // 5 second timeout
            conn.readTimeout = 8000    // 8 second timeout
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            conn.outputStream.use { it.write(jsonBody.toByteArray()) }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val hexResult = org.json.JSONObject(response).getString("result").replace("0x", "")
            if (hexResult.isEmpty()) return 0.0

            val rawBalance = hexResult.toBigInteger(16)
            
            // KINEMATIC FIX: Safe BigInteger Division
            val divisor = java.math.BigInteger.valueOf(1_000_000)
            val wholePart = rawBalance.divide(divisor)
            val remainder = rawBalance.remainder(divisor)
            
            wholePart.toDouble() + remainder.toDouble() / 1_000_000.0
        } catch (e: Exception) {
            -1.0
        }
    }
}