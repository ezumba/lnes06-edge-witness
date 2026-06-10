package com.exergynet.myapplication

import androidx.room.*

@Dao
interface GhostDropDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(drop: GhostDropEntity)

    /** All drops not yet confirmed by the L0 Router — oldest first for FIFO upload.
     *  Includes RELAYED drops so any node with connectivity drains the mesh queue. */
    @Query("SELECT * FROM ghost_drops WHERE syncStatus IN ('PENDING', 'RELAYED') ORDER BY createdMs ASC")
    suspend fun getPending(): List<GhostDropEntity>

    /** Recent drop history for the Streets/Drops tab (newest first, capped at 100). */
    @Query("SELECT * FROM ghost_drops ORDER BY createdMs DESC LIMIT 100")
    suspend fun getRecent(): List<GhostDropEntity>

    @Query("UPDATE ghost_drops SET syncStatus = 'SYNCED', dropId = :dropId WHERE localId = :localId")
    suspend fun markSynced(localId: String, dropId: String)

    @Query("UPDATE ghost_drops SET l2TxHash = :txHash WHERE localId = :localId")
    suspend fun setL2TxHash(localId: String, txHash: String)

    @Query("UPDATE ghost_drops SET syncStatus = 'RELAYED' WHERE localId = :localId")
    suspend fun markRelayed(localId: String)

    /** Increment attempt counter so the worker can abandon drops after MAX_ATTEMPTS. */
    @Query("UPDATE ghost_drops SET syncAttempts = syncAttempts + 1 WHERE localId = :localId")
    suspend fun incrementAttempts(localId: String)

    @Query("SELECT COUNT(*) FROM ghost_drops WHERE syncStatus = 'PENDING'")
    suspend fun pendingCount(): Int
}
