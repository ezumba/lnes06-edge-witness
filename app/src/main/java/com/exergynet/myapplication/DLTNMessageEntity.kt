package com.exergynet.myapplication

import androidx.room.*

@Entity(tableName = "dltn_messages")
data class DLTNMessageEntity(
    @PrimaryKey val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val type: String,
    val content: String,
    val imageHash: String? = null,
    val l0TxHash: String? = null,
    val timestampMs: Long,
    val signature: String,
    val delivered: Boolean = false,
    val read: Boolean = false,
    val direction: String,
    val outboxPending: Boolean = false,
)

@Dao
interface DLTNMessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: DLTNMessageEntity)

    @Query("SELECT * FROM dltn_messages WHERE " +
           "(fromNodeId = :nodeId OR toNodeId = :nodeId) " +
           "ORDER BY timestampMs ASC")
    suspend fun getAllMessagesForNode(nodeId: String): List<DLTNMessageEntity>

    @Query("SELECT * FROM dltn_messages WHERE outboxPending = 1")
    suspend fun getPendingOutbox(): List<DLTNMessageEntity>

    @Query("SELECT * FROM dltn_messages WHERE outboxPending = 1 " +
           "AND toNodeId = :nodeId")
    suspend fun getPendingOutboxForNode(nodeId: String): List<DLTNMessageEntity>

    @Query("UPDATE dltn_messages SET delivered = 1, outboxPending = 0 " +
           "WHERE id = :messageId")
    suspend fun markDelivered(messageId: String)

    @Query("UPDATE dltn_messages SET read = 1 WHERE id = :messageId")
    suspend fun markRead(messageId: String)

    @Query("SELECT COUNT(*) FROM dltn_messages WHERE read = 0 " +
           "AND direction = 'inbound'")
    suspend fun getUnreadCount(): Int

    @Query("SELECT DISTINCT fromNodeId FROM dltn_messages " +
           "WHERE direction = 'inbound' " +
           "UNION " +
           "SELECT DISTINCT toNodeId FROM dltn_messages " +
           "WHERE direction = 'outbound'")
    suspend fun getAllContactNodeIds(): List<String>

    @Query("DELETE FROM dltn_messages WHERE timestampMs < :cutoffMs " +
           "AND delivered = 1")
    suspend fun pruneOldDelivered(cutoffMs: Long)
}
