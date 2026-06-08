package com.exergynet.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class ComputeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val NOTIFICATION_ID = 4472
        const val CHANNEL_ID = "exergynet_compute"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val jobIdHex = inputData.getString("JOB_ID_HEX") ?: return@withContext Result.failure()
        val dataPayload = inputData.getString("DATA_PAYLOAD") ?: ""

        // 1. Elevate to Foreground Service to prevent OS kill
        setForeground(createForegroundInfo())

        val db = ExergyDatabase.getDatabase(applicationContext)
        val dao = db.jobStateDao()

        try {
            // 2. Heavy Math Loop (Simulated ML Inference / RISC-V Verification)
            val digest = MessageDigest.getInstance("SHA-256")

            // KINEMATIC RECOVERY: Load from checkpoint if process died
            val savedCheckpoint = dao.getComputeCheckpoint(jobIdHex)
            var currentHash = if (!savedCheckpoint.isNullOrEmpty()) {
                hexStringToByteArray(savedCheckpoint)
            } else {
                dataPayload.toByteArray()
            }
            
            // We simulate a 100-step process for progress tracking
            for (progress in 1..100) {
                // Intense CPU Burn: 200,000 hashes per 1% increment
                repeat(200_000) {
                    currentHash = digest.digest(currentHash)
                }

                // SAVE CHECKPOINT EVERY 10%
                if (progress % 10 == 0) {
                    val checkpointHex = currentHash.joinToString("") { "%02X".format(it) }
                    dao.saveComputeCheckpoint(jobIdHex, checkpointHex)
                }

                // Heartbeat: Update Database and progress UI via bridge
                dao.updateJobStatus(jobIdHex, JobStatus.PROVING) // Use PROVING as 'Computing' state
                
                // PUSH PROGRESS TO UI (via SharedPreferences or similar for worker -> main sync)
                // For this mock, we use a simple delay to simulate time-series friction
                delay(100) 

                // Note: In production, we'd use a dedicated progress listener or broadcast
                println(">>> [COMPUTE] Job $jobIdHex progress: $progress%")
            }

            val finalResult = currentHash.joinToString("") { "%02X".format(it) }
            
            // 3. Finalize and Radiate
            dao.updateJobStatus(jobIdHex, JobStatus.QUEUED_LOCAL)
            
            // Trigger RadiationWorker for the computed result
            // (Handled by the Orchestrator/Main lifecycle in this demo flow)
            
            Result.success()
        } catch (e: Exception) {
            println(">>> [FATAL] Compute Worker Crash: ${e.message}")
            Result.failure()
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val cleanStr = s.replace("0x", "")
        val data = ByteArray(cleanStr.length / 2)
        for (i in cleanStr.indices step 2) {
            data[i / 2] = ((Character.digit(cleanStr[i], 16) shl 4) + Character.digit(cleanStr[i + 1], 16)).toByte()
        }
        return data
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Compute Service", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("ExergyNet: Night Sump Compute Active")
            .setContentText("Executing heavy thermodynamic proof...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}