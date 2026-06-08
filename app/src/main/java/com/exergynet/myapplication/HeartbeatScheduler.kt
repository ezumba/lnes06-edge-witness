package com.exergynet.myapplication

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object HeartbeatScheduler {

    private const val JOB_ID = 1001
    private const val FIFTEEN_MINUTES = 15 * 60 * 1000L

    fun schedule(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val componentName = ComponentName(context, PeriodicHeartbeatService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, componentName)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(FIFTEEN_MINUTES)
            .build()
        jobScheduler.schedule(jobInfo)
    }
}
