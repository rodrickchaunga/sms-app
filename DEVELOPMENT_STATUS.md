# SMS App V2.0 — development status

## Phase 4.3 (this delivery): undo send, failed-message options, quick schedule, Bin, settings cards

Not compiled in the authoring environment (no Android SDK): fix the first compiler error first. Room database version is now 11 (migration 10->11: `deletedAt` on `messages`).

1. Settings > Messaging > **Undo send delay** (Off / 3 / 5 / 10 / 15 / 30 s). After tapping send, a bar "Sending in Ns - UNDO" appears above the message box; tapping it stops the send and puts the text back in the box. Leaving the chat during the countdown sends the message straight away.
2. A message that failed to send shows **See options** (instead of Retry): Retry, Edit (text goes back into the box, failed copy removed), Delete.
3. **Long-press the send button** to schedule that text for that number (quick choices: in 1 hour, in 3 hours, tomorrow 8:00, or pick a date and time).
4. The new-conversation button opens the private space only after a **2 second hold** (`PRIVATE_SPACE_HOLD_MILLIS` in `ConversationListScreen.kt`). A tap still starts a chat.
5. **Bin** (conversation-list menu and Settings > Tools): deleting a whole ordinary conversation from the list, archive or chat menu moves it to the Bin. From there: restore, delete permanently, empty bin, and auto-delete after 30 days (switch on the Bin screen; swept on app start, when the Bin opens, and daily by `BinCleanupWorker`). Private conversations are never kept: deleting them erases them, and moving a number to the private space purges any of its messages already in the Bin. Single messages deleted inside an open chat are deleted for good and never reach the Bin.
6. Unread conversations: sender name and message preview are **bold**.
7. Settings are grouped into rounded **cards** (Messaging, Notifications, Messages and appearance, Tools, About).

## Phase 4.2 (this delivery): menu over the message, drafts, delivery ticks

- Long-press menu: the message is redrawn exactly where it was (same position and width) with the actions under it, on the same side; above it when there is no room below. Uses a Popup with the bubble's window bounds, which are recorded per visible bubble (a cheap assignment) and read only on long-press.
- Conversation list: an unsent draft shows as `Draft: <text>` (label in the error colour, text in italics) instead of the last message. A new chat with typed text but no messages also appears in the list. Private-space drafts are not shown in the main list (they stay encrypted).
- Conversation list: when my message is the latest, a circle under the time shows its state: empty circle = sending, one tick = sent, two ticks = delivered (needs delivery reports on), `!` = failed or delivery failed. The unread badge shows instead when the latest message is received.
- No database schema change (new columns are query results only).

---

## Phase 4.1 (previous): speed fix + long-press menu

- Typing lag: the draft text was read in the body of `ConversationScreen`, so every keystroke recomposed the whole chat (all visible bubbles). It is now read through `snapshotFlow` (draft saving) and inside the message box only.
- Scrolling: removed the per-bubble gradient and hairline edge added in 4.0; date formatters are cached instead of rebuilt for every list row. Light font weights and the soft "Texting with" strip stay (they cost nothing per frame).
- Long-press on a message opens a menu (Forward, Copy, Star, Delete, Select more, Info) on the side the message came from, over a dimmed chat, blurred on Android 12+. Info opens the Details screen. The menu is vertically centred (it is not anchored over the original message). Delete asks for confirmation as before.

---

## Phase 4.0 (previous): light decoration

Lightweight only (no blur, shadows or animations): lighter font weights (titles Medium, unread names SemiBold, unread preview/time/badge Medium instead of Bold), chat bubbles get a 6% lighter top fading to the bubble colour plus a hairline edge, the "Texting with / Sending with" block sits on a soft strip that fades at both ends, list rows have slightly more vertical padding. Scroll position is kept on back from a chat (list) and from Details (chat); the SIM carrier shows on the tapped date/time line.

---

## Phase 3.9 (previous): details screen, sender notice, SIM labels, private-number fix

Not compiled in the authoring environment (no Android SDK): fix the first compiler error first. Room database version is now 10 (migration 9->10: `subscriptionId` on `messages`).

1. Long-press a message opens a Details screen: the message, Status (Received/Sent, or Sent/Delivered for your own messages; Status/Time for failed ones), Type, From/To, SIM, and the message actions (Retry, Select, Copy text, Forward, Star, Delete).
2. Senders you cannot reply to (names such as MPAMBA or TNM Promos) replace the message box with "Sender can't accept replies. Contact them directly. Learn more"; Learn more opens the short-code explanation with OK.
3. Top of a chat (and a brand-new chat): "Texting with <name> (SMS/MMS)" and "Sending with <carrier>"; on dual-SIM phones the carrier is a link that opens the SIM picker. The old "start of a new conversation" text is gone.
4. Each message records the SIM it arrived on or was sent from. On dual-SIM phones the carrier appears on the same line as the date/time when a message is tapped ("21/11/2026, 7:08 am · Airtel") and disappears with it. Older messages have no label.
   Back-navigation keeps scroll position: the conversation list returns to where you were after opening a chat, and a chat returns to the same message after Details/Conversation details.
