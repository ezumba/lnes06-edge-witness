package com.exergynet.myapplication

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class DLTNForegroundService : Service() {

    companion object {
        @Volatile var instance: DLTNForegroundService? = null
    }

    private val TAG   = "DLTNService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var incomingRingtone: Ringtone? = null

    private  lateinit var dltnManager: DLTNManager
    internal lateinit var messenger:   DLTNMessenger
    internal lateinit var callEngine:  DLTNCallEngine
    internal lateinit var arem:        AREMSynchronizer
    internal lateinit var assa:        ASSAScavenger
    // LNES-12 GLOBAL rail (additive fallback). Null-safe: local mesh never depends on it.
    internal var globalMesh: GlobalMeshService? = null
    // WiFi LAN rail (NSD/mDNS + TCP) — reliable primary when peers share Wi-Fi.
    internal var lanService: DLTNLanService? = null

    private val pendingRelayJobs = mutableListOf<DLTNRelayJob>()
    private val flushIntervalMs  = 5 * 60 * 1000L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // ANDROID 14 STARTUP HAZARD: a connectedDevice/microphone FGS requires its
        // prerequisite permission (a Bluetooth perm / RECORD_AUDIO) to ALREADY be
        // granted, or startForeground throws and the OS kills the service. On a
        // fresh launch BLE perms aren't granted yet → the service died at boot,
        // `instance` was null, and the mesh never came up. So we boot with the
        // dataSync type, which only needs the auto-granted FOREGROUND_SERVICE_DATA_SYNC
        // normal permission and therefore always succeeds. Once a BT permission is
        // present we promote the banner to connectedDevice (see promoteToConnectedDevice).
        val bootType = if (hasBluetoothPermission()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(
            this,
            DLTNConstants.DLTN_FOREGROUND_NOTIFICATION_ID,
            buildNotification("Mesh relay active — scanning for peers"),
            bootType,
        )

        val minerId = ConfigManager(this).getMinerId()

        assa = ASSAScavenger(this)
        arem = AREMSynchronizer(this, assa)

        messenger = DLTNMessenger(
            context             = this,
            onMessageReceived   = { msg -> notifyMessageToUI(msg) },
            onCallSignalReceived = { type, from, content -> handleCallSignal(type, from, content) },
        )

        // LNES-12: GLOBAL rail. Persistent WebSocket to the L0 Switchboard, keyed by
        // this node's id. Inbound signaling envelopes are routed straight into the
        // SAME messenger.receiveRawPayload pipeline as local mesh traffic, so the UI
        // rings identically. Audio is bridged into the existing DLTNAudioEngine.
        globalMesh = GlobalMeshService(
            myNodeId = messenger.localNodeId(),
            onSignalEnvelope = { bytes ->
                scope.launch { messenger.receiveRawPayload(DLTNConstants.CALL_RAIL_GLOBAL, bytes) }
            },
        )
        // LNES-12 Message Hub: let the messenger race text over the global rail too.
        messenger.globalSend = { to, env -> globalMesh?.sendSignalEnvelope(to, env) }

        // WiFi LAN rail. Discovers same-Wi-Fi peers via NSD and delivers the SAME
        // outbox (messages + call signals) over direct TCP. No BLE permission needed.
        lanService = DLTNLanService(
            context     = this,
            myNodeId    = messenger.localNodeId(),
            onEnvelope  = { peer, bytes -> scope.launch { messenger.receiveRawPayload(peer, bytes) } },
            pendingFor  = { nid -> messenger.getPendingOutboxEnvelopes(nid) },
            onDelivered = { id -> messenger.markDelivered(id) },
        )

        callEngine = DLTNCallEngine(
            context            = this,
            onCallStateChanged = { state, peer, dropped, reason -> notifyCallStateToUI(state, peer, dropped, reason) },
            sendSignal = { to, type, payload ->
                // RAIL 1 — local BLE/WiFi-Aware outbox (primary, untouched).
                scope.launch {
                    when (type) {
                        DLTNConstants.MSG_TYPE_CALL_INVITE -> messenger.sendCallInvite(to, payload)
                        DLTNConstants.MSG_TYPE_CALL_ACCEPT -> messenger.sendCallAccept(to)
                        DLTNConstants.MSG_TYPE_CALL_REJECT -> messenger.sendCallReject(to)
                        DLTNConstants.MSG_TYPE_CALL_END    -> messenger.sendCallEnd(to)
                    }
                }
            },
            // RAIL 2 — global WebSocket relay invite delivery (rings far peers).
            globalSignal = { to, type, payload ->
                scope.launch {
                    val env = messenger.buildSignalEnvelope(to, type, payload)
                    globalMesh?.sendSignalEnvelope(to, env)
                }
            },
            globalAvailable = { globalMesh?.isConnected() == true },
            // GLOBAL WebRTC signaling: push SDP/ICE frames to the peer over the WS.
            globalRtcSend = { peerId, rtcJson -> globalMesh?.sendRtcSignal(peerId, rtcJson) },
        )

        // Route inbound global WebRTC signaling frames into the active call.
        globalMesh?.onRtcSignal = { fromId, rtcJson -> callEngine.onGlobalRtcSignal(fromId, rtcJson) }

        dltnManager = DLTNManager(
            context   = this,
            minerId   = minerId,
            messenger = messenger,
            arem      = arem,
            assa      = assa,
            onRelayComplete = { job ->
                synchronized(pendingRelayJobs) { pendingRelayJobs.add(job) }
                Log.i(TAG, "[Relay] Job queued: ${job.jobIdHex} — ${job.rewardMicroUsdc} µUSDC")
                updateNotification("Relay received — ${pendingRelayJobs.size} pending settlement")
            }
        )

        try {
            dltnManager.start()
            scheduleRelayFlush()
            Log.i(TAG, "[DLTN] Service online — minerId=$minerId")
        } catch (e: SecurityException) {
            Log.w(TAG, "[DLTN] BLE permissions not yet granted — mesh relay deferred: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "[DLTN] Start failed — service alive, relay inactive: ${e.message}")
        }

        // LNES-12: open the GLOBAL rail. Independent of BLE permissions — it only
        // needs network, so global calling works even when the local mesh is dark.
        try { globalMesh?.connect() }
        catch (e: Exception) { Log.w(TAG, "[DLTN] Global rail connect failed: ${e.message}") }

        // WiFi LAN rail. Independent of BLE permissions — works the moment two
        // phones share a Wi-Fi network. This is the reliable primary path.
        try { lanService?.start() }
        catch (e: Exception) { Log.w(TAG, "[DLTN] LAN rail start failed: ${e.message}") }
    }

    override fun onDestroy() {
        instance = null
        try { lanService?.stop() } catch (_: Exception) {}
        try { globalMesh?.disconnect() } catch (_: Exception) {}
        try { callEngine.destroy()  } catch (_: Exception) {}
        try { messenger.destroy()   } catch (_: Exception) {}
        try { arem.destroy()        } catch (_: Exception) {}
        try { assa.destroy()        } catch (_: Exception) {}
        try { dltnManager.stop()    } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "[DLTN] Service destroyed — MLE restored")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called by MainActivity once the user grants BLUETOOTH_SCAN/CONNECT at
     * runtime. The initial start() in onCreate may have deferred (no perms yet);
     * DLTNManager.start() is idempotent and retry-safe, so this ignites the mesh
     * the moment permissions land — no app restart required.
     */
    fun ensureMeshStarted() {
        promoteToConnectedDevice()
        try { dltnManager.start() }
        catch (e: Exception) { Log.w(TAG, "[DLTN] ensureMeshStarted failed: ${e.message}") }
    }

    /** Live BLE mesh diagnostics JSON for the in-app panel. */
    fun meshDiagnostics(): String =
        try { dltnManager.diagnostics() } catch (e: Exception) { "{\"error\":\"${e.message}\"}" }

    private fun hasBluetoothPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Re-assert the relay banner as connectedDevice now that a BT perm is granted. */
    private fun promoteToConnectedDevice() {
        if (!hasBluetoothPermission()) return
        try {
            ServiceCompat.startForeground(
                this,
                DLTNConstants.DLTN_FOREGROUND_NOTIFICATION_ID,
                buildNotification("Mesh relay active — scanning for peers"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "[DLTN] connectedDevice promotion failed: ${e.message}")
        }
    }

    // ── Call signal routing ───────────────────────────────────────────────────

    private fun handleCallSignal(type: String, fromNodeId: String, content: String = "") {
        Log.i(TAG, "[CALL] Signal $type from ${fromNodeId.take(8)}")
        when (type) {
            DLTNConstants.MSG_TYPE_CALL_INVITE -> {
                // content = caller's "ip:port" (LOCAL rail) or "GLOBAL" (LNES-12 rail).
                // setRinging dedups a second invite that arrives on the other rail.
                callEngine.setRinging(fromNodeId, callerAddress = content)
                showIncomingCallNotification(fromNodeId)
                notifyCallStateToUI(DLTNCallEngine.CallState.RINGING, fromNodeId)
            }
            DLTNConstants.MSG_TYPE_CALL_ACCEPT -> {
                if (content == DLTNConstants.CALL_RAIL_GLOBAL) {
                    // GLOBAL rail accept — bind the WebSocket audio bridge on the caller.
                    callEngine.onGlobalAccept(fromNodeId)
                } else {
                    // LOCAL rail — our ServerSocket.accept() unblocks when the callee
                    // connects; nothing else to do here.
                    Log.i(TAG, "[CALL] Peer accepted (local) — awaiting TCP connection")
                }
            }
            DLTNConstants.MSG_TYPE_CALL_REJECT -> callEngine.endCall()
            DLTNConstants.MSG_TYPE_CALL_END    -> callEngine.endCall()
        }
    }

    private fun notifyCallStateToUI(
        state: DLTNCallEngine.CallState,
        peer: String,
        dropped: Boolean = false,
        reason: String = "",
    ) {
        // TASK 1: while a call is live, elevate the foreground service to an
        // IMPORTANCE_HIGH "Active Call" notification so the OS gives the process
        // top priority and won't reap its sockets; revert to the relay banner once
        // the call leaves an active state.
        when (state) {
            DLTNCallEngine.CallState.RINGING,
            DLTNCallEngine.CallState.CALLING,
            DLTNCallEngine.CallState.CONNECTED -> {
                // Stop ringing once the call is answered or outgoing.
                if (state != DLTNCallEngine.CallState.RINGING) {
                    try { incomingRingtone?.stop(); incomingRingtone = null } catch (_: Exception) {}
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
                        } else {
                            @Suppress("DEPRECATION")
                            getSystemService(Vibrator::class.java)?.cancel()
                        }
                    } catch (_: Exception) {}
                }
                startCallForeground(peer)
                // Surface the NATIVE call UI (the WebView cannot render WebRTC
                // video). Best-effort: on Android 10+ background-activity-start
                // may be blocked unless we're foreground/FGS-eligible; the
                // full-screen notification below is the fallback ring path.
                try { CallActivity.launch(this) }
                catch (e: Exception) { Log.w(TAG, "[CALL] CallActivity launch blocked: ${e.message}") }
            }
            DLTNCallEngine.CallState.ENDED,
            DLTNCallEngine.CallState.IDLE      -> endCallForeground()
        }

        val intent = Intent("com.exergynet.DLTN_CALL_STATE").apply {
            putExtra("state", state.name)
            putExtra("peer",  peer)
            // TASK 3: tell the UI this end was an OS-severed drop, not a hang-up.
            putExtra("dropped", dropped)
            putExtra("reason", reason)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ── TASK 1: Active-call foreground elevation ──────────────────────────────

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCallForeground(peer: String) {
        // Re-assert startForeground() with the high-importance call notification on
        // the SAME foreground id, so it replaces (not duplicates) the relay banner
        // and re-anchors the service at the top of the OS priority list. We do NOT
        // block/"lock" any thread — that would ANR the service; foreground
        // promotion is the real mechanism that keeps the process from being reaped.
        //
        // DYNAMIC FGS TYPE GATING: only claim the MICROPHONE type when RECORD_AUDIO
        // is actually granted. On Android 14+ promoting with a type whose
        // prerequisite permission is missing throws SecurityException /
        // MissingForegroundServiceTypeException and kills the service. If denied we
        // fall back to CONNECTED_DEVICE only — the call still rings, and the audio
        // engine fails to open the mic stream gracefully rather than crashing.
        val type = if (hasRecordAudio()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            Log.w(TAG, "[CALL] RECORD_AUDIO not granted — FGS promoted without microphone type")
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        ServiceCompat.startForeground(
            this,
            DLTNConstants.DLTN_FOREGROUND_NOTIFICATION_ID,
            buildCallNotification(peer),
            type,
        )
    }

    private fun endCallForeground() {
        // Stop ringtone and vibration.
        try { incomingRingtone?.stop(); incomingRingtone = null } catch (_: Exception) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)?.cancel()
            }
        } catch (_: Exception) {}
        // Dismiss the ringing full-screen notification if it's still up.
        try {
            getSystemService(NotificationManager::class.java)
                .cancel(DLTNConstants.CALL_NOTIFICATION_ID)
        } catch (_: Exception) {}
        // Drop back to the ongoing low-importance relay notification. Relay never
        // uses the mic, so it is always CONNECTED_DEVICE only — explicitly typed so
        // we never implicitly inherit the manifest's microphone type here.
        ServiceCompat.startForeground(
            this,
            DLTNConstants.DLTN_FOREGROUND_NOTIFICATION_ID,
            buildNotification("Mesh relay active — scanning for peers"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun buildCallNotification(peer: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, DLTNConstants.DLTN_CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Ongoing ExergyNet Call")
            .setContentText("Sovereign voice link · node ${peer.take(8)}")
            .setContentIntent(pi)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .build()
    }

    private fun notifyMessageToUI(msg: DLTNMessageEntity) {
        val intent = Intent("com.exergynet.DLTN_MESSAGE_RECEIVED").apply {
            putExtra("fromNodeId", msg.fromNodeId)
            putExtra("type",       msg.type)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun showIncomingCallNotification(fromNodeId: String) {
        // Play ringtone directly — notification-triggered sound is unreliable when
        // the app already holds audio focus or the screen is on.
        try {
            incomingRingtone?.stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            incomingRingtone = RingtoneManager.getRingtone(this, uri)?.also {
                it.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                it.isLooping = true
                it.play()
            }
        } catch (e: Exception) { Log.w(TAG, "[CALL] ringtone play failed: ${e.message}") }

        // Vibrate: 600ms on / 400ms off, repeat indefinitely.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)?.defaultVibrator
                vm?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
            } else {
                @Suppress("DEPRECATION")
                val vm = getSystemService(Vibrator::class.java)
                @Suppress("DEPRECATION")
                vm?.vibrate(longArrayOf(0, 600, 400), 0)
            }
        } catch (e: Exception) { Log.w(TAG, "[CALL] vibrate failed: ${e.message}") }

        val callIntent = Intent(this, CallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, DLTNConstants.DLTN_CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming ExergyNet Call")
            .setContentText("From node ${fromNodeId.take(8)}")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(DLTNConstants.CALL_NOTIFICATION_ID, notif)
    }

    // ── Relay Batch Flush ────────────────────────────────────────────────────

    private fun scheduleRelayFlush() {
        scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                flushRelayBatch()
            }
        }
    }

    private suspend fun flushRelayBatch() = withContext(Dispatchers.IO) {
        val batch = synchronized(pendingRelayJobs) {
            val copy = pendingRelayJobs.toList()
            pendingRelayJobs.clear()
            copy
        }
        if (batch.isEmpty()) return@withContext

        val minerId = ConfigManager(this@DLTNForegroundService).getMinerId()
        val success = DLTNRelaySettlement.postRelayBatch(minerId, batch)

        if (success) {
            Log.i(TAG, "[Relay] Batch settled — ${batch.size} jobs, " +
                    "${batch.sumOf { it.rewardMicroUsdc }} µUSDC total")
            updateNotification("Relay settled — ${batch.size} jobs")
        } else {
            synchronized(pendingRelayJobs) { pendingRelayJobs.addAll(batch) }
            Log.w(TAG, "[Relay] Batch post failed — re-queued ${batch.size} jobs")
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            DLTNConstants.DLTN_NOTIFICATION_CHANNEL_ID,
            DLTNConstants.DLTN_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "ExergyNet mesh relay activity" }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)

        // TASK 1: a separate IMPORTANCE_HIGH channel for active calls. Channel
        // importance is immutable once created, so the relay channel cannot simply
        // be "raised" — the active-call banner needs its own high channel.
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val callChannel = NotificationChannel(
            DLTNConstants.DLTN_CALL_CHANNEL_ID,
            DLTNConstants.DLTN_CALL_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming ExergyNet call ring"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600)
            setSound(
                ringtoneUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        mgr.createNotificationChannel(callChannel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, DLTNConstants.DLTN_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("ExergyNet Mesh Relay")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(DLTNConstants.DLTN_FOREGROUND_NOTIFICATION_ID, buildNotification(text))
    }
}
