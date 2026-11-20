package com.example.smsapp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MessageEntity::class, DraftEntity::class, ScheduledSmsEntity::class, VaultedNumberEntity::class, ConversationPrefsEntity::class], version = 8, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN providerId INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'received'")
                db.execSQL("ALTER TABLE messages ADD COLUMN isStarred INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN isSpam INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_providerId ON messages(providerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_address_timestamp ON messages(address, timestamp)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_sms (id TEXT NOT NULL PRIMARY KEY, address TEXT NOT NULL, body TEXT NOT NULL, sendAtMillis INTEGER NOT NULL, status TEXT NOT NULL, createdAtMillis INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_sms_sendAtMillis ON scheduled_sms(sendAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_sms_status ON scheduled_sms(status)")
            }
        }
        /** Adds read state (existing rows count as read) and a durable vault-number table seeded from current private rows. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE TABLE IF NOT EXISTS vaulted_numbers (address TEXT NOT NULL, addedAt INTEGER NOT NULL, PRIMARY KEY(address))")
                db.execSQL("INSERT OR IGNORE INTO vaulted_numbers (address, addedAt) SELECT address, MAX(timestamp) FROM messages WHERE isVaulted = 1 GROUP BY address")
            }
        }
        /** Scheduler state machine (pending/sending/sent/failed/cancelled), per-message SIM, and per-conversation SIM preference. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_sms ADD COLUMN failureReason TEXT")
                db.execSQL("ALTER TABLE scheduled_sms ADD COLUMN subscriptionId INTEGER")
                db.execSQL("ALTER TABLE scheduled_sms ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scheduled_sms ADD COLUMN messageId INTEGER")
                db.execSQL("ALTER TABLE scheduled_sms ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE scheduled_sms SET status = 'pending' WHERE status IN ('queued', 'retrying')")
                db.execSQL("UPDATE scheduled_sms SET status = 'sent' WHERE status = 'sent to carrier'")
                db.execSQL("UPDATE scheduled_sms SET failureReason = status, status = 'failed' WHERE status LIKE 'failed%'")
                db.execSQL("CREATE TABLE IF NOT EXISTS conversation_prefs (address TEXT NOT NULL, simSubscriptionId INTEGER, PRIMARY KEY(address))")
            }
        }
        /** Per-conversation notification settings. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversation_prefs ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversation_prefs ADD COLUMN notifSound TEXT")
                db.execSQL("ALTER TABLE conversation_prefs ADD COLUMN notifVibration TEXT")
            }
        }
        /** Delivered/received timestamps for message info, and per-conversation chat theme. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN deliveredAt INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN receivedAt INTEGER")
                db.execSQL("ALTER TABLE conversation_prefs ADD COLUMN themeBackground TEXT")
                db.execSQL("ALTER TABLE conversation_prefs ADD COLUMN themeOutgoing INTEGER")
                db.execSQL("ALTER TABLE conversation_prefs ADD COLUMN themeIncoming INTEGER")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS drafts (address TEXT NOT NULL, body TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(address))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_drafts_updatedAt ON drafts(updatedAt)")
            }
        }
    }
}
