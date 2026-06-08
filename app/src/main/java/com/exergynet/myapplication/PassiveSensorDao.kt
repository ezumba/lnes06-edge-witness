package com.exergynet.myapplication

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PassiveSensorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: PassiveSensorEntity): Long

    @Query("SELECT * FROM passive_sensor_sump WHERE transmitted = 0 ORDER BY capturedAt ASC")
    suspend fun getPendingReadings(): List<PassiveSensorEntity>

    @Query("SELECT COUNT(*) FROM passive_sensor_sump WHERE transmitted = 0")
    suspend fun getPendingCount(): Int

    @Query("UPDATE passive_sensor_sump SET transmitted = 1 WHERE id IN (:ids)")
    suspend fun markTransmitted(ids: List<Long>)

    @Query("DELETE FROM passive_sensor_sump WHERE transmitted = 1 AND capturedAt < :cutoff")
    suspend fun purgeOld(cutoff: Long)
}
