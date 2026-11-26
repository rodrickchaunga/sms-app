package com.example.smsapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(m: MessageEntity): Long
    /** One transaction for the whole list: much faster than a write per message. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(items: List<MessageEntity>)
    @Query("SELECT providerId FROM messages WHERE providerId IS NOT NULL") suspend fun allProviderIds(): List<Long>
    @Query("SELECT address, body, timestamp, outgoing FROM messages") suspend fun messageKeys(): List<MessageKey>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDraft(draft: DraftEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertScheduled(item: ScheduledSmsEntity)
    @Query("SELECT * FROM scheduled_sms ORDER BY CASE WHEN status IN ('pending','sending') THEN 0 ELSE 1 END, sendAtMillis ASC") fun scheduledMessages(): Flow<List<ScheduledSmsEntity>>
    @Query("SELECT * FROM scheduled_sms WHERE status IN ('pending','sending')") suspend fun activeScheduled(): List<ScheduledSmsEntity>
    @Query("UPDATE scheduled_sms SET status = 'sending', attempts = attempts + 1, failureReason = NULL, updatedAtMillis = :now WHERE id = :id AND status = 'pending'") suspend fun claimScheduled(id: String, now: Long): Int
    @Query("UPDATE scheduled_sms SET status = 'sent', messageId = :messageId, failureReason = NULL, updatedAtMillis = :now WHERE id = :id AND status = 'sending'") suspend fun markScheduledSent(id: String, messageId: Long, now: Long): Int
    @Query("UPDATE scheduled_sms SET status = 'failed', failureReason = :reason, updatedAtMillis = :now WHERE id = :id AND status IN ('sending','pending')") suspend fun markScheduledFailed(id: String, reason: String, now: Long): Int
    @Query("UPDATE scheduled_sms SET status = 'failed', failureReason = :reason, updatedAtMillis = :now WHERE messageId = :messageId AND status = 'sent'") suspend fun failScheduledByMessage(messageId: Long, reason: String, now: Long): Int
    @Query("UPDATE scheduled_sms SET status = 'pending', updatedAtMillis = :now WHERE id = :id AND status = 'sending'") suspend fun revertScheduledToPending(id: String, now: Long): Int
    @Query("UPDATE scheduled_sms SET status = 'cancelled', updatedAtMillis = :now WHERE id = :id AND status IN ('pending','failed')") suspend fun cancelScheduledRow(id: String, now: Long): Int
    @Query("UPDATE scheduled_sms SET address = :address, body = :body, sendAtMillis = :sendAt, subscriptionId = :sub, status = 'pending', failureReason = NULL, attempts = 0, updatedAtMillis = :now WHERE id = :id AND status IN ('pending','failed','cancelled')") suspend fun updateScheduledContent(id: String, address: String, body: String, sendAt: Long, sub: Int?, now: Long): Int
    @Query("UPDATE scheduled_sms SET sendAtMillis = :sendAt, subscriptionId = :sub, status = 'pending', failureReason = NULL, attempts = 0, updatedAtMillis = :now WHERE id = :id AND status IN ('pending','failed','cancelled')") suspend fun rescheduleScheduled(id: String, sendAt: Long, sub: Int?, now: Long): Int
    @Query("UPDATE scheduled_sms SET sendAtMillis = :now, status = 'pending', failureReason = NULL, attempts = 0, updatedAtMillis = :now WHERE id = :id AND status = 'failed'") suspend fun retryScheduledNow(id: String, now: Long): Int
    @Query("DELETE FROM scheduled_sms WHERE id = :id AND status IN ('sent','failed','cancelled')") suspend fun deleteScheduledFinished(id: String): Int
    @Query("DELETE FROM scheduled_sms WHERE status IN ('sent','cancelled')") suspend fun clearFinishedScheduled(): Int
    @Query("SELECT * FROM conversation_prefs WHERE address = :address LIMIT 1") suspend fun conversationPrefs(address: String): ConversationPrefsEntity?
    @Query("DELETE FROM conversation_prefs WHERE address = :address") suspend fun deleteConversationPrefs(address: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensureConversationPrefs(prefs: ConversationPrefsEntity)
    @Query("UPDATE conversation_prefs SET simSubscriptionId = :sub WHERE address = :address") suspend fun setConversationSimPref(address: String, sub: Int?)
    @Query("UPDATE conversation_prefs SET muted = :muted, notifSound = :sound, notifVibration = :vibration WHERE address = :address") suspend fun setConversationNotifications(address: String, muted: Boolean, sound: String?, vibration: String?)
    @Query("SELECT * FROM scheduled_sms WHERE id = :id LIMIT 1") suspend fun scheduledById(id: String): ScheduledSmsEntity?
    @Query("SELECT * FROM drafts WHERE address = :address LIMIT 1") suspend fun getDraft(address: String): DraftEntity?
    @Query("DELETE FROM drafts WHERE address = :address") suspend fun deleteDraft(address: String)
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteMessage(id: Long)
    @Query("DELETE FROM messages WHERE address = :address AND isVaulted = :vaulted") suspend fun deleteConversation(address: String, vaulted: Boolean)
    @Query("SELECT * FROM messages WHERE address = :addr AND isVaulted = 0 AND isSpam = 0 AND deletedAt IS NULL ORDER BY timestamp ASC")
    fun thread(addr: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE address = :addr AND isVaulted = 1 ORDER BY timestamp ASC")
    fun privateThread(addr: String): Flow<List<MessageEntity>>
    @Query("SELECT m.address AS address, m.body AS body, m.outgoing AS lastOutgoing, m.status AS lastStatus, (SELECT d.body FROM drafts d WHERE d.address = m.address AND d.body NOT LIKE 'vault:v1:%') AS draft, MAX(MAX(m.timestamp), COALESCE((SELECT d2.updatedAt FROM drafts d2 WHERE d2.address = m.address AND d2.body NOT LIKE 'vault:v1:%'), 0)) AS timestamp, SUM(CASE WHEN m.outgoing = 0 AND m.isRead = 0 THEN 1 ELSE 0 END) AS unread, COALESCE(p.isPinned, 0) AS isPinned FROM messages m LEFT JOIN conversation_prefs p ON p.address = m.address WHERE m.isVaulted = 0 AND m.isSpam = 0 AND m.isArchived = 0 AND m.deletedAt IS NULL GROUP BY m.address ORDER BY isPinned DESC, timestamp DESC")
    fun conversations(): Flow<List<ConversationPreview>>
    /** Typed-but-unsent text for numbers that have no messages yet (a brand-new chat), so the list can still show "Draft: ...". Private numbers are excluded. */
    @Query("SELECT d.address AS address, d.body AS body, 0 AS lastOutgoing, '' AS lastStatus, d.body AS draft, d.updatedAt AS timestamp, 0 AS unread, 0 AS isPinned FROM drafts d WHERE d.body NOT LIKE 'vault:v1:%' AND d.address NOT IN (SELECT address FROM messages WHERE deletedAt IS NULL) AND d.address NOT IN (SELECT address FROM vaulted_numbers)")
    fun draftOnlyConversations(): Flow<List<ConversationPreview>>
    @Query("SELECT address, body, outgoing AS lastOutgoing, status AS lastStatus, NULL AS draft, MAX(timestamp) AS timestamp, SUM(CASE WHEN outgoing = 0 AND isRead = 0 THEN 1 ELSE 0 END) AS unread, 0 AS isPinned FROM messages WHERE isVaulted = 0 AND isSpam = 0 AND isArchived = 0 AND deletedAt IS NULL AND (address LIKE '%' || :query || '%' ESCAPE '\\' OR body LIKE '%' || :query || '%' ESCAPE '\\') GROUP BY address ORDER BY timestamp DESC")
    fun searchConversations(query: String): Flow<List<ConversationPreview>>
    @Query("SELECT address, body, outgoing AS lastOutgoing, status AS lastStatus, NULL AS draft, MAX(timestamp) AS timestamp, 0 AS unread, 0 AS isPinned FROM messages WHERE isVaulted = 0 AND isSpam = 0 AND isArchived = 1 AND deletedAt IS NULL GROUP BY address ORDER BY timestamp DESC")
    fun archivedConversations(): Flow<List<ConversationPreview>>
    @Query("UPDATE messages SET status = 'sending', timestamp = :now, deliveredAt = NULL, providerId = NULL WHERE id = :id AND outgoing = 1 AND status LIKE 'failed%'") suspend fun resetForRetry(id: Long, now: Long): Int
    @Query("SELECT * FROM messages WHERE isStarred = 1 AND isVaulted = 0 AND deletedAt IS NULL ORDER BY timestamp DESC") fun starred(): Flow<List<MessageEntity>>
    /** Every number in the private space (including ones with no messages yet), newest activity first. */
    @Query("SELECT v.address AS address, COALESCE((SELECT m.body FROM messages m WHERE m.address = v.address AND m.isVaulted = 1 ORDER BY m.timestamp DESC LIMIT 1), '') AS body, COALESCE((SELECT m.outgoing FROM messages m WHERE m.address = v.address AND m.isVaulted = 1 ORDER BY m.timestamp DESC LIMIT 1), 0) AS lastOutgoing, COALESCE((SELECT m.status FROM messages m WHERE m.address = v.address AND m.isVaulted = 1 ORDER BY m.timestamp DESC LIMIT 1), '') AS lastStatus, NULL AS draft, COALESCE((SELECT MAX(m.timestamp) FROM messages m WHERE m.address = v.address AND m.isVaulted = 1), v.addedAt) AS timestamp, (SELECT COUNT(*) FROM messages m WHERE m.address = v.address AND m.isVaulted = 1 AND m.outgoing = 0 AND m.isRead = 0) AS unread, 0 AS isPinned FROM vaulted_numbers v ORDER BY timestamp DESC")
    fun privateConversations(): Flow<List<ConversationPreview>>
    @Query("SELECT DISTINCT address FROM messages WHERE deletedAt IS NULL") suspend fun allAddresses(): List<String>
    @Query("SELECT address FROM vaulted_numbers") suspend fun vaultedNumberAddresses(): List<String>
    @Query("SELECT DISTINCT address FROM messages WHERE isVaulted = 1") suspend fun vaultedMessageAddresses(): List<String>
    @Query("UPDATE messages SET address = :to WHERE address = :from AND isVaulted = 1") suspend fun reassignVaultedAddress(from: String, to: String): Int
    @Query("SELECT COUNT(*) FROM messages WHERE address = :address AND isVaulted = :vaulted AND deletedAt IS NULL") suspend fun messageCountFor(address: String, vaulted: Boolean): Int
    @Query("UPDATE messages SET status = 'delivered', deliveredAt = :at WHERE id = :id AND status NOT LIKE 'failed%'") suspend fun markDelivered(id: Long, at: Long)
    @Query("UPDATE conversation_prefs SET isPinned = :pinned WHERE address = :address") suspend fun setPinned(address: String, pinned: Boolean)
    @Query("UPDATE conversation_prefs SET themeBackground = :background, themeOutgoing = :outgoing, themeIncoming = :incoming WHERE address = :address") suspend fun setConversationTheme(address: String, background: String?, outgoing: Int?, incoming: Int?)
    @Query("SELECT * FROM messages WHERE isSpam = 1 AND isVaulted = 0 AND deletedAt IS NULL ORDER BY timestamp DESC") fun spam(): Flow<List<MessageEntity>>
    @Query("UPDATE messages SET isVaulted = :locked WHERE address = :address AND deletedAt IS NULL") suspend fun setVaulted(address: String, locked: Boolean)
    @Query("SELECT * FROM messages WHERE address = :address AND deletedAt IS NULL") suspend fun messagesForAddress(address: String): List<MessageEntity>
    @Query("UPDATE messages SET body = :body WHERE id = :id") suspend fun updateBody(id: Long, body: String)
    /** Private messages still in the older, slow encryption format (see VaultCrypto), a chunk at a time. */
    @Query("SELECT * FROM messages WHERE isVaulted = 1 AND body LIKE 'vault:v1:%' AND body NOT LIKE 'vault:v1:s.%' AND id > :afterId ORDER BY id LIMIT 200")
    suspend fun legacyVaultRows(afterId: Long): List<MessageEntity>
    @Query("UPDATE messages SET isStarred = :starred WHERE id = :id") suspend fun setStarred(id: Long, starred: Boolean)
    @Query("UPDATE messages SET isArchived = :archived WHERE address = :address") suspend fun setArchived(address: String, archived: Boolean)
    @Query("UPDATE messages SET isSpam = :spam WHERE address = :address") suspend fun setSpam(address: String, spam: Boolean)
    @Query("UPDATE messages SET status = CASE WHEN status LIKE 'failed%' THEN status WHEN status = 'delivered' AND :status = 'sent' THEN status WHEN :status LIKE 'failed%' THEN :status ELSE :status END WHERE id = :id") suspend fun updateStatus(id: Long, status: String)
    @Query("UPDATE messages SET providerId = :providerId WHERE id = :id") suspend fun setProviderId(id: Long, providerId: Long?)
    @Query("SELECT address FROM vaulted_numbers UNION SELECT address FROM messages WHERE isVaulted = 1") suspend fun vaultedAddresses(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addVaultedNumber(entity: VaultedNumberEntity)
    @Query("DELETE FROM vaulted_numbers WHERE address = :address") suspend fun removeVaultedNumber(address: String)
    @Query("SELECT providerId FROM messages WHERE address = :address AND isRead = 0 AND outgoing = 0 AND isVaulted = 0 AND providerId IS NOT NULL AND deletedAt IS NULL") suspend fun unreadProviderIds(address: String): List<Long>
    @Query("UPDATE messages SET isRead = 1 WHERE address = :address AND isRead = 0 AND deletedAt IS NULL") suspend fun markThreadRead(address: String): Int
    /** Mark all as read: ordinary conversations only. Private-space threads are never touched from the main list. */
    @Query("SELECT providerId FROM messages WHERE isRead = 0 AND outgoing = 0 AND isVaulted = 0 AND providerId IS NOT NULL") suspend fun allUnreadProviderIds(): List<Long>
    @Query("SELECT DISTINCT address FROM messages WHERE isRead = 0 AND outgoing = 0 AND isVaulted = 0 AND deletedAt IS NULL") suspend fun unreadAddresses(): List<String>
    @Query("UPDATE messages SET isRead = 1 WHERE isRead = 0 AND isVaulted = 0 AND deletedAt IS NULL") suspend fun markAllRead(): Int
    @Query("SELECT * FROM messages WHERE address = :address AND timestamp = :timestamp AND outgoing = :outgoing") suspend fun messagesAt(address: String, timestamp: Long, outgoing: Boolean): List<MessageEntity>
    @Query("SELECT DISTINCT address FROM messages WHERE isSpam = 1") suspend fun blockedAddresses(): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM vaulted_numbers WHERE address = :address) OR EXISTS(SELECT 1 FROM messages WHERE address = :address AND isVaulted = 1)") suspend fun isVaultedExact(address: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE address = :address AND isSpam = 1 LIMIT 1)") suspend fun isBlockedExact(address: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE address = :address AND body = :body AND timestamp = :timestamp AND outgoing = :outgoing LIMIT 1)") suspend fun duplicateMessage(address: String, body: String, timestamp: Long, outgoing: Boolean): Boolean
    @Query("SELECT * FROM messages WHERE providerId = :providerId LIMIT 1") suspend fun byProviderId(providerId: Long): MessageEntity?
    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1") suspend fun byId(id: Long): MessageEntity?

    // ---- Bin (public conversations only; private messages are never kept here) ----
    @Query("UPDATE messages SET deletedAt = :now, providerId = NULL WHERE address = :address AND isVaulted = 0 AND deletedAt IS NULL") suspend fun binConversation(address: String, now: Long): Int
    @Query("SELECT m.address AS address, (SELECT b.body FROM messages b WHERE b.address = m.address AND b.deletedAt IS NOT NULL ORDER BY b.timestamp DESC LIMIT 1) AS body, COUNT(*) AS messageCount, MAX(m.deletedAt) AS deletedAt FROM messages m WHERE m.deletedAt IS NOT NULL AND m.isVaulted = 0 GROUP BY m.address ORDER BY MAX(m.deletedAt) DESC")
    fun binned(): Flow<List<BinItem>>
    @Query("SELECT COUNT(DISTINCT address) FROM messages WHERE deletedAt IS NOT NULL AND isVaulted = 0") fun binnedCount(): Flow<Int>
    @Query("SELECT * FROM messages WHERE address = :address AND deletedAt IS NOT NULL ORDER BY timestamp ASC") suspend fun binnedRows(address: String): List<MessageEntity>
    @Query("UPDATE messages SET deletedAt = NULL WHERE id = :id") suspend fun restoreRow(id: Long)
    @Query("DELETE FROM messages WHERE address = :address AND deletedAt IS NOT NULL") suspend fun purgeBinned(address: String): Int
    @Query("DELETE FROM messages WHERE deletedAt IS NOT NULL") suspend fun purgeAllBinned(): Int
    @Query("DELETE FROM messages WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff") suspend fun purgeBinnedBefore(cutoff: Long): Int
    @Query("SELECT COUNT(*) FROM messages WHERE address = :address AND deletedAt IS NULL AND isVaulted = 0") suspend fun activeCountFor(address: String): Int
}

/** One deleted conversation waiting in the Bin. */
data class BinItem(val address: String, val body: String?, val messageCount: Int, val deletedAt: Long)

/** [lastOutgoing] is true when the newest message in the conversation was sent by this user (the list shows "You: ..."). */
data class ConversationPreview(val address: String, val body: String, val timestamp: Long, val unread: Int = 0, val isPinned: Boolean = false, val lastOutgoing: Boolean = false, val lastStatus: String = "", val draft: String? = null)

/** Just enough of a message to recognise it as already imported. */
data class MessageKey(val address: String, val body: String, val timestamp: Long, val outgoing: Boolean)
