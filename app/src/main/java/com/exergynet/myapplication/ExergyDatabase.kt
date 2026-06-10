package com.exergynet.myapplication

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        JobStateEntity::class,
        PassiveSensorEntity::class,
        DLTNMessageEntity::class,
        DLTNContactEntity::class,
        NodeBookEntity::class,
        GhostDropEntity::class,
    ],
    version = 8,
    exportSchema = false
)
abstract class ExergyDatabase : RoomDatabase() {

    abstract fun jobStateDao(): JobStateDao
    abstract fun passiveSensorDao(): PassiveSensorDao
    abstract fun dltnMessageDao(): DLTNMessageDao
    abstract fun dltnContactDao(): DLTNContactDao
    abstract fun nodeBookDao(): NodeBookDao
    abstract fun ghostDropDao(): GhostDropDao

    companion object {
        @Volatile private var INSTANCE: ExergyDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS dltn_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        fromNodeId TEXT NOT NULL,
                        toNodeId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        imageHash TEXT,
                        l0TxHash TEXT,
                        timestampMs INTEGER NOT NULL,
                        signature TEXT NOT NULL,
                        delivered INTEGER NOT NULL DEFAULT 0,
                        read INTEGER NOT NULL DEFAULT 0,
                        direction TEXT NOT NULL,
                        outboxPending INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS dltn_contacts (
                        nodeId TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        walletAddress TEXT,
                        lastSeenMs INTEGER NOT NULL DEFAULT 0,
                        discoveryType TEXT NOT NULL DEFAULT 'ble_auto',
                        rssiLast INTEGER NOT NULL DEFAULT -100,
                        publicKeyB64 TEXT,
                        trusted INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS node_book (
                        nodeId TEXT NOT NULL PRIMARY KEY,
                        alias TEXT NOT NULL,
                        lastSeen INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // Batch 2: threaded replies — add the replyToId column to dltn_messages.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE dltn_messages ADD COLUMN replyToId TEXT")
            }
        }

        // LNES-10 Sprint Beta prep: l2TxHash column for Base L2 settlement receipt.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE ghost_drops ADD COLUMN l2TxHash TEXT")
            }
        }

        // LNES-10 Sprint Alpha: Ghost Drops offline survival table.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS ghost_drops (
                        localId       TEXT NOT NULL PRIMARY KEY,
                        dropId        TEXT NOT NULL DEFAULT '',
                        message       TEXT NOT NULL,
                        type          TEXT NOT NULL,
                        lat           REAL NOT NULL,
                        lon           REAL NOT NULL,
                        radiusM       INTEGER NOT NULL,
                        ttlSecs       INTEGER NOT NULL,
                        groups        TEXT NOT NULL DEFAULT '',
                        category      TEXT NOT NULL DEFAULT 'INTEL',
                        timestamp     INTEGER NOT NULL,
                        nonceHex      TEXT NOT NULL,
                        signature     TEXT NOT NULL,
                        minerId       TEXT NOT NULL,
                        attachmentJson TEXT NOT NULL DEFAULT '',
                        syncStatus    TEXT NOT NULL DEFAULT 'PENDING',
                        syncAttempts  INTEGER NOT NULL DEFAULT 0,
                        createdMs     INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): ExergyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ExergyDatabase::class.java,
                    "exergynet_database"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
