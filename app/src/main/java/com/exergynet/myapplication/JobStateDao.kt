package com.exergynet.myapplication

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface JobStateDao {
    // Inject a new job into the persistence layer
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: JobStateEntity)

    // Retrieve a specific job's state
    @Query("SELECT * FROM job_state_table WHERE jobIdHex = :jobId")
    suspend fun getJob(jobId: String): JobStateEntity?

    // Retrieve all active jobs that need to be resumed on app boot
    @Query("SELECT * FROM job_state_table WHERE status NOT IN ('SETTLED', 'FAILED_NETWORK', 'FAILED_PROOF', 'EXPIRED')")
    suspend fun getActiveJobs(): List<JobStateEntity>

    // KINEMATIC SHIFT: Update the state machine as the job progresses
    @Query("UPDATE job_state_table SET status = :newStatus, updatedAt = :timestamp WHERE jobIdHex = :jobId")
    suspend fun updateJobStatus(jobId: String, newStatus: JobStatus, timestamp: Long = System.currentTimeMillis())

    // Anchor heavy payload paths to the job
    @Query("UPDATE job_state_table SET payloadPath = :path WHERE jobIdHex = :jobId")
    suspend fun updatePayloadPath(jobId: String, path: String)

    @Query("SELECT * FROM job_state_table ORDER BY updatedAt DESC")
    suspend fun getAllJobs(): List<JobStateEntity>

    @Query("UPDATE job_state_table SET payloadPath = :checkpointHex WHERE jobIdHex = :jobId")
    suspend fun saveComputeCheckpoint(jobId: String, checkpointHex: String)
    // Note: Reusing payloadPath to store the hex string to avoid migrating the DB schema right now.

    @Query("SELECT payloadPath FROM job_state_table WHERE jobIdHex = :jobId")
    suspend fun getComputeCheckpoint(jobId: String): String?
}