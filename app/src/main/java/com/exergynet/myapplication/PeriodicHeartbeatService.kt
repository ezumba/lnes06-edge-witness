package com.exergynet.myapplication

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

class PeriodicHeartbeatService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d("PeriodicHeartbeat", "Heartbeat job started")
        // Work is done on the main thread, so offload to a background thread if needed.
        // For this simple case, we'll just log and finish.
        jobFinished(params, false) // false = no rescheduling
        return true // true = job is still running (e.g., on a background thread)
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d("PeriodicHeartbeat", "Heartbeat job stopped")
        return true // true = reschedule the job if it was stopped before completion
    }
}
