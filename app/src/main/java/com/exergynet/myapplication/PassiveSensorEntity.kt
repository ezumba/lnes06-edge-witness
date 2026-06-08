package com.exergynet.myapplication

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "passive_sensor_sump")
data class PassiveSensorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vector: String,
    val rawPayload: String,
    val deviceId: String,
    val capturedAt: Long = System.currentTimeMillis(),
    val transmitted: Boolean = false
)
