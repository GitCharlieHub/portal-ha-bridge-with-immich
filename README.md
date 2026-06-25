# Portal HA Bridge — ImmichFrame Edition

A fork of [portal-ha-bridge](https://github.com/RoadRunner-1024/portal-ha-bridge) that embeds an ImmichFrame photo-slideshow WebView directly into the app, so the Portal's camera stays available to Home Assistant / Frigate **while** beautiful photos play on the screen.

## Why this fork?

Meta Portal restricts camera access to the foreground app. The upstream portal-ha-bridge is a background service — it loses the camera whenever the Portal launcher (or another app) takes the foreground.

**This fork keeps portal-ha-bridge permanently in the foreground** by displaying ImmichFrame inside its own WebView. The bridge service, MQTT, camera streaming, motion detection, and all HA entities keep running uninterrupted.

## What is ImmichFrame?

[ImmichFrame](https://github.com/immichframe/ImmichFrame) is a web-based digital photo frame that connects to your [Immich](https://immich.app/) photo server and shows a slideshow. You access it through a browser via its URL (e.g. `http://192.168.1.10:3000`).

## Setup

### 1. Install ImmichFrame

Run ImmichFrame on a server on your local network. Note its URL.

### 2. Install this app

Build and install the APK via ADB, or use Android Studio.

### 3. One-time ADB setup (same as upstream)

Connect the Portal by USB to a computer and run:

```bash
# Required for camera access
adb shell pm grant com.aeonos.portalha android.permission.CAMERA

# Required for sound level sensor
adb shell pm grant com.aeonos.portalha android.permission.RECORD_AUDIO

# Required for screen brightness control
adb shell appops set com.aeonos.portalha WRITE_SETTINGS allow

# Required for SYSTEM_ALERT_WINDOW (keeps camera accessible when another app is briefly shown)
adb shell appops set com.aeonos.portalha SYSTEM_ALERT_WINDOW allow

# Optional: auto-enable the accessibility service (needed for screen sleep command)
adb shell pm grant com.aeonos.portalha android.permission.WRITE_SECURE_SETTINGS

# Optional: portal presence detection (reads Meta's own face detection from logcat)
adb shell pm grant com.aeonos.portalha android.permission.READ_LOGS
```

### 4. Configure the app

Open the app and go to **Settings**:

- **MQTT Broker**: enter your Home Assistant MQTT broker address and credentials
- **Device name**: how the device appears in HA (default: Portal)
- **Home Assistant Dashboard URL**: the HA dashboard to show when in HA mode
- **ImmichFrame Photo Slideshow**: enable and enter your ImmichFrame server URL

Tap **Save & Restart Service**.

### 5. Switch between modes

Swipe from the left edge of the screen to open the drawer, then:

- **"HA Dashboard"** button → switch to the HA dashboard view
- **"Photo Frame"** button → switch back to ImmichFrame

The toggle button only appears when both URLs are configured.

## Home Assistant entities

All entities from upstream are published under `portal/<device_id>/`:

| Entity | Type | Description |
|---|---|---|
| Screen | switch | Wake / sleep the screen |
| Ambient Light | sensor | Lux reading |
| Temperature | sensor | Die temperature (Portal+ only) |
| Temperature Offset | number | Calibration offset in °C |
| Tap/Tilt | sensor | Gesture direction |
| Tap Sensitivity | number | Threshold for tap/tilt detection |
| Sound Level | sensor | Ambient 0–100% |
| Mic Mute | switch | Mute/unmute the microphone |
| Volume | number | Media volume 0–100 |
| Volume Mute | switch | Mute/unmute media volume |
| Doorbell | button | Play doorbell chime |
| Alert | button | Play alert beep |
| Brightness | number | Screen brightness 0–100 |
| IP Address | sensor | Current Wi-Fi IP |
| Camera | switch | Turn camera on/off |
| Motion | binary_sensor | Motion detected |
| Motion Detection | switch | Enable/disable motion feature |
| Motion Sensitivity | number | 1=sensitive, 100=firm |
| Camera Streaming | switch | Enable/disable RTSP stream |
| Portal Presence | binary_sensor | Face detected (READ_LOGS required) |
| Presence Detection | switch | Enable/disable presence feature |
| Screen Timeout | switch | Auto-sleep when idle |
| Screen Timeout Minutes | number | Minutes before sleep |

## Camera streaming (Frigate)

With **RTSP Streaming** enabled in Camera Settings:

```yaml
# Frigate config example
cameras:
  portal:
    ffmpeg:
      inputs:
        - path: rtsp://192.168.1.x:8554/
          roles: [detect, record]
```

Or in Home Assistant with the WebRTC card:

```yaml
type: custom:webrtc-camera
url: 'ffmpeg:rtsp://192.168.1.x:8554/#video=copy'
```

## Differences from upstream

- `DashboardActivity`: ImmichFrame/HA dual-mode WebView with drawer toggle
- `MainActivity`: ImmichFrame URL + enable switch added to Settings
- `Prefs`: `immichFrameUrl` and `immichFrameEnabled` fields added
- `BridgeService`: written from scratch based on upstream architecture (same MQTT topics, same HA entities)
- All other Kotlin files: identical to upstream

## License

Same as upstream: [PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/)
