package com.example.smsapp.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.example.smsapp.security.VaultCrypto
import com.example.smsapp.notify.NotificationHelper

data class IngestResult(val id: Long, val vaulted: Boolean, val blocked: Boolean)

class SmsRepository(private val context: Context, private val dao: MessageDao) {
    val conversations: Flow<List<ConversationPreview>> = dao.conversations()
    fun search(query: String): Flow<List<ConversationPreview>> =
        dao.searchConversations(query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"))
    fun thread(addr: String): Flow<List<MessageEntity>> = dao.thread(addr)
    fun privateThread(addr: String): Flow<List<MessageEntity>> = dao.privateThread(addr).map { rows -> rows.map { it.copy(body = runCatching { VaultCrypto.decrypt(it.body) }.getOrDefault("[Unable to decrypt private message]")) } }
    fun archivedConversations(): Flow<List<ConversationPreview>> = dao.archivedConversations()
    fun starred(): Flow<List<MessageEntity>> = dao.starred()
    fun privateConversations(): Flow<List<ConversationPreview>> = dao.privateConversations().map { rows -> rows.map { it.copy(body = runCatching { VaultCrypto.decrypt(it.body) }.getOrDefault("")) } }
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
            localId = dao.insert(MessageEntity(address = address, body = storedBody, timestamp = now, outgoing = true, isVaulted = vaulted, status = "sending"))
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
    suspend fun ingest(address: String, body: String, ts: Long, providerId: Long? = null): IngestResult? = withContext(Dispatchers.IO) {
        if (providerId != null && dao.byProviderId(providerId) != null) return@withContext null
        val vaulted = isVaulted(address)
        val blocked = isBlocked(address)
        // Vault bodies are AES-GCM with a random IV, so ciphertext never matches; compare decrypted text instead.
        val duplicate = if (vaulted) dao.messagesAt(address, ts, false).any { runCatching { VaultCrypto.decrypt(it.body) }.getOrNull() == body }
            else dao.duplicateMessage(address, body, ts, false)
        if (duplicate) return@withContext null
        val storedBody = if (vaulted) VaultCrypto.encrypt(body) else body
        val id = dao.insert(MessageEntity(address = address, body = storedBody, timestamp = ts, outgoing = false, isVaulted = vaulted, isSpam = blocked, providerId = if (vaulted || blocked) null else providerId, status = "received", isRead = blocked, receivedAt = System.currentTimeMillis()))
        if (id <= 0L) null else IngestResult(id, vaulted, blocked)
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
    suspend fun deleteConversation(address: String) = withContext(Dispatchers.IO) {
        val vaulted = isVaulted(address)
        if (!vaulted) {
            val ids = dao.messagesForAddress(address).mapNotNull { it.providerId }
            ids.forEach { id -> runCatching { context.contentResolver.delete(android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null) } }
        }
        dao.deleteConversation(address, vaulted)
        dao.deleteDraft(address)
        dao.deleteConversationPrefs(address)
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

    /** Imports existing provider rows. Call only after READ_SMS permission and default-role grant. */
    suspend fun syncProviderMessages() = withContext(Dispatchers.IO) {
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)
        context.contentResolver.query(uri, projection, null, null, "${Telephony.Sms.DATE} ASC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressCol = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeCol = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val readCol = c.getColumnIndexOrThrow(Telephony.Sms.READ)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                if (dao.byProviderId(id) == null) {
                    val address = c.getString(addressCol) ?: "Unknown"
                    val body = c.getString(bodyCol) ?: ""
                    val timestamp = c.getLong(dateCol)
                    // A vaulted thread must never be re-imported from the shared provider.
                    if (!isVaulted(address)) {
                        val type = c.getInt(typeCol)
                        val outgoing = type == Telephony.Sms.MESSAGE_TYPE_SENT || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
                        val blocked = isBlocked(address)
                        if (!dao.duplicateMessage(address, body, timestamp, outgoing)) {
                            dao.insert(MessageEntity(address = address, body = body, timestamp = timestamp, outgoing = outgoing, isSpam = blocked, providerId = if (blocked) null else id, status = "synced", isRead = outgoing || blocked || c.getInt(readCol) == 1))
                        }
                    }
                }
            }
        }
    }
}
