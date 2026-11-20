# Build and upload SMS App V2.0 from Termux

## 1. Install tools

```bash
pkg update && pkg upgrade
pkg install git unzip openjdk-17
java -version
```

Use GitHub Actions for the Android build if the handset does not have a complete Android SDK/Gradle environment. Termux itself does not automatically include the Android SDK.

## 2. Extract the project

Place the ZIP in a directory accessible to Termux, then:

```bash
termux-setup-storage
cd ~/storage/downloads
unzip SMS-App-V2.0-Android-Studio.zip
cd sms-app
```

## 3. Upload to a new GitHub repository

Create an empty repository on GitHub first. Then:

```bash
git init
git add .
git commit -m "Prepare SMS App V2.0 source"
git branch -M main
git remote add origin https://github.com/YOUR-USERNAME/YOUR-REPOSITORY.git
git push -u origin main
```

Replace the two uppercase placeholders with your own repository details. Never put a GitHub token or password in source files or commit history.

## 4. Build APK with GitHub Actions

- Open the repository on GitHub.
- Select **Actions**.
- Open the Android Debug APK workflow.
- Run the workflow if it did not start automatically.
- Wait for the build to finish.
- Download the `app-debug` artifact from the successful run.

## 5. Local build (only if Android SDK is installed)

```bash
chmod +x ./gradlew
./gradlew --no-daemon clean assembleDebug
```

The APK should be at `app/build/outputs/apk/debug/app-debug.apk`.

## 6. Before installing

Test on a spare handset first. Grant permissions, set SMS App V2.0 as the default SMS app, and test SMS send/receive. Do not uninstall the previous SMS app or clear its data as a shortcut; preserve message history.
