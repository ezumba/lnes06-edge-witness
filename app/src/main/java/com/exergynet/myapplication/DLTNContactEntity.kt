package com.exergynet.myapplication

import androidx.room.*

@Entity(tableName = "dltn_contacts")
data class DLTNContactEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String,
    val walletAddress: String? = null,
    val lastSeenMs: Long = 0L,
    val discoveryType: String = "ble_auto",
    val rssiLast: Int = -100,
    val publicKeyB64: String? = null,
    val trusted: Boolean = false,
)

@Dao
interface DLTNContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: DLTNContactEntity)

    @Query("SELECT * FROM dltn_contacts ORDER BY lastSeenMs DESC")
    suspend fun getAllContacts(): List<DLTNContactEntity>

    @Query("SELECT * FROM dltn_contacts WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getContact(nodeId: String): DLTNContactEntity?

    @Query("UPDATE dltn_contacts SET lastSeenMs = :ms, rssiLast = :rssi " +
           "WHERE nodeId = :nodeId")
    suspend fun updateLastSeen(nodeId: String, ms: Long, rssi: Int)

    @Query("UPDATE dltn_contacts SET displayName = :name WHERE nodeId = :nodeId")
    suspend fun updateDisplayName(nodeId: String, name: String)

    @Query("UPDATE dltn_contacts SET trusted = 1 WHERE nodeId = :nodeId")
    suspend fun markTrusted(nodeId: String)

    @Query("DELETE FROM dltn_contacts WHERE nodeId = :nodeId")
    suspend fun delete(nodeId: String)
}
