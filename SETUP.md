# Portal HA Bridge — Setup

Exposes a **Home Assistant switch entity** for the Portal's screen. Send `OFF` to sleep it, `ON` to wake it. Runs as a persistent foreground service; starts automatically on boot.

## Requirements

- Meta Portal with ADB enabled (USB or network)
- Home Assistant with an MQTT broker (Mosquitto add-on recommended)

## Installation

### 1. Build or download the APK

Build with Android Studio / Gradle, or grab the latest release APK.

### 2. Install via ADB

```bash
adb install portal-ha-bridge.apk
```

### 3. Grant permissions

**Fastest (Windows, device on USB):** run the provisioner — it installs the APK and grants everything, prompting for the two optional adb-only features (screen sleep, presence):

```powershell
.\provision-portal.ps1            # interactive
.\provision-portal.ps1 -AssumeYes # unattended / bulk
```

**Or, no computer needed for most of it:** open the app and tap **Grant Missing Permissions**, then keep tapping until the status box is all ✓. This handles **everything except screen sleep with no adb** — camera and microphone via permission dialogs; brightness and overlay are auto-granted on Portal.

**Screen sleep is the one exception.** Meta hides accessibility services from Portal's Settings, so sleep can't be enabled on-device. Run this once from a computer with the Portal connected (the app shows this command with a Copy button when needed):

```bash
adb shell pm grant com.aeonos.portalha android.permission.WRITE_SECURE_SETTINGS
```

The app then enables its own accessibility service automatically. **Everything else works without adb** — if you don't need HA-controlled screen sleep, you can skip adb entirely.

**Optional full adb setup** (e.g. provisioning many devices) — grants everything silently:

```bash
adb shell pm grant com.aeonos.portalha android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.aeonos.portalha android.permission.RECORD_AUDIO
adb shell pm grant com.aeonos.portalha android.permission.CAMERA
adb shell appops set com.aeonos.portalha WRITE_SETTINGS allow
adb shell appops set com.aeonos.portalha SYSTEM_ALERT_WINDOW allow
```

### 4. Configure via the app

Open **Portal HA Bridge** on the Portal and fill in:

| Field | Value |
|-------|-------|
| Host | IP or hostname of your MQTT broker |
| Port | 1883 (default) |
| Username / Password | Your MQTT credentials |
| Device name | Name shown in Home Assistant (e.g. "Living Room Portal") |

Tap **Save & Restart Service**.

### 5. Home Assistant

The app publishes MQTT auto-discovery — the entity appears automatically in HA under the device name you set.

- **Turn off** → screen sleeps (tap anywhere to wake manually)
- **Turn on** → screen wakes

Topics (for manual use or automations):

| Purpose | Topic |
|---------|-------|
| Command | `portal/<device_id>/screen/command` |
| State | `portal/<device_id>/screen/state` |
| Discovery | `homeassistant/switch/<device_id>_screen/config` |

The device ID is shown in the app's status area.

## Immortal Store catalog entry

```json
{
  "name": "Portal HA Bridge",
  "packageName": "com.aeonos.portalha",
  "source": "url",
  "apkUrl": "https://github.com/YOUR_USER/portal-ha-bridge/releases/latest/download/portal-ha-bridge.apk",
  "minSdk": 29,
  "description": "Home Assistant MQTT bridge — sleep/wake the Portal screen from HA automations",
  "author": "YOUR_NAME",
  "homepage": "https://github.com/YOUR_USER/portal-ha-bridge"
}
```

## How sleep/wake work

| Action | Mechanism |
|--------|-----------|
| **Wake** | `PowerManager.FULL_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP` — standard `WAKE_LOCK` permission only |
| **Sleep** | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` — no device admin |

The Portal has no keyguard, so lock = screen blank, same behaviour as Immortal's `lockNow()` without needing device admin.
