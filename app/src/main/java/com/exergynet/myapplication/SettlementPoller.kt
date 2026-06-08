package com.exergynet.myapplication

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class SettlementPoller(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = ExergyDatabase.getDatabase(applicationContext)
        
        // 1. Get jobs that the phone thinks are processing
        val pendingJobs = db.jobStateDao().getAllJobs().filter {
            it.status == JobStatus.AGGREGATING || it.status == JobStatus.TRANSMITTING || it.status == JobStatus.QUEUED_LOCAL
        }

        if (pendingJobs.isEmpty()) {
            return@withContext Result.success()
        }

        try {
            // 2. THE OMNISCIENT EYE: Query the live AWS L0 Database
            val config = ConfigManager(applicationContext)

            // In testnet mode, auto-settle any AGGREGATING job — the L0 ledger is simulated.
            if (config.networkMode == "testnet") {
                for (job in pendingJobs) {
                    if (job.status == JobStatus.AGGREGATING) {
                        db.jobStateDao().updateJobStatus(job.jobIdHex, JobStatus.SETTLED)
                        val displayAmount = String.format(java.util.Locale.US, "%.2f", job.rewardMicroUsdc / 1_000_000.0)
                        NotificationHelper.showSettlementNotification(applicationContext, displayAmount)
                    }
                }
                return@withContext Result.success()
            }

            val aggregatorHost = config.aggregatorIp.ifEmpty { "explorer-api.exergynet.org" }
            
            // KINEMATIC ROUTING: Use HTTP for IP addresses, HTTPS for domains
            val protocol = if (aggregatorHost.substringBefore(":").all { it.isDigit() || it == '.' }) "http" else "https"
            val url = URL("$protocol://$aggregatorHost/api/l0/transactions")
            
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                if (response.trim().startsWith("[")) {
                    val l0Ledger = JSONArray(response)

                    // 3. Map the ledger into a list of mathematically verified Job IDs
                    val verifiedJobIds = mutableSetOf<String>()
                    for (i in 0 until l0Ledger.length()) {
                        val tx = l0Ledger.getJSONObject(i)
                        verifiedJobIds.add(tx.getString("job_id_hex"))
                    }

                    // 4. Compare local pending jobs against the global L0 truth
                    for (job in pendingJobs) {
                        if (verifiedJobIds.contains(job.jobIdHex)) {
                            // OMEGA VICTORY: The Cloud confirms the ZK-Proof exists!
                            db.jobStateDao().updateJobStatus(job.jobIdHex, JobStatus.SETTLED)
                            
                            // Fire the biological notification
                            val displayAmount = String.format(java.util.Locale.US, "%.2f", job.rewardMicroUsdc / 1_000_000.0)
                            NotificationHelper.showSettlementNotification(applicationContext, displayAmount)
                        }
                    }
                }
            } else {
                println(">>> [POLLER] AWS L0 API returned HTTP ${conn.responseCode}")
            }
            
            Result.success()
        } catch (e: Exception) {
            println(">>> [FATAL] Poller Network Exception: ${e.message}")
            Result.retry() // Try again on next WorkManager cycle
        }
    }
}
