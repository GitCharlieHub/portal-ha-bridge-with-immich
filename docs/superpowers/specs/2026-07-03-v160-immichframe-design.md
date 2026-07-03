# Portal HA Bridge v1.6.0 with ImmichFrame Design

## Goal

Keep the basic functionality and proven Meta Portal camera behavior of Portal HA
Bridge v1.6.0 while displaying ImmichFrame inside the app's foreground dashboard.

## Baseline

The implementation is based on the exact `v1.6.0` release from
`RoadRunner-1024/portal-ha-bridge`. Its camera, streaming, MQTT, sensor, display,
boot, and foreground-service behavior remain unchanged unless a compile-time
integration adjustment is strictly required.

Later upstream features are excluded. In particular, the implementation will
not include intercom, announce/broadcast controls, voice assistant tools,
hands-free audio, automatic updating, or later dashboard additions.

## ImmichFrame Integration

`DashboardActivity` remains the launcher and foreground Activity. Its existing
WebView gains two content modes:

- ImmichFrame, selected by default when enabled and configured.
- Home Assistant, available through the existing navigation drawer.

Both modes load in the same WebView and Activity. Opening ImmichFrame must not
launch another application, replace the foreground Activity, restart the bridge
service, or change camera ownership.

`Prefs` stores an enabled flag and URL for ImmichFrame. `MainActivity` adds the
matching settings controls. The dashboard falls back to Home Assistant when
ImmichFrame is disabled or has no URL.

## Camera And Lifecycle Contract

The following v1.6.0 classes retain their original implementation and role:

- `BridgeService`
- `CameraStream`
- `RtspStreamer`
- `MjpegServer`
- `MotionDetector`
- `MediaKeepAlive`
- `BootReceiver`

The existing calls from Activity lifecycle methods into `BridgeService` also
remain in place. ImmichFrame mode must not introduce a second camera path,
overlay workaround, polling restart, or alternate foreground service.

## WebView Security

ImmichFrame receives no camera or microphone WebView permissions. Home
Assistant media permission requests are allowed only when Home Assistant mode
is active and the requesting origin matches the configured Home Assistant
origin.

HTTP and HTTPS navigation remain supported for local installations. External
Android intent launches are restricted to the behavior needed by the v1.6.0
Home Assistant dashboard and are not expanded for ImmichFrame.

## Verification

Automated checks will verify that:

- The v1.6.0 camera and service sources remain identical to the release tag.
- ImmichFrame preference and dashboard mode behavior work as specified.
- ImmichFrame cannot receive WebView camera or microphone permission.
- The Android project builds successfully.

Device acceptance testing on the Meta Portal will verify that RTSP remains live
while ImmichFrame is visible and while the app's settings page is visible.