5. Date headers: `Yesterday • ...`, weekday for the last week, `Friday, 6 Nov • 9:26 pm` after that.
6. Private numbers saved as 099... or 088...: a message that arrives as +265... is now stored under the saved address, so it shows in that private chat. Opening the private space also moves earlier messages that were stored under the +265... spelling onto the saved number.

---

## Phase 3.8 (this delivery): Google Messages look + 3 requested changes

Not compiled in the authoring environment (no Android SDK): fix the first compiler error first. No database change (version unchanged).

1. List preview: when the newest message in a conversation is mine the preview reads `You: <text>`; a received message shows just its text (`ConversationPreview.lastOutgoing`, selected in the four conversation queries).
2. Unknown-number popup: slides in under the header only when the newest message is one I received from an unsaved number, and hides itself after 8 seconds (or on X / Add contact / Block). Sending a message hides it, so typing and sending never triggers it.
3. `PrivateSpaceScreen.kt`: top-level `ListItemDefaults.colors(...)` value replaced by `@Composable getTransparentColors()` (the sed fix you supplied).
4. Dates: list rows show `21/11, 4:01 pm` (year added for older years); chats get date headers (`11:36 am` today, `Yesterday • ...`, `Monday • ...`, `12 Nov • ...`) on a new day or after a 1 hour gap (`ChatTime.SEPARATOR_GAP_MS`), and an `Unread` line above the first message that was unread when the chat was opened.
5. List screen: lifted top bar with rounded content sheet, round avatars (letter for saved contacts, person icon for everyone else), unread count badge under the time, no divider lines, `Start chat` button (long-press still opens the private space), overflow menu: Mark all as read, Archived, Spam and blocked, Personalize, Settings. The bottom tab bar is gone; Personalize and Settings open from the menu with a back arrow.
6. Chat screen: avatar in the header, no avatar beside bubbles, pill message box with `<carrier> • Text message` hint, round send button.
7. Theme: default accent is peach with warm tinted dark/light surfaces and rounded bubbles. Anyone who already chose an accent or bubble style keeps it.
8. New `SpamScreen.kt` (list of blocked numbers with Unblock). `Mark all as read` leaves private-space conversations untouched.

Not included: Bin and Help and feedback (no recycle bin or help screen exists yet), per-message SIM label under the last message (needs a new column), emoji/image/voice buttons in the message box (no feature behind them).

---

## Phase 3.7 (this delivery): 8 upgrades

Not compiled in the authoring environment (no Android SDK): fix the first compiler error first. Room database version is now 9 (migration 8->9: `isPinned` on `conversation_prefs`).

1. Unknown numbers: a banner under the chat header ("Unknown number", Add contact / Block number) when an unsaved phone number has messaged you. "Add to contacts" also sits beside Call in the contact details. Both disappear once the number is saved (re-checked when you return to the chat).
2. Phone numbers inside messages are underlined: tap opens the dialer, long-press copies the number (no info dialog). Only number-like text counts (international, local with leading 0, grouped or plain 10 digits); codes, amounts, dates and references are left alone. Tapping anywhere else on the bubble behaves as before.
3. A message that arrives on a SIM sets that conversation's SIM to it (dual-SIM phones only). The app-wide default SIM is untouched.
4. Swipe actions need a deliberate pull (at least about 40% of the screen width); light touches and quick flicks do nothing.
5. `ContactEntry` removed from `NewConversationScreen.kt` (contacts are plain name/number pairs).
6. Unread conversations are highlighted (tinted row, bold name, coloured time, bold preview).
7. The message box is disabled for sender names such as AIRTEL or TNM ("You can't reply to this sender"). Numeric short codes can still be replied to. The call button is hidden for them too.
8. Pin conversations: pin/unpin from the chat menu or from bulk selection in the list. Pinned conversations stay at the top (newest first) and show a pin icon.

---

## Phase 3.6 (this delivery): 12 upgrades

Not compiled in the authoring environment (no Android SDK): fix the first compiler error first. No database change in this delivery.

1. Settings > Archived conversations: list, open, unarchive, delete (long-press to select).
2. Conversation list: swipe left to archive, swipe right to delete (asks first).
3. Switch SIM moved from the top bar to the message box, next to Send. It shows only on dual-SIM phones and hides once you start typing. The picker lists only the SIM cards (no "system default" / "app default" rows).
4. Counter near Send: appears when 10 or fewer characters are left in the current SMS, then shows `remaining/parts` (e.g. 160/2) like Google Messages. Still follows the Settings switches.
5. "← Back" text buttons replaced by a back arrow.
6. Jump-to-latest button in a chat; shows only after scrolling up.
7. Composer capitalises the first letter of each sentence.
8. Message bubbles are limited to 75% of the screen width.
9. Fingerprint / device unlock is requested automatically when the private-space PIN dialog opens (if enabled and available).
10. Keyboard no longer pushes the header off screen (`adjustResize` plus IME padding in the chat).
11. A new conversation with no messages shows "This is the start of a new conversation. Say something nice!". Going back without sending leaves no empty conversation in the list.
12. Failed messages show "Retry" (under the message, and in message info). Retry re-sends in place, with a fresh time, clearing the old failed state.

---

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
