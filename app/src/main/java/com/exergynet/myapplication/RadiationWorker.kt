package com.exergynet.myapplication

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class RadiationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobIdHex = inputData.getString("JOB_ID_HEX")
            ?: return@withContext Result.failure()

        val db = ExergyDatabase.getDatabase(applicationContext)
        val dao = db.jobStateDao()
        val config = ConfigManager(applicationContext)

        val job = dao.getJob(jobIdHex) ?: return@withContext Result.failure()

        dao.updateJobStatus(jobIdHex, JobStatus.TRANSMITTING)

        // Read signed payload from disk
        val payloadFile = File(applicationContext.cacheDir, "payload_$jobIdHex.bin")
        if (!payloadFile.exists()) {
            dao.updateJobStatus(jobIdHex, JobStatus.FAILED_NETWORK)
            return@withContext Result.failure()
        }
        val sensorData = payloadFile.readBytes()

        val minerAddress = config.evmPayoutAddress.ifEmpty { "0x0000000000000000000000000000000000000000" }
        val manualIp = config.aggregatorIp

        val ok = EdgeTransmitter.radiatePayload(
            manualIp = manualIp,
            jobIdHex = jobIdHex,
            minerAddress = minerAddress,
            rewardMicroUsdc = job.rewardMicroUsdc,
            vector = job.jobType,
            sensorData = sensorData,
            tcpSecret = config.tcpSecret.toByteArray(Charsets.UTF_8)
        )

        if (ok) {
            dao.updateJobStatus(jobIdHex, JobStatus.AGGREGATING)
            // Relay POST to Apex for relay/payload path (belt-and-suspenders for relay mode)
            tryRelayPost(jobIdHex, job.rewardMicroUsdc, job.jobType, sensorData)
            payloadFile.delete()
            Result.success()
        } else {
            dao.updateJobStatus(jobIdHex, JobStatus.FAILED_NETWORK)
            Result.retry()
        }
    }

    private fun tryRelayPost(
        jobIdHex: String,
        rewardMicroUsdc: Long,
        vector: String,
        sensorData: ByteArray
    ) {
        try {
            val b64 = android.util.Base64.encodeToString(sensorData, android.util.Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("job_id", jobIdHex)
                put("vector", vector)
                put("reward_micro_usdc", rewardMicroUsdc)
                put("data_b64", b64)
                put("source", "android_radiation_worker")
            }.toString()

            val conn = URL("https://explorer-api.exergynet.org/api/relay/payload")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            println(">>> [RADIATION] relay/payload response: ${conn.responseCode}")
        } catch (e: Exception) {
            println(">>> [RADIATION] relay/payload skipped: ${e.message}")
        }
    }
}
