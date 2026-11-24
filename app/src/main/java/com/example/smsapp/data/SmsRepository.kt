package com.example.smsapp.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.example.smsapp.security.VaultCrypto
import com.example.smsapp.notify.NotificationHelper

/** [address] is the thread the message was stored under (for a private number this is the address it was saved with). */
data class IngestResult(val id: Long, val vaulted: Boolean, val blocked: Boolean, val address: String)

private const val IMPORT_BATCH = 500

const val BIN_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

class SmsRepository(private val context: Context, private val dao: MessageDao) {
    val conversations: Flow<List<ConversationPreview>> = kotlinx.coroutines.flow.combine(dao.conversations(), dao.draftOnlyConversations()) { chats, drafts ->
        if (drafts.isEmpty()) chats
        else (chats + drafts).sortedWith(compareByDescending<ConversationPreview> { it.isPinned }.thenByDescending { it.timestamp })
    }
    fun search(query: String): Flow<List<ConversationPreview>> =
        dao.searchConversations(query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"))
    fun thread(addr: String): Flow<List<MessageEntity>> = dao.thread(addr)
    // Decrypting used to run on the main thread (a Flow's map runs where it is collected), for every message, on every database change.
    // Now: off the main thread, and each message is decrypted once while the chat is open (the cache dies with the collection).
    fun privateThread(addr: String): Flow<List<MessageEntity>> = kotlinx.coroutines.flow.flow {
        val plain = HashMap<String, String>()
        emitAll(dao.privateThread(addr).map { rows ->
            rows.map { row -> row.copy(body = plain.getOrPut(row.body) { runCatching { VaultCrypto.decrypt(row.body) }.getOrDefault("[Unable to decrypt private message]") }) }
        })
    }.flowOn(Dispatchers.Default)
    fun archivedConversations(): Flow<List<ConversationPreview>> = dao.archivedConversations()
    fun starred(): Flow<List<MessageEntity>> = dao.starred()
    fun privateConversations(): Flow<List<ConversationPreview>> = kotlinx.coroutines.flow.flow {
        val plain = HashMap<String, String>()
        emitAll(dao.privateConversations().map { rows -> rows.map { row -> row.copy(body = plain.getOrPut(row.body) { runCatching { VaultCrypto.decrypt(row.body) }.getOrDefault("") }) } })
    }.flowOn(Dispatchers.Default)
    fun spam(): Flow<List<MessageEntity>> = dao.spam()
    fun scheduledMessages(): Flow<List<ScheduledSmsEntity>> = dao.scheduledMessages()

    private suspend fun matchingAddress(address: String, candidates: List<String>): String? =
        candidates.firstOrNull { candidate -> candidate == address || runCatching { PhoneNumberUtils.compare(candidate, address) }.getOrDefault(false) }

    suspend fun isVaulted(address: String): Boolean = withContext(Dispatchers.IO) {
        dao.isVaultedExact(address) || matchingAddress(address, dao.vaultedAddresses()) != null
    }

    suspend fun isBlocked(address: String): Boolean = withContext(Dispatchers.IO) {
        dao.isBlockedExact(address) || matchingAddress(address, dao.blockedAddresses()) != null
    }

    /** Returns the local message id. subscriptionId null = resolve from conversation / app preference. */
    suspend fun send(address: String, body: String, deliveryReports: Boolean = false, subscriptionId: Int? = null, retryId: Long? = null): Long = withContext(Dispatchers.IO) {
        require(address.isNotBlank()) { "Recipient is required" }
        require(body.isNotBlank()) { "Message cannot be empty" }
        // Resolved before any row is created, so an unavailable SIM never leaves a half-sent message.
        val sub = resolveSubscription(address, subscriptionId)
        val mgr = SimManager.smsManager(context, sub)
        val vaulted = isVaulted(address)
        val now = System.currentTimeMillis()
        val storedBody = if (vaulted) VaultCrypto.encrypt(body) else body
        val localId: Long
        if (retryId != null) {
            // Retrying a failed message re-uses its row: drop the old provider copy and the old callback state, then send again.
            val old = dao.byId(retryId)
            old?.providerId?.let { id -> runCatching { context.contentResolver.delete(android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null) } }
            val callbackPrefs = context.getSharedPreferences("sms_callback_state", Context.MODE_PRIVATE)
            callbackPrefs.edit().apply { callbackPrefs.all.keys.filter { it.startsWith("${retryId}_") }.forEach { remove(it) } }.apply()
            check(dao.resetForRetry(retryId, now) == 1) { "This message can no longer be retried" }
            localId = retryId
        } else {
            localId = dao.insert(MessageEntity(address = address, body = storedBody, timestamp = now, outgoing = true, isVaulted = vaulted, status = "sending", subscriptionId = sub))
        }
        val parts = mgr.divideMessage(body)
        // One unique PendingIntent per message part (the data URI differs), so extras can never be swapped between messages.
        // The delivery-report intent must be mutable: Android attaches the status-report PDU to it, and an immutable
        // PendingIntent drops that PDU, which is the only way to tell "delivered" from "accepted by the carrier, still pending".
        fun callback(action: String, partIndex: Int, mutable: Boolean): PendingIntent {
            val callbackIntent = Intent(context, com.example.smsapp.receiver.SmsStatusReceiver::class.java)
                .setAction("${context.packageName}.$action")
                .setData(Uri.parse("smsapp://status/$localId/$partIndex"))
                .putExtra("message_id", localId)
                .putExtra("part_index", partIndex)
                .putExtra("part_count", parts.size)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, 0, callbackIntent, flags)
        }
        val sentIntents = ArrayList(parts.indices.map { callback("SMS_SENT", it, false) })
        val deliveredIntents = if (deliveryReports) ArrayList(parts.indices.map { callback("SMS_DELIVERED", it, true) }) else null
        try {
            if (parts.size == 1) mgr.sendTextMessage(address, null, body, sentIntents.first(), deliveredIntents?.firstOrNull())
            else mgr.sendMultipartTextMessage(address, null, parts, sentIntents, deliveredIntents)
        } catch (error: Exception) {
            dao.updateStatus(localId, "failed")
            throw error
        }
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body); put(Telephony.Sms.DATE, now)
            put(Telephony.Sms.READ, 1); put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            if (sub != null) put(Telephony.Sms.SUBSCRIPTION_ID, sub)
        }
        if (!vaulted) {
            val providerUri = runCatching { context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values) }.getOrNull()
            dao.setProviderId(localId, providerUri?.lastPathSegment?.toLongOrNull())
        }
        localId
    }

    /** Returns null when the message was a duplicate, so callers never notify twice for one SMS. */
    suspend fun ingest(address: String, body: String, ts: Long, providerId: Long? = null, subscriptionId: Int? = null): IngestResult? = withContext(Dispatchers.IO) {
        if (providerId != null && dao.byProviderId(providerId) != null) return@withContext null
        val vaulted = isVaulted(address)
        // A private number saved as 099... / 088... keeps its thread when the carrier delivers it as +265..., so the message shows in that chat.
        val thread = if (vaulted) vaultedThreadAddress(address) else address
        val blocked = isBlocked(address)
        // Vault bodies are AES-GCM with a random IV, so ciphertext never matches; compare decrypted text instead.
        val duplicate = if (vaulted) dao.messagesAt(thread, ts, false).any { runCatching { VaultCrypto.decrypt(it.body) }.getOrNull() == body }
            else dao.duplicateMessage(address, body, ts, false)
        if (duplicate) return@withContext null
        val storedBody = if (vaulted) VaultCrypto.encrypt(body) else body
        val id = dao.insert(MessageEntity(address = thread, body = storedBody, timestamp = ts, outgoing = false, isVaulted = vaulted, isSpam = blocked, providerId = if (vaulted || blocked) null else providerId, status = "received", isRead = blocked, receivedAt = System.currentTimeMillis(), subscriptionId = subscriptionId))
        if (id <= 0L) null else IngestResult(id, vaulted, blocked, thread)
    }

    /** The address a private number was saved under, matching +265... against 099... style spellings of the same number. */
    private suspend fun vaultedThreadAddress(address: String): String {
        val saved = dao.vaultedNumberAddresses()
        return saved.firstOrNull { it == address } ?: matchingAddress(address, saved) ?: address
    }

    /**
     * Moves private messages that were stored under another spelling of a private number (e.g. +26599... for a number
     * saved as 099...) onto the saved address, so earlier messages that never showed up appear in the chat.
     */
    suspend fun repairPrivateAddresses() = withContext(Dispatchers.IO) {
        val saved = dao.vaultedNumberAddresses()
        if (saved.isEmpty()) return@withContext
        dao.vaultedMessageAddresses().filter { it !in saved }.forEach { stray ->
            matchingAddress(stray, saved)?.let { target -> dao.reassignVaultedAddress(stray, target) }
        }
    }

    /** Marks a thread read in Room and, where this app owns the rows, in the system SMS provider. */
    suspend fun markRead(address: String) = withContext(Dispatchers.IO) {
        val providerIds = dao.unreadProviderIds(address)
        if (dao.markThreadRead(address) == 0) return@withContext
        if (providerIds.isNotEmpty()) runCatching {
            val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 1) }
            providerIds.forEach { id -> context.contentResolver.update(android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), values, null, null) }
        }
    }
    /** Marks every ordinary (non-private) conversation read, in Room and in the system SMS provider, and clears their notifications. */
    suspend fun markAllRead(): Int = withContext(Dispatchers.IO) {
        val providerIds = dao.allUnreadProviderIds()
        val addresses = dao.unreadAddresses()
        val changed = dao.markAllRead()
        if (changed == 0) return@withContext 0
        if (providerIds.isNotEmpty()) runCatching {
            val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 1) }
            providerIds.forEach { id -> context.contentResolver.update(android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), values, null, null) }
        }
        addresses.forEach { runCatching { NotificationHelper.cancel(context, it) } }
        changed
    }
    // ---- SIM preferences -------------------------------------------------------------------
    private val settings get() = context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE)
    fun defaultSim(): Int? = if (settings.contains("default_sim_sub_id")) settings.getInt("default_sim_sub_id", -1) else null
    fun setDefaultSim(subscriptionId: Int?) { settings.edit().apply { if (subscriptionId == null) remove("default_sim_sub_id") else putInt("default_sim_sub_id", subscriptionId) }.apply() }
    suspend fun conversationSim(address: String): Int? = withContext(Dispatchers.IO) { dao.conversationPrefs(address)?.simSubscriptionId }
    suspend fun setConversationSim(address: String, subscriptionId: Int?) = withContext(Dispatchers.IO) {
        dao.ensureConversationPrefs(ConversationPrefsEntity(address))
        dao.setConversationSimPref(address, subscriptionId)
    }
    suspend fun conversationPrefs(address: String): ConversationPrefsEntity? = withContext(Dispatchers.IO) { dao.conversationPrefs(address) }
    suspend fun saveConversationNotifications(address: String, muted: Boolean, sound: String?, vibration: String?) = withContext(Dispatchers.IO) {
        dao.ensureConversationPrefs(ConversationPrefsEntity(address))
        dao.setConversationNotifications(address, muted, sound, vibration)
    }
    /** Maps a typed or contact number onto the address an existing thread is stored under, so one person never gets two threads. */
    suspend fun resolveAddress(input: String): String = withContext(Dispatchers.IO) {
        val typed = input.trim()
        val matches = dao.allAddresses().filter { it == typed || runCatching { PhoneNumberUtils.compare(it, typed) }.getOrDefault(false) }
        matches.firstOrNull { it == typed } ?: matches.firstOrNull() ?: PhoneNumberUtils.stripSeparators(typed).takeIf { it.isNotEmpty() } ?: typed
    }
    suspend fun setPinned(address: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        dao.ensureConversationPrefs(ConversationPrefsEntity(address))
        dao.setPinned(address, pinned)
    }
    suspend fun saveConversationTheme(address: String, background: String?, outgoing: Int?, incoming: Int?) = withContext(Dispatchers.IO) {
        dao.ensureConversationPrefs(ConversationPrefsEntity(address))
        dao.setConversationTheme(address, background, outgoing, incoming)
    }
    suspend fun messageCount(address: String, vaulted: Boolean): Int = withContext(Dispatchers.IO) { dao.messageCountFor(address, vaulted) }

    /**
     * Puts a number in the private space and returns the address to open. Existing threads for the same number
     * (even if stored in another format, e.g. with or without the country code) are moved too.
     */
    suspend fun moveToPrivate(input: String): String = withContext(Dispatchers.IO) {
        val typed = input.trim()
        val stored = dao.allAddresses().filter { it == typed || runCatching { PhoneNumberUtils.compare(it, typed) }.getOrDefault(false) }
        val targets = if (stored.isEmpty()) listOf(typed) else stored
        targets.forEach { setVaulted(it, true) }
        targets.first()
    }
    suspend fun vaultedAmong(addresses: List<String>): Set<String> = withContext(Dispatchers.IO) { addresses.filter { isVaulted(it) }.toSet() }

    /**
     * Order: explicit choice, conversation preference, app default, system default (null).
     * A chosen SIM that is missing is an error, never a silent switch to the other SIM
     * (that could send from the wrong number or bill the wrong plan).
     */
    suspend fun resolveSubscription(address: String, explicit: Int? = null): Int? {
        val wanted = explicit ?: dao.conversationPrefs(address)?.simSubscriptionId ?: defaultSim()
        if (wanted == null || wanted == SimManager.SYSTEM_DEFAULT) return null
        val active = SimManager.activeSims(context)
        if (active.isEmpty()) throw SimUnavailableException("Cannot verify the selected SIM. Allow phone access in Settings or choose System default SIM.")
        if (active.none { it.subscriptionId == wanted }) throw SimUnavailableException("The selected SIM is not available (removed or disabled). Choose another SIM.")
        return wanted
    }

    private fun removeProviderCopies(address: String) {
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS)
        val matchingIds = mutableListOf<Long>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            while (cursor.moveToNext()) {
                val candidate = cursor.getString(addressColumn) ?: continue
                val matches = candidate == address || runCatching { PhoneNumberUtils.compare(candidate, address) }.getOrDefault(false)
                if (matches) matchingIds += cursor.getLong(idColumn)
            }
        }
        matchingIds.forEach { id ->
            val rowUri = android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
            context.contentResolver.delete(rowUri, null, null)
        }
    }

    suspend fun setVaulted(address: String, locked: Boolean) = withContext(Dispatchers.IO) {
        if (locked) {
            // A number that becomes private must leave nothing behind in the (public) Bin.
            dao.purgeBinned(address)
            val rows = dao.messagesForAddress(address)
            val draft = dao.getDraft(address)
            try {
                rows.filter { !it.isVaulted }.forEach { row -> dao.updateBody(row.id, VaultCrypto.encrypt(row.body)) }
                if (draft != null && !draft.body.startsWith("vault:v1:")) {
                    dao.saveDraft(draft.copy(body = VaultCrypto.encrypt(draft.body)))
                }
                // Remove plaintext system-provider copies before declaring the thread vaulted.
                removeProviderCopies(address)
                dao.addVaultedNumber(VaultedNumberEntity(address, System.currentTimeMillis()))
                dao.setVaulted(address, true)
                NotificationHelper.cancel(context, address)
                dao.messagesForAddress(address).forEach { dao.setProviderId(it.id, null) }
            } catch (error: Exception) {
                dao.messagesForAddress(address).filter { it.body.startsWith("vault:v1:") }.forEach { row ->
                    runCatching { dao.updateBody(row.id, VaultCrypto.decrypt(row.body)) }
                }
                draft?.let { saved -> runCatching { dao.saveDraft(saved.copy(body = if (saved.body.startsWith("vault:v1:")) VaultCrypto.decrypt(saved.body) else saved.body)) } }
                throw error
            }
        } else {
            val savedDraft = dao.getDraft(address)
            dao.messagesForAddress(address).filter { it.isVaulted }.forEach { row ->
                val plain = VaultCrypto.decrypt(row.body)
                dao.updateBody(row.id, plain)
                val values = android.content.ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, plain); put(Telephony.Sms.DATE, row.timestamp)
                    put(Telephony.Sms.READ, 1); put(Telephony.Sms.TYPE, if (row.outgoing) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX)
                }
                val uri = runCatching { context.contentResolver.insert(if (row.outgoing) Telephony.Sms.Sent.CONTENT_URI else Telephony.Sms.Inbox.CONTENT_URI, values) }.getOrNull()
                dao.setProviderId(row.id, uri?.lastPathSegment?.toLongOrNull())
            }
            if (savedDraft != null && savedDraft.body.startsWith("vault:v1:")) {
                dao.saveDraft(savedDraft.copy(body = VaultCrypto.decrypt(savedDraft.body)))
            }
            dao.setVaulted(address, false)
            dao.removeVaultedNumber(address)
        }
    }
    suspend fun saveDraft(address: String, body: String) = withContext(Dispatchers.IO) {
        if (body.isBlank()) dao.deleteDraft(address)
        else {
            val privateConversation = isVaulted(address)
            // Opening a chat re-saves its unchanged draft: ignore that, so only real edits move the chat up the list.
            if (!privateConversation && dao.getDraft(address)?.body == body) return@withContext
            dao.saveDraft(DraftEntity(address, if (privateConversation) VaultCrypto.encrypt(body) else body, System.currentTimeMillis()))
        }
    }
    suspend fun loadDraft(address: String): String? = withContext(Dispatchers.IO) {
        val row = dao.getDraft(address) ?: return@withContext null
        if (isVaulted(address)) runCatching { VaultCrypto.decrypt(row.body) }.getOrNull() else row.body
    }
    suspend fun deleteMessage(id: Long) = withContext(Dispatchers.IO) {
        val row = dao.byId(id) ?: return@withContext
        // Only remove the provider row when this app owns the corresponding ID.
        row.providerId?.let { providerId ->
            runCatching { context.contentResolver.delete(android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId), null, null) }
        }
        dao.deleteMessage(id)
    }
    /**
     * Deleting a whole ordinary conversation moves it to the Bin (kept 30 days, restorable). Private conversations are
     * never kept: they are erased immediately and nothing about them reaches the Bin.
     */
    suspend fun deleteConversation(address: String) = withContext(Dispatchers.IO) {
        val vaulted = isVaulted(address)
        if (vaulted) {
            dao.deleteConversation(address, true)
            dao.deleteDraft(address)
            dao.deleteConversationPrefs(address)
            return@withContext
        }
        // The system SMS provider copy goes now (so other apps no longer show it); the Room rows wait in the Bin.
        val ids = dao.messagesForAddress(address).mapNotNull { it.providerId }
        ids.forEach { id -> runCatching { context.contentResolver.delete(android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null) } }
        dao.binConversation(address, System.currentTimeMillis())
        dao.deleteDraft(address)
        runCatching { NotificationHelper.cancel(context, address) }
    }

    // ---- Bin ---------------------------------------------------------------------------------
    val binned: Flow<List<BinItem>> = dao.binned()
    val binnedCount: Flow<Int> = dao.binnedCount()
    fun binAutoDelete(): Boolean = settings.getBoolean("bin_auto_delete", true)
    fun setBinAutoDelete(enabled: Boolean) { settings.edit().putBoolean("bin_auto_delete", enabled).apply() }

    /** Puts a deleted conversation back in the list, and back in the system SMS provider where this app is allowed to write. */
    suspend fun restoreFromBin(address: String) = withContext(Dispatchers.IO) {
        dao.binnedRows(address).forEach { row ->
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.ADDRESS, row.address); put(Telephony.Sms.BODY, row.body); put(Telephony.Sms.DATE, row.timestamp)
                put(Telephony.Sms.READ, if (row.isRead || row.outgoing) 1 else 0)
                put(Telephony.Sms.TYPE, if (row.outgoing) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            val uri = runCatching { context.contentResolver.insert(if (row.outgoing) Telephony.Sms.Sent.CONTENT_URI else Telephony.Sms.Inbox.CONTENT_URI, values) }.getOrNull()
            dao.setProviderId(row.id, uri?.lastPathSegment?.toLongOrNull())
            dao.restoreRow(row.id)
        }
    }
    /** Erases a conversation from the Bin for good. */
    suspend fun purgeFromBin(address: String) = withContext(Dispatchers.IO) {
        dao.purgeBinned(address)
        // Nothing left of it: drop its saved settings too, unless the number has live messages again.
        if (dao.activeCountFor(address) == 0) { dao.deleteDraft(address); dao.deleteConversationPrefs(address) }
    }
    suspend fun emptyBin() = withContext(Dispatchers.IO) { dao.purgeAllBinned() }
    /** Auto-delete: anything that has been in the Bin for more than 30 days. Does nothing when auto-delete is switched off. */
    suspend fun purgeExpiredBin(): Int = withContext(Dispatchers.IO) {
        if (!binAutoDelete()) 0 else dao.purgeBinnedBefore(System.currentTimeMillis() - BIN_RETENTION_MILLIS)
    }
    /** Sends a failed message again, in place. */
    suspend fun retrySend(id: Long, deliveryReports: Boolean = false) {
        val row = dao.byId(id) ?: throw IllegalArgumentException("Message no longer exists")
        require(row.outgoing && row.status.startsWith("failed")) { "Only a message that failed to send can be retried" }
        val body = if (row.isVaulted) VaultCrypto.decrypt(row.body) else row.body
        send(row.address, body, deliveryReports, retryId = id)
    }
    suspend fun forwardMessage(id: Long, recipient: String, deliveryReports: Boolean = false) {
        val row = dao.byId(id) ?: throw IllegalArgumentException("Message no longer exists")
        val body = if (row.isVaulted) VaultCrypto.decrypt(row.body) else row.body
        send(recipient, body, deliveryReports)
    }
    suspend fun star(id: Long, value: Boolean) = dao.setStarred(id, value)
    suspend fun archive(address: String, value: Boolean) = dao.setArchived(address, value)
    suspend fun block(address: String, value: Boolean) = dao.setSpam(address, value)

    /** (imported so far, total) while the first-time import runs; null otherwise. The conversation list shows it. */
    private val _importProgress = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Int>?>(null)
    val importProgress: kotlinx.coroutines.flow.StateFlow<Pair<Int, Int>?> = _importProgress
    private val importLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Imports existing provider rows. Call only after READ_SMS permission and default-role grant.
     * Newest messages first, so recent chats appear straight away; saved in batches (one transaction each) instead of one write
     * per message; messages already imported are recognised from sets loaded once, not by a query per message.
     */
    suspend fun syncProviderMessages() = withContext(Dispatchers.IO) {
        // Two overlapping syncs (e.g. the screen is recreated mid-import) would both try to insert the same rows.
        if (!importLock.tryLock()) return@withContext
        try {
            val knownProviderIds = HashSet<Long>(dao.allProviderIds())
            val knownKeys = HashSet<String>().apply { dao.messageKeys().forEach { add(importKey(it.address, it.timestamp, it.outgoing, it.body)) } }
            // Per-number answers, so the (comparatively slow) vault/blocked checks run once per number and not once per message.
            val vaultedCache = HashMap<String, Boolean>()
            val blockedCache = HashMap<String, Boolean>()
            val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)
            context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                val total = c.count
                val idCol = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressCol = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeCol = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readCol = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                val batch = ArrayList<MessageEntity>(IMPORT_BATCH)
                var seen = 0
                // Only show progress when there is real work to do, so a normal launch never flashes it.
                var showProgress = false
                suspend fun flush() {
                    if (batch.isNotEmpty()) { dao.insertAll(batch.toList()); batch.clear() }
                    if (showProgress) _importProgress.value = seen to total
                }
                while (c.moveToNext()) {
                    seen++
                    val id = c.getLong(idCol)
                    if (id !in knownProviderIds) {
                        val address = c.getString(addressCol) ?: "Unknown"
                        // A vaulted thread must never be re-imported from the shared provider.
                        if (!vaultedCache.getOrPut(address) { isVaulted(address) }) {
                            val body = c.getString(bodyCol) ?: ""
                            val timestamp = c.getLong(dateCol)
                            val type = c.getInt(typeCol)
                            val outgoing = type == Telephony.Sms.MESSAGE_TYPE_SENT || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
                            if (knownKeys.add(importKey(address, timestamp, outgoing, body))) {
                                val blocked = blockedCache.getOrPut(address) { isBlocked(address) }
                                batch.add(MessageEntity(address = address, body = body, timestamp = timestamp, outgoing = outgoing, isSpam = blocked, providerId = if (blocked) null else id, status = "synced", isRead = outgoing || blocked || c.getInt(readCol) == 1))
                                if (batch.size >= IMPORT_BATCH) { showProgress = true; flush() }
                            }
                        }
                    }
                }
                flush()
            }
        } finally {
            _importProgress.value = null
            importLock.unlock()
        }
    }

    private fun importKey(address: String, timestamp: Long, outgoing: Boolean, body: String) = "$address|$timestamp|$outgoing|${body.length}|${body.hashCode()}"
}
