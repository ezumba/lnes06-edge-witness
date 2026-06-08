package com.exergynet.myapplication

import androidx.room.*

/**
 * Node Book — the sovereign replacement for the OS contacts provider.
 * Maps a cryptographic node identity (miner_id / Ed25519 pubkey / Base58) to a
 * human-readable alias. No phone numbers, no telecom, no OS contact access.
 */
@Entity(tableName = "node_book")
data class NodeBookEntity(
    @PrimaryKey val nodeId: String,   // 64-char hex / Base58 cryptographic identity
    val alias: String,                // human-readable label, e.g. "Berlin-Relay-1"
    val lastSeen: Long = 0L,          // timestamp (ms)
)

@Dao
interface NodeBookDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: NodeBookEntity)

    @Query("SELECT * FROM node_book ORDER BY lastSeen DESC")
    suspend fun getAll(): List<NodeBookEntity>

    @Query("SELECT * FROM node_book WHERE nodeId = :nodeId LIMIT 1")
    suspend fun get(nodeId: String): NodeBookEntity?

    @Query("DELETE FROM node_book WHERE nodeId = :nodeId")
    suspend fun delete(nodeId: String)
}
