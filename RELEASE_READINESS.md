# SMS App V2.0 — Release readiness and remaining engineering work

## Changes in this delivery

- Added Room `DraftEntity` storage with a non-destructive 2→3 migration.
- Added automatic draft restore and delayed autosave in conversations.
- Draft bodies are encrypted when the associated conversation is in the vault; vault/unvault operations migrate the draft representation too.
- Added copy, forward, star/unstar and delete actions for individual messages.
- Added confirmed conversation deletion, including deletion of app-owned SMS provider rows for non-vault conversations.
- Added Room-backed scheduled SMS records, queue visibility in Settings, cancellation and worker status updates.
- Preserved the existing application ID, package, Room database filename and 1→2 migration.

## Status: implemented in source, not device-certified

| Capability | Current state |
|---|---|
| Compose UI, Room, contacts, basic SMS send/receive | Existing foundation retained |
| Historical provider import | Existing full provider scan retained; still needs large-inbox/device testing |
| Draft persistence | Added in this delivery |
| Copy / forward / star / individual delete | Added in this delivery |
| Conversation delete | Added in this delivery; provider cleanup requires default-SMS role |
| Vault PIN throttling / AES-GCM / biometric prompt / FLAG_SECURE | Existing implementation retained; security review and device tests required |
| Scheduled SMS | Queue/list/cancel/status tracking implemented; edit/reschedule and device QA remain |
| MMS | Receiver declaration exists, but full MMS transaction/APN/attachment implementation is not present |
| Dual SIM | Not implemented |
| Full theme/accent/bubble/font customization | Not implemented; current personalization controls are partial |
| Notification channels/actions/per-contact sounds | Not implemented |
| Backup/export/import | Not implemented; backups remain disabled to avoid leaking SMS/vault data |
| Full read receipts / message read state / bulk actions | Not implemented |
| Contact photo/create/edit integration | Partial / delegated to Android Contacts |
| GitHub Actions APK build | Workflow included; must run on GitHub to verify |

## Build validation

A successful build has **not** been certified in the authoring environment because Gradle distribution/dependency resolution was unavailable. Run:

```bash
./gradlew --no-daemon clean assembleDebug
```

If this fails, capture the complete first compiler error and fix it before installing the APK. A source parse check is not a Gradle build.

## Critical test matrix before relying on the app

1. Build using JDK 17 and Android Gradle Plugin 8.6.1.
2. Install on a spare/test handset, grant runtime permissions, and explicitly set the app as default SMS.
3. Test import against a test inbox, including sent/inbox messages and a large history.
4. Test incoming SMS while foregrounded, backgrounded, process-killed and after reboot.
5. Test one-part and multipart SMS, no service, invalid destination, and delivery reports.
6. Test vault lock/unlock with national/international/formatted variants of the same number.
7. Test Home, Back, screen-off, recents, app process death and external `sms:` / `smsto:` intents.
8. Confirm private messages and drafts do not appear in shared provider rows, logs, notifications, backups or exports.
9. Test migrations from a copy of a real v1 and v2 database; never test migrations against the only copy of important messages.
10. Test scheduled send/reboot/delay/failure behavior.
11. Test MMS and dual-SIM only after their implementations are added.
12. Verify the GitHub Actions artifact installs and runs.

## Security limitations

This app does not provide forensic-grade protection. Rooted or compromised devices, malicious accessibility services, OS-level compromise, SIM/carrier systems, screenshots taken by external hardware, and previously exported/provider-copied messages are outside the protection boundary. Do not store irreplaceable messages only in an untested development build.

## Release decision

This ZIP is a further-developed source package, **not a certified production release**. MMS, dual-SIM, complete notification controls, full backup/export, and device QA remain release blockers.
