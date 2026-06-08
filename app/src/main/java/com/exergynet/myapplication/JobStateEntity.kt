package com.exergynet.myapplication

import androidx.room.Entity
import androidx.room.PrimaryKey

// The Absolute Thermodynamic State Machine
enum class JobStatus {
    CREATED, CLAIMED, NAVIGATING, CAPTURING, PENDING,
    QUEUED_LOCAL, TRANSMITTING, AGGREGATING,
    PROVING, ON_CHAIN, DELIVERING, SETTLED,
    FAILED_NETWORK, FAILED_PROOF, EXPIRED, INTERRUPTED
}

// The SQLite Table Schema
@Entity(tableName = "job_state_table")
data class JobStateEntity(
    @PrimaryKey val jobIdHex: String,
    val jobType: String,
    val rewardMicroUsdc: Long,
    var status: JobStatus,
    var payloadPath: String? = null, // Stores the disk path of heavy JPEGs/Data to prevent DB bloating
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)