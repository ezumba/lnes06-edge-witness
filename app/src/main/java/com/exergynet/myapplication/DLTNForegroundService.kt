package com.exergynet.myapplication

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
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
        startForeground(
            DLTNConstants.DLTN_FOREGROUND_NOTIFICATION_ID,
            buildNotification("Mesh relay active — scanning for peers")
        )

        val minerId = ConfigManager(this).getMinerId()

        assa = ASSAScavenger(this)
        arem = AREMSynchronizer(this, assa)

        messenger = DLTNMessenger(
            context             = this,
            onMessageReceived   = { msg -> notifyMessageToUI(msg) },
            onCallSignalReceived = { type, from -> handleCallSignal(type, from) },
        )

        callEngine = DLTNCallEngine(
            context            = this,
            onCallStateChanged = { state, peer -> notifyCallStateToUI(state, peer) },
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

    private fun handleCallSignal(type: String, fromNodeId: String) {
        Log.i(TAG, "[CALL] Signal $type from ${fromNodeId.take(8)}")
        when (type) {
            DLTNConstants.MSG_TYPE_CALL_INVITE -> {
                callEngine.setRinging(fromNodeId)
                showIncomingCallNotification(fromNodeId)
                notifyCallStateToUI(DLTNCallEngine.CallState.RINGING, fromNodeId)
            }
            DLTNConstants.MSG_TYPE_CALL_ACCEPT -> {
                if (callEngine.getCurrentState() == DLTNCallEngine.CallState.CALLING) {
                    callEngine.acceptIncomingCall(fromNodeId)
                }
            }
            DLTNConstants.MSG_TYPE_CALL_REJECT -> callEngine.endCall()
            DLTNConstants.MSG_TYPE_CALL_END    -> callEngine.endCall()
        }
    }

    private fun notifyCallStateToUI(state: DLTNCallEngine.CallState, peer: String) {
        val intent = Intent("com.exergynet.DLTN_CALL_STATE").apply {
            putExtra("state", state.name)
            putExtra("peer",  peer)
            setPackage(packageName)
        }
        sendBroadcast(intent)
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
