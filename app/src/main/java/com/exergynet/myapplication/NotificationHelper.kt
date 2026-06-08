package com.exergynet.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_SETTLEMENT = "exergynet_settlements_v3"
    const val CHANNEL_NEW_JOB    = "exergynet_new_jobs_v3"
    const val CHANNEL_PROOF      = "exergynet_proof_v3"
    const val EXTRA_TARGET_SCREEN = "TARGET_SCREEN"
    const val EXTRA_JOB_ID        = "JOB_ID_HEX"

    // Per-vector vibration signatures — each vector has a distinct pattern
    private val VECTOR_VIBRATIONS = mapOf(
        "OPTICAL"         to longArrayOf(0, 200, 80, 200),
        "GEOSPATIAL"      to longArrayOf(0, 100, 50, 100, 50, 100),
        "AMBIENT"         to longArrayOf(0, 400, 200, 400),
        "KINEMATIC"       to longArrayOf(0, 50, 30, 50, 30, 50, 30, 50),
        "NFC_RFID"        to longArrayOf(0, 600),
        "MAGNETOMETER"    to longArrayOf(0, 150, 100, 150),
        "STORAGE_PING"    to longArrayOf(0, 80),
        "NETWORK_DENSITY" to longArrayOf(0, 100, 50, 200),
        "BIOMETRIC_GATE"  to longArrayOf(0, 300, 150, 600),
        "ASYNC_COMPUTE"   to longArrayOf(0, 1000),
        "SETTLED"         to longArrayOf(0, 200, 100, 200, 100, 500)
    )

    private fun deepLinkIntent(context: Context, screen: String, jobIdHex: String = ""): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TARGET_SCREEN, screen)
            if (jobIdHex.isNotEmpty()) putExtra(EXTRA_JOB_ID, jobIdHex)
        }
        return PendingIntent.getActivity(
            context, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(manager: NotificationManager, id: String, name: String, desc: String) {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = desc
            enableLights(true)
            enableVibration(true)
            lightColor = android.graphics.Color.parseColor("#00e887")
            setSound(soundUri, AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        }
        manager.createNotificationChannel(channel)
    }

    fun showSettlementNotification(context: Context, amount: String, jobIdHex: String = "", vector: String = "SETTLED") {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager, CHANNEL_SETTLEMENT, "Settlements", "USDC settlement confirmations")

        val vibration = VECTOR_VIBRATIONS[vector] ?: VECTOR_VIBRATIONS["SETTLED"]!!
        val intent = deepLinkIntent(context, "s-earnings", jobIdHex)

        val notification = NotificationCompat.Builder(context, CHANNEL_SETTLEMENT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⚡ ExergyNet Settlement")
            .setContentText("+\$$amount USDC settled to your wallet")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("+\$$amount USDC settled\nVector: $vector\nTap to view earnings"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(vibration)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

        manager.notify(CHANNEL_SETTLEMENT.hashCode(), notification)
    }

    fun showNewJobNotification(context: Context, title: String, reward: String, jobIdHex: String = "", vector: String = "OPTICAL") {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager, CHANNEL_NEW_JOB, "New Jobs", "New job opportunity alerts")

        val vibration = VECTOR_VIBRATIONS[vector] ?: VECTOR_VIBRATIONS["OPTICAL"]!!
        val intent = deepLinkIntent(context, "s-jobs", jobIdHex)

        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_JOB)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("🎯 New Job: $title")
            .setContentText("Bounty: \$$reward USDC · Tap to claim")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(vibration)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    fun showProofProgressNotification(context: Context, status: String, jobIdHex: String = "") {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager, CHANNEL_PROOF, "Proof Pipeline", "ZK proof progress updates")

        val intent = deepLinkIntent(context, "s-pending", jobIdHex)

        val (title, body) = when(status) {
            "TRANSMITTING" -> "📡 Transmitting proof..." to "Sending signed payload to Desktop Aggregator"
            "AGGREGATING"  -> "⚙️ ZK Proof generating..." to "Desktop Prover is sealing your proof"
            "PROVING"      -> "🔐 ZK-STARK sealing..." to "Proof condensing — nearly done"
            else           -> "🔗 Anchoring to Base L2" to "Settlement incoming"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_PROOF)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(longArrayOf(0, 100))
            .setContentIntent(intent)
            .setOngoing(status == "AGGREGATING")
            .setAutoCancel(status != "AGGREGATING")
            .build()

        manager.notify(CHANNEL_PROOF.hashCode(), notification)
    }
}
