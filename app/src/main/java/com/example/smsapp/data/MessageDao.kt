package com.example.smsapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(m: MessageEntity): Long
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
    @Query("SELECT * FROM messages WHERE address = :addr AND isVaulted = 0 AND isSpam = 0 ORDER BY timestamp ASC")
    fun thread(addr: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE address = :addr AND isVaulted = 1 ORDER BY timestamp ASC")
    fun privateThread(addr: String): Flow<List<MessageEntity>>
    @Query("SELECT address, body, MAX(timestamp) AS timestamp, SUM(CASE WHEN outgoing = 0 AND isRead = 0 THEN 1 ELSE 0 END) AS unread FROM messages WHERE isVaulted = 0 AND isSpam = 0 AND isArchived = 0 GROUP BY address ORDER BY timestamp DESC")
    fun conversations(): Flow<List<ConversationPreview>>
    @Query("SELECT address, body, MAX(timestamp) AS timestamp, SUM(CASE WHEN outgoing = 0 AND isRead = 0 THEN 1 ELSE 0 END) AS unread FROM messages WHERE isVaulted = 0 AND isSpam = 0 AND isArchived = 0 AND (address LIKE '%' || :query || '%' ESCAPE '\\' OR body LIKE '%' || :query || '%' ESCAPE '\\') GROUP BY address ORDER BY timestamp DESC")
    fun searchConversations(query: String): Flow<List<ConversationPreview>>
    @Query("SELECT address, body, MAX(timestamp) AS timestamp, 0 AS unread FROM messages WHERE isVaulted = 0 AND isSpam = 0 AND isArchived = 1 GROUP BY address ORDER BY timestamp DESC")
    fun archivedConversations(): Flow<List<ConversationPreview>>
    @Query("UPDATE messages SET status = 'sending', timestamp = :now, deliveredAt = NULL, providerId = NULL WHERE id = :id AND outgoing = 1 AND status LIKE 'failed%'") suspend fun resetForRetry(id: Long, now: Long): Int
    @Query("SELECT * FROM messages WHERE isStarred = 1 AND isVaulted = 0 ORDER BY timestamp DESC") fun starred(): Flow<List<MessageEntity>>
    /** Every number in the private space (including ones with no messages yet), newest activity first. */
    @Query("SELECT v.address AS address, COALESCE((SELECT m.body FROM messages m WHERE m.address = v.address AND m.isVaulted = 1 ORDER BY m.timestamp DESC LIMIT 1), '') AS body, COALESCE((SELECT MAX(m.timestamp) FROM messages m WHERE m.address = v.address AND m.isVaulted = 1), v.addedAt) AS timestamp, (SELECT COUNT(*) FROM messages m WHERE m.address = v.address AND m.isVaulted = 1 AND m.outgoing = 0 AND m.isRead = 0) AS unread FROM vaulted_numbers v ORDER BY timestamp DESC")
    fun privateConversations(): Flow<List<ConversationPreview>>
    @Query("SELECT DISTINCT address FROM messages") suspend fun allAddresses(): List<String>
    @Query("SELECT COUNT(*) FROM messages WHERE address = :address AND isVaulted = :vaulted") suspend fun messageCountFor(address: String, vaulted: Boolean): Int
    @Query("UPDATE messages SET status = 'delivered', deliveredAt = :at WHERE id = :id AND status NOT LIKE 'failed%'") suspend fun markDelivered(id: Long, at: Long)
    @Query("UPDATE conversation_prefs SET themeBackground = :background, themeOutgoing = :outgoing, themeIncoming = :incoming WHERE address = :address") suspend fun setConversationTheme(address: String, background: String?, outgoing: Int?, incoming: Int?)
    @Query("SELECT * FROM messages WHERE isSpam = 1 AND isVaulted = 0 ORDER BY timestamp DESC") fun spam(): Flow<List<MessageEntity>>
    @Query("UPDATE messages SET isVaulted = :locked WHERE address = :address") suspend fun setVaulted(address: String, locked: Boolean)
    @Query("SELECT * FROM messages WHERE address = :address") suspend fun messagesForAddress(address: String): List<MessageEntity>
    @Query("UPDATE messages SET body = :body WHERE id = :id") suspend fun updateBody(id: Long, body: String)
    @Query("UPDATE messages SET isStarred = :starred WHERE id = :id") suspend fun setStarred(id: Long, starred: Boolean)
    @Query("UPDATE messages SET isArchived = :archived WHERE address = :address") suspend fun setArchived(address: String, archived: Boolean)
    @Query("UPDATE messages SET isSpam = :spam WHERE address = :address") suspend fun setSpam(address: String, spam: Boolean)
    @Query("UPDATE messages SET status = CASE WHEN status LIKE 'failed%' THEN status WHEN status = 'delivered' AND :status = 'sent' THEN status WHEN :status LIKE 'failed%' THEN :status ELSE :status END WHERE id = :id") suspend fun updateStatus(id: Long, status: String)
    @Query("UPDATE messages SET providerId = :providerId WHERE id = :id") suspend fun setProviderId(id: Long, providerId: Long?)
    @Query("SELECT address FROM vaulted_numbers UNION SELECT address FROM messages WHERE isVaulted = 1") suspend fun vaultedAddresses(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addVaultedNumber(entity: VaultedNumberEntity)
    @Query("DELETE FROM vaulted_numbers WHERE address = :address") suspend fun removeVaultedNumber(address: String)
    @Query("SELECT providerId FROM messages WHERE address = :address AND isRead = 0 AND outgoing = 0 AND isVaulted = 0 AND providerId IS NOT NULL") suspend fun unreadProviderIds(address: String): List<Long>
    @Query("UPDATE messages SET isRead = 1 WHERE address = :address AND isRead = 0") suspend fun markThreadRead(address: String): Int
    @Query("SELECT * FROM messages WHERE address = :address AND timestamp = :timestamp AND outgoing = :outgoing") suspend fun messagesAt(address: String, timestamp: Long, outgoing: Boolean): List<MessageEntity>
    @Query("SELECT DISTINCT address FROM messages WHERE isSpam = 1") suspend fun blockedAddresses(): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM vaulted_numbers WHERE address = :address) OR EXISTS(SELECT 1 FROM messages WHERE address = :address AND isVaulted = 1)") suspend fun isVaultedExact(address: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE address = :address AND isSpam = 1 LIMIT 1)") suspend fun isBlockedExact(address: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE address = :address AND body = :body AND timestamp = :timestamp AND outgoing = :outgoing LIMIT 1)") suspend fun duplicateMessage(address: String, body: String, timestamp: Long, outgoing: Boolean): Boolean
    @Query("SELECT * FROM messages WHERE providerId = :providerId LIMIT 1") suspend fun byProviderId(providerId: Long): MessageEntity?
    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1") suspend fun byId(id: Long): MessageEntity?
}

data class ConversationPreview(val address: String, val body: String, val timestamp: Long, val unread: Int = 0)
