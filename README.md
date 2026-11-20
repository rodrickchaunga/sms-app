# SMS App V2.0

Kotlin + Jetpack Compose Android SMS application using Room, WorkManager, Android's SMS provider and Android Keystore-backed vault encryption.

## Project setup

- Android Studio project: `sms-app/`
- Application ID: `com.example.smsapp`
- Minimum Android: API 26
- Compile / target SDK: 35
- Java / Kotlin target: 17
- Android Gradle Plugin: 8.6.1
- Gradle wrapper: 8.7

## Build

```bash
./gradlew --no-daemon clean assembleDebug
```

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

## Main source areas

- `data/`: Room entities, DAO, migrations and SMS repository
- `security/`: PIN handling and AES-GCM vault cryptography
- `receiver/`: SMS delivery, status and WAP push receiver declarations
- `schedule/`: WorkManager scheduled-SMS worker
- `ui/`: Compose screens and navigation
- `.github/workflows/`: CI debug APK workflow

## Database safety

The Room database filename remains `sms.db`. Migration 1→2 adds message metadata and migration 2→3 adds a separate drafts table and migration 3→4 adds tracked scheduled-SMS records. The project does not use destructive migration fallback. Keep a backup of important data before testing upgrades.

## Privacy

Private message bodies are encrypted in the Room database. Private conversation content should not be sent to notifications or logs. The private space is opened only by long-pressing the new-conversation button. Android SMS provider rows are removed when a thread is placed in the vault. Cloud backup is disabled to avoid accidental plaintext leakage. Vault protection is not forensic-grade and cannot protect a rooted or compromised device.

## Current limitations

This source package is still under development. Full MMS sending/receiving, dual-SIM selection, complete notification controls, full backup/export/import, full scheduled-message management, and physical-device certification are not complete. See [RELEASE_READINESS.md](RELEASE_READINESS.md) and [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md).

## Termux and GitHub

See [TERMUX_GITHUB.md](TERMUX_GITHUB.md) for the GitHub upload and Actions workflow.
