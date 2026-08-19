# AutoAcceptADB ⚡

[![Android Build APK](https://github.com/scsonic/AutoAcceptADB/actions/workflows/build-apk.yml/badge.svg)](https://github.com/scsonic/AutoAcceptADB/actions/workflows/build-apk.yml)
[![Platform](https://img.shields.io/badge/Platform-Android%205.0%2B%20(API%2021%2B)-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

An intelligent, lightweight Android application designed for headless devices, remote hardware testing racks, automated test benches, and devices with broken touchscreens. 

**AutoAcceptADB** automatically intercepts the system **"Allow USB Debugging?"** or **"Allow Wireless Debugging?"** authorization dialog, auto-checks *"Always allow from this computer"*, and clicks **"Allow / OK"** — entirely hands-free and without requiring Root access.

---

## 🌟 Key Features

- **Hands-Free ADB Authorization (No Root Required)**: Utilizes the native Android `AccessibilityService` to inspect the UI window hierarchy and interact with system authorization prompts.
- **Strict ADB Keyword & Anti-Misclick Filtering**:
  - Targets only genuine ADB authorization prompts across English, Traditional Chinese, and Simplified Chinese.
  - Matches keywords such as `USB debugging`, `Wireless debugging`, `RSA key fingerprint`, `Allow USB debugging`, `允許 USB 偵錯`, `允许 USB 调试`, `RSA 金鑰`, etc.
  - **Safety Blacklist**: Never interacts with destructive or unrelated prompts (e.g., app uninstallation, factory reset, data clearing, device formatting).
- **Configurable Click Delay**: Customize how long to wait before clicking allow (`0s`, `1s`, `2s` [default], `3s`, `5s`) directly from the clean dashboard.
- **Visual Feedback & 5s Cooldown**:
  - Displays a global Toast notification on successful authorization: `🎉 AutoAccept ADB: Debugging Authorized!`.
  - Implements a 5-second debounce cooldown after each acceptance to prevent duplicate triggers or event spamming.
- **Auto-Start on Boot**: Registers `RECEIVE_BOOT_COMPLETED` and maintains an optional low-overhead Foreground KeepAlive service to ensure the service persists after device reboots.
- **Zero-Lag & High Performance**: Clean interface optimized with event throttling (350ms) and lock state management to eliminate any Main Thread blocking or ANR (Application Not Responding) issues.
- **Interactive Simulation Test**: Includes a built-in mock ADB dialog within the app so you can immediately verify that the auto-clicker is active and working properly.

---

## 📱 Screenshots & Permissions

### Required Permissions
1. **Accessibility Service (`AdbAccessibilityService`)**: Essential for window event detection and automated button clicks.
2. **Ignore Battery Optimization**: Prevents OEM background task killers and Android Doze mode from killing the background service.
3. **Foreground Service & Notification**: Ensures the service maintains high process priority on Android 8.0+.
4. **Boot Completed**: Enables auto-run and service recovery upon device boot.

---

## 🛠️ Architecture & Project Structure

```
AutoAcceptADB/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml                  # Manifest declaring services, receivers, and permissions
│   │   ├── res/
│   │   │   ├── xml/accessibility_service_config.xml # Accessibility service definitions
│   │   │   ├── layout/activity_main.xml         # Modern Material 3 dashboard layout
│   │   │   └── values/                          # Strings, colors, styles
│   │   └── java/com/autoaccept/adb/
│   │       ├── MainActivity.kt                  # Setup wizard, status dashboard & mock test
│   │       ├── AdbAccessibilityService.kt       # Core automated detection & click logic
│   │       ├── KeepAliveService.kt              # Foreground persistence service
│   │       └── BootReceiver.kt                  # Device boot broadcast handler
│   └── build.gradle.kts
├── .github/workflows/
│   └── build-apk.yml                            # CI/CD pipeline building APK on push
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🚀 Getting Started

### 1. Build and Install via ADB
```bash
# Build debug APK
./gradlew assembleDebug

# Install to connected Android device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. First-Time Setup Wizard (One-Time Only)
1. Open the **AutoAccept ADB** app on your device.
2. Click **"⚡ Start Permission Setup Wizard"** to navigate to Android Accessibility Settings.
3. Locate **"AutoAccept ADB"** (or *AutoAccept ADB 授權服務*) and toggle it **ON**.
4. Allow Battery Optimization Whitelist and Notification permissions when prompted.

### 3. Verify Auto-Acceptance
- Tap the **"🧪 Show Simulation Dialog"** button inside the app.
- Watch the simulated USB debugging dialog appear, wait for your configured delay (e.g. 2s), auto-check *"Always allow"*, and automatically click *"Allow"*, followed by the success Toast notification!

---

## 🤖 CI / CD Pipeline

This repository includes a GitHub Actions workflow ([`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)) that:
- Triggers on every `push` and `pull_request` to the main branch.
- Sets up JDK 17, caches Gradle dependencies, and compiles the debug APK.
- Uploads the generated `app-debug.apk` as a workflow artifact.
- **Automatically prunes old workflow runs**, retaining only the **latest 3 builds** to save storage space and keep your actions clean.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
