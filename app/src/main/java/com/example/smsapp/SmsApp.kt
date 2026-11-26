package com.example.smsapp

import android.app.Application
import androidx.room.Room
import com.example.smsapp.appearance.AppearanceStore
import com.example.smsapp.data.AppDatabase
import com.example.smsapp.data.SmsRepository
import com.example.smsapp.notify.NotificationHelper
import com.example.smsapp.schedule.BinCleanupWorker
import com.example.smsapp.schedule.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsApp : Application() {
    val db by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "sms.db")
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11)
        .build() }
    val repo by lazy { SmsRepository(this, db.messageDao()) }
    val appearance by lazy { AppearanceStore(this) }

    override fun onCreate() {
        super.onCreate()
        com.example.smsapp.security.VaultCrypto.init(this)
        NotificationHelper.createChannels(this)
        // Keep WorkManager consistent with Room after updates, restarts or restored data.
        CoroutineScope(Dispatchers.IO).launch { runCatching { Scheduler.reconcile(this@SmsApp) } }
        // Bin: empty out conversations that have been in it for more than 30 days (now, and once a day after that).
        CoroutineScope(Dispatchers.IO).launch { runCatching { repo.purgeExpiredBin() } }
        // Private chats: convert old messages to the fast encryption format in the background (a one-off, slow only the first time).
        CoroutineScope(Dispatchers.IO).launch { runCatching { repo.migrateVaultEncryption() } }
        BinCleanupWorker.schedule(this)
    }
}
