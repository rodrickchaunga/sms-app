# SMS App V2.0 — development status

## Phase 3.5 (this delivery): private space, chat screen, delivery reports

Phases 1 to 3 have been compiled and run on a real device. This delivery has NOT been compiled (no Android SDK in the authoring environment): fix the first compiler error first. Room database version is now 8 (migration 7->8: `deliveredAt`/`receivedAt` on messages, theme columns on `conversation_prefs`).

**Private space**
- Reached only by long-pressing the new-conversation button (a tap still starts a normal new conversation). First use asks for a PIN; afterwards PIN or fingerprint/device unlock.
- The PIN, fingerprint switch and private notification mode are in the private space (three-dot > Private settings). Settings no longer mention private conversations at all.
- Inside: list of private conversations with unread badges, "+" to add one (from the conversation list, from contacts, or by typing a number), bulk select (remove from private / delete messages).
- Long-pressing a conversation in Messages no longer makes it private; it starts bulk selection.
- Private numbers are never opened outside the space (notification taps, sms: intents, contacts and typed numbers do nothing for them).
- Private notifications use the normal Messages channel and the same generic "New message" text as hidden notifications, no sender, no actions, and open the app without an address. Option "None" posts nothing. The old separate "Private messages" channel is deleted at start-up. Leaving the app locks the space again; the screen is protected from screenshots while it is open.

