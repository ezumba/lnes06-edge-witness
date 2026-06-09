package com.exergynet.myapplication

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
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

    private  lateinit var dltnManager: DLTNManager
    internal lateinit var messenger:   DLTNMessenger
    internal lateinit var callEngine:  DLTNCallEngine
    internal lateinit var arem:        AREMSynchronizer
    internal lateinit var assa:        ASSAScavenger

    private val pendingRelayJobs = mutableListOf<DLTNRelayJob>()
    private val flushIntervalMs  = 5 * 60 * 1000L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Boot promotion is relay-only and never uses the mic. Type it explicitly
        // as CONNECTED_DEVICE: on Android 14+ the 2-arg startForeground() would
        // otherwise claim the UNION of manifest types (now incl. microphone) and
        // crash at boot whenever RECORD_AUDIO isn't yet granted.
        ServiceCompat.startForeground(
            this,
            DLTNConstants.DLTN_FOREGROUND_NOTIFICATION_ID,
            buildNotification("Mesh relay active — scanning for peers"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        val minerId = ConfigManager(this).getMinerId()

        assa = ASSAScavenger(this)
        arem = AREMSynchronizer(this, assa)

        messenger = DLTNMessenger(
            context             = this,
            onMessageReceived   = { msg -> notifyMessageToUI(msg) },
            onCallSignalReceived = { type, from, content -> handleCallSignal(type, from, content) },
        )

        callEngine = DLTNCallEngine(
            context            = this,
            onCallStateChanged = { state, peer, dropped, reason -> notifyCallStateToUI(state, peer, dropped, reason) },
            sendSignal = { to, type, payload ->
                scope.launch {
                    when (type) {
                        DLTNConstants.MSG_TYPE_CALL_INVITE -> messenger.sendCallInvite(to, payload)
                        DLTNConstants.MSG_TYPE_CALL_ACCEPT -> messenger.sendCallAccept(to)
                        DLTNConstants.MSG_TYPE_CALL_REJECT -> messenger.sendCallReject(to)
                        DLTNConstants.MSG_TYPE_CALL_END    -> messenger.sendCallEnd(to)
                    }
                }
            },
        )

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
    }

    override fun onDestroy() {
        instance = null
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

    // ── Call signal routing ───────────────────────────────────────────────────

    private fun handleCallSignal(type: String, fromNodeId: String, content: String = "") {
        Log.i(TAG, "[CALL] Signal $type from ${fromNodeId.take(8)}")
        when (type) {
            DLTNConstants.MSG_TYPE_CALL_INVITE -> {
                // content = caller's "ip:port" for direct TCP voice connection
                callEngine.setRinging(fromNodeId, callerAddress = content)
                showIncomingCallNotification(fromNodeId)
                notifyCallStateToUI(DLTNCallEngine.CallState.RINGING, fromNodeId)
            }
            DLTNConstants.MSG_TYPE_CALL_ACCEPT -> {
                // Callee accepted — our ServerSocket.accept() will unblock when they connect;
                // no additional action needed here.
                Log.i(TAG, "[CALL] Peer accepted — awaiting TCP connection")
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
            DLTNCallEngine.CallState.CONNECTED -> startCallForeground(peer)
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
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, DLTNConstants.DLTN_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming Call")
            .setContentText("From node ${fromNodeId.take(8)}")
            .setContentIntent(pi)
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
        val callChannel = NotificationChannel(
            DLTNConstants.DLTN_CALL_CHANNEL_ID,
            DLTNConstants.DLTN_CALL_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Active ExergyNet voice/data call" }
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