**Chat screen**
- Call button (opens the dialer), tap the name for contact details (details, notification settings, per-conversation theme, block/archive/delete).
- Time and status are hidden; tap a message to show them. A message that failed to send always shows "Not sent".
- Long-press a message for info (sent: time and status, plus delivered time when delivered; received: sender's send time and the time this phone received it) and for Select / Copy / Forward / Star / Delete.
- Bulk selection (copy, select all, delete). Messages sit at the bottom and a chat opens at the newest message.

**Delivery reports**
- The carrier's delivery callback also fires for "accepted, still trying". The status-report PDU is now decoded and a message is only marked delivered on a final "delivered" status. The delivery PendingIntent is mutable (an immutable one drops the PDU) and every part has its own PendingIntent.
- If a device returns no readable status report, the message stays "sent".

**Other**
- Contacts tab removed; the new-conversation button lists contacts and accepts a typed number. A typed or contact number reuses the existing thread for that person, even when stored in a different format.
- Settings: Storage section removed (only About remains). Removed unused code: vault list query, storage counters, old contacts screen, unused scheduled-SMS and prefs queries.

### Known gaps
- Archived conversations still have no screen to view or unarchive them (the bulk list therefore offers delete only).
- The scheduled-message list still labels hidden private rows "Private number" / "Private message".

---

## Phase 3 (this delivery): personalization, notification controls, settings dashboard

Source written, NOT compiled or device-tested (no Android SDK/Gradle/network in the authoring environment). Phases 1 to 3 have never been through Gradle: fix the first compiler error first.

**Audit finding:** the previous Personalize screen saved a "dark" and a "compact" flag that nothing read (decorative settings, which the spec forbids). They are replaced; those two old values are not migrated.

**Appearance (DataStore, `appearance_v2`)**
- Theme: system / light / dark applied app-wide, including window background and status/navigation bar icons.
- Accent colour (8 presets plus custom hex). Accent is lightened or darkened automatically if it would be under 3:1 contrast on the current theme.
- Outgoing and incoming bubble colours (presets plus custom hex), 4 bubble shapes, spacing (compact/normal/relaxed), message font, 5 conversation backgrounds, app-wide font size 85% to 150% of the system size.
- Text on bubbles is black or white chosen by contrast, so it is at least 4.5:1 for any bubble colour.
- Live preview at the top of the Personalize tab and a Reset button.
- Image backgrounds are not implemented (needs a picker, storage and memory handling).

**Notifications**
- Global sound (system picker, including silent) and vibration pattern (default/short/long/double/triple/off, tap to preview).
- Android keeps sound and vibration on the channel and apps cannot edit a channel afterwards, so each combination gets its own channel ID and superseded channels are deleted. The system may show a "deleted channels" count.
- Per-conversation settings (conversation menu > Notification settings): mute, own sound, own vibration. "Per-contact" is the same thing because conversations are keyed by number. Private conversations only get mute and use the shared private channel.
- Notification content level: sender and text / sender only / hidden. Private conversations always reveal nothing. Lock-screen preview switch from Phase 1 remains.

**Settings dashboard additions:** default-SMS status, notification sound/vibration/content, character counter and multipart indicator switches (actually drive the composer), read reports (explained as unavailable for SMS), fingerprint/device unlock switch (hides the biometric button; the PIN always works), storage summary with database compaction, About with version/build.

**Storage of preferences:** appearance and composer options are in DataStore. Settings that background receivers read synchronously (delivery reports, lock-screen previews, notification sound/vibration/content, biometric offer, default SIM) remain in SharedPreferences "sms_settings".

Migration 6->7 adds mute/sound/vibration columns to `conversation_prefs`.

### Known gaps after Phase 3
- Not yet built: backup/restore (Phase 4), blocked-number database and contact photos (Phase 5), MMS (Phase 6), bulk select/delete/archive, manual mark-as-unread.
- Settings still show a few dialogs (spam, starred, vault list) in the old style; MMS and backup rows are informational until those phases.
- App-wide font scaling multiplies the system font scale, so very large system sizes plus 150% can overflow some rows.
- No instrumented UI tests; contrast and theme behaviour should be eyeballed on the TECNO KN4 in light and dark.
- The notification small icon is still a framework placeholder and the app has no launcher icon.

## Phase 2 (this delivery): dual-SIM and scheduled SMS

Source written, NOT compiled or device-tested (no Android SDK/Gradle/network in the authoring environment). Phase 1 and Phase 2 have both never been through Gradle: fix the first compiler error first.

**Dual-SIM**
- `SimManager` lists active subscriptions (READ_PHONE_STATE) and sends through `SmsManager` created for the chosen subscription ID. Slot numbers are never used as IDs.
- Resolution order: explicit choice, conversation preference (Room `conversation_prefs`), app default (Settings), system default.
- A chosen SIM that is missing or disabled is an error, never a silent switch to the other SIM. The error appears in the composer; choose another SIM or "System default SIM".
- Composer shows the active SIM and a SIM button in the header (only when 2+ SIMs). Settings has Default SIM and requests phone access.
- Character counter now uses the platform SMS length calculation (GSM vs Unicode) and shows the part count.
- SIM list refreshes when the screen resumes; there is no live listener for hot-swapped SIMs yet.

**Scheduled SMS**
- State machine in Room: pending -> sending -> sent | failed, plus cancelled. All transitions are guarded UPDATEs.
- Idempotent send: the worker atomically claims a row (pending -> sending). Duplicate or stale workers do nothing.
- One unique WorkManager job per message; edit/reschedule replaces it. `Scheduler.reconcile()` runs at every process start: re-enqueues pending rows that lost their job (updates, restored data), cancels orphan jobs, and marks rows stuck in 'sending' for 10+ minutes as failed. It never auto-resends an interrupted send because the SMS may already have left the phone.
- Overdue messages (phone off at the scheduled time) are sent as soon as the app next runs.
- Failures after hand-off to the radio (e.g. no service) flip the scheduled row to failed so it can be retried. Failures before hand-off retry automatically up to 3 times.
- UI: full list with status, edit/reschedule (date + time pickers), cancel, retry, remove, clear finished, per-message SIM.
- Privacy fix: scheduled bodies for private numbers are stored encrypted (previously plaintext in Room and in the WorkManager input data). The list masks private rows; their content cannot be edited from Settings, only time and SIM. Rows scheduled before this change are not retroactively encrypted.
- Migration 5->6 maps old statuses (queued/retrying -> pending, sent to carrier -> sent, failed* -> failed) and adds columns.

### Known gaps after Phase 2
- No instrumented tests for migrations or the scheduler; none of this has run on a device. Dual-SIM needs a real dual-SIM handset (the TECNO KN4 test).
- Scheduling does not use exact alarms: WorkManager may fire a few minutes late under Doze/battery saver.
- Early-wake edge case: if the clock jumps and the job runs more than 60 s early it no-ops until the next app start (reconcile).
- The status receiver still keys partial-delivery state in SharedPreferences.
- Settings dashboard is still the earlier layout (Phase 3).

## Phase 1 (this delivery): notifications, read state, vault hardening

Source written, NOT compiled (no Android SDK/Gradle/network in the authoring environment). The first GitHub Actions run is the real compile check; fix the first compiler error before anything else.

- Added notification system: channels (Messages, Private messages), grouping with summary, MessagingStyle accumulation, Reply (RemoteInput) and Mark as read actions, POST_NOTIFICATIONS handling, lock-screen preview setting (default: hidden).
- Private conversations: notifications show no sender/number/text, have no actions, and tap intents carry no address. Locking a conversation cancels its existing notification.
- Duplicate notifications prevented: ingest() now returns null for duplicates; no notification while that conversation is visible.
- Added read/unread state (Room column `isRead`, provider READ column synced both ways) and unread indicators in the conversation list.
- Room migration 4→5: `isRead` (existing rows = read) and durable `vaulted_numbers` table seeded from existing private rows. Schema export enabled (`app/schemas/` appears after first build; commit it).
- Vault fixes: (1) vault status no longer disappears when the last private message is deleted (previously the next SMS from that number would be stored in plaintext in the system provider); (2) duplicate detection for private messages compares decrypted text (ciphertext never matched because of random IVs); (3) changing the PIN now requires the current PIN; (4) PIN lockout escalates (30 s doubling to 1 h) and the UI shows the wait time.
- Entry routes: notification taps and sms:/smsto: intents share one path through the vault check; intents are no longer replayed after rotation/process recreation; repeated identical requests re-trigger via a nonce.
- Search: LIKE wildcards (% and _) are escaped.

### Known gaps after Phase 1
- Per-conversation channels, custom sounds and vibration: Phase 3 (sound lives on the channel on Android 8+).
- Manual mark-as-unread, bulk select/delete/archive: not yet.
- Vault PIN lockout uses the wall clock (changing the device clock can shorten it); biometric success is a UI gate, not key-bound.
- Vault state changes (lock/unlock) are not a single database transaction.
- MMS notifications: Phase 6. Notification icon is a framework placeholder; the app has no launcher icon yet.
- Full provider sync still rescans everything (incremental sync is pending).

## Latest source changes

- Preserved the supplied Kotlin/Compose/Room application structure and package.
- Preserved the `sms.db` database filename and non-destructive 1→2 migration.
- Added Room migration 2→3 and a separate drafts table.
- Added draft autosave/restore; vault drafts are encrypted alongside vault messages.
- Added copy, forward, star/unstar and individual message deletion actions.
- Added confirmed conversation deletion with best-effort removal of provider rows owned by the app.
- Added Room-backed scheduled-message records, a 3→4 migration, queued/retrying list in Settings, cancellation, and worker status updates.
- Retained prior provider-ID deduplication, sender normalization, multipart status aggregation and vault provider-cleanup improvements.

## Validation status

| Check | Result |
|---|---|
| ZIP extraction | Passed |
| XML parse | Pending final validation |
| Kotlin syntax scan | Pending final validation |
| Gradle assembleDebug | Not certified; dependency/distribution access was unavailable |
| GitHub Actions | Not run |
| TECNO KN4 / Android 15 install | Not run |
| Carrier SMS / MMS | Not tested |
| Vault / biometric / lifecycle QA | Not tested |
| Room 1→2→3 migration against real user DB | Not tested |

## Still incomplete

- Full MMS transactions, sending, downloading, attachment persistence and carrier/APN compatibility.
- Dual-SIM selection/subscription-aware send path.
- Full scheduled message list/edit/reschedule/cancel UI and robust duplicate-send guarantees.
- Full theme, accent, bubble, font and per-contact notification preferences.
- Notification channels, reply/action handling and privacy controls.
- Full backup/restore and export/import with explicit vault handling.
- Contact photos and native contact create/edit flow.
- Full search, bulk actions, read/unread, group SMS and richer delivery/read receipts.
- Automated unit/instrumentation coverage and physical-device acceptance testing.

Do not treat source presence as proof of production readiness. See `RELEASE_READINESS.md`.
