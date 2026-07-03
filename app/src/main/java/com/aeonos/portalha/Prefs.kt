package com.aeonos.portalha

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

class Prefs(private val context: Context) {
    private val sp = context.getSharedPreferences("portal_ha", Context.MODE_PRIVATE)

    // The service updates prefs in response to HA commands (camera on/off,
    // feature cascades); UI screens register here to stay in sync live.
    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    var brokerHost: String
        get() = sp.getString("broker_host", "homeassistant.local") ?: "homeassistant.local"
        set(v) = sp.edit().putString("broker_host", v).apply()

    var brokerPort: Int
        get() = sp.getInt("broker_port", 1883)
        set(v) = sp.edit().putInt("broker_port", v).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    var deviceName: String
        get() = sp.getString("device_name", "Portal") ?: "Portal"
        set(v) = sp.edit().putString("device_name", v).apply()

    val deviceId: String
        get() {
            val existing = sp.getString("device_id", null)
            if (existing != null) return existing
            val new = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: java.util.UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString("device_id", new).apply()
            return new
        }

    var tapThreshold: Float
        get() = sp.getFloat("tap_threshold", 4.0f)
        set(v) = sp.edit().putFloat("tap_threshold", v).apply()

    // Camera feature toggles. The legacy "camera_enabled" key seeds the defaults
    // so existing installs keep their behavior after upgrading.
    var motionEnabled: Boolean
        get() = sp.getBoolean("motion_enabled", sp.getBoolean("camera_enabled", false))
        set(v) = sp.edit().putBoolean("motion_enabled", v).apply()

    var streamEnabled: Boolean
        get() = sp.getBoolean("stream_enabled", sp.getBoolean("camera_enabled", false))
        set(v) = sp.edit().putBoolean("stream_enabled", v).apply()

    // Master switch for the entire camera service (app-only, not exposed to HA).
    // When off: no camera infrastructure, no HA camera/motion entities, and
    // camera commands from HA are ignored.
    var cameraServiceEnabled: Boolean
        get() = sp.getBoolean("camera_service_enabled", motionEnabled || streamEnabled)
        set(v) = sp.edit().putBoolean("camera_service_enabled", v).apply()

    // Consumers active when the camera was last turned off — turning the camera
    // back on restores them (camera off switches motion/streaming off; camera on
    // brings back what was running before).
    var lastMotionEnabled: Boolean
        get() = sp.getBoolean("last_motion_enabled", true)
        set(v) = sp.edit().putBoolean("last_motion_enabled", v).apply()

    var lastStreamEnabled: Boolean
        get() = sp.getBoolean("last_stream_enabled", true)
        set(v) = sp.edit().putBoolean("last_stream_enabled", v).apply()

    // Desired camera on/off state — survives app restarts and reboots so the
    // camera comes back without relying on retained MQTT commands.
    var cameraOn: Boolean
        get() = sp.getBoolean("camera_on", false)
        set(v) = sp.edit().putBoolean("camera_on", v).apply()

    var motionSensitivity: Int
        get() = sp.getInt("motion_sensitivity", 20)
        set(v) = sp.edit().putInt("motion_sensitivity", v).apply()

    // Calibration offset (deg C) added to the ambient-temperature reading before
    // publishing. The Portal+ sensor is an accelerometer die-temp sensor with a
    // per-chip bias, so this lets the user dial it to a real thermometer.
    var tempOffset: Float
        get() = sp.getFloat("temp_offset", 0f)
        set(v) = sp.edit().putFloat("temp_offset", v.coerceIn(-20f, 20f)).apply()

    // Manual stream rotation in degrees (0/90/180/270), cycled from the app.
    var streamRotation: Int
        get() = sp.getInt("stream_rotation", 0)
        set(v) = sp.edit().putInt("stream_rotation", v).apply()

    // Portal presence — reads Meta's own face-presence detection by tailing
    // logcat (needs READ_LOGS via adb). Published to HA as a binary_sensor.
    var presenceEnabled: Boolean
        get() = sp.getBoolean("presence_enabled", false)
        set(v) = sp.edit().putBoolean("presence_enabled", v).apply()

    // On-device screen-off timer (independent of HA). When enabled, the screen
    // sleeps after this many minutes with no presence / no wake. Disabled = the
    // screen stays on indefinitely.
    var screenTimeoutEnabled: Boolean
        get() = sp.getBoolean("screen_timeout_enabled", false)
        set(v) = sp.edit().putBoolean("screen_timeout_enabled", v).apply()

    var screenTimeoutMinutes: Int
        get() = sp.getInt("screen_timeout_minutes", 5)
        set(v) = sp.edit().putInt("screen_timeout_minutes", v.coerceIn(1, 240)).apply()

    var haUrl: String
        get() = sp.getString("ha_url", "") ?: ""
        set(v) = sp.edit().putString("ha_url", v).apply()

    var immichFrameEnabled: Boolean
        get() = sp.getBoolean("immich_frame_enabled", false)
        set(v) = sp.edit().putBoolean("immich_frame_enabled", v).apply()

    var immichFrameUrl: String
        get() = sp.getString("immich_frame_url", "") ?: ""
        set(v) = sp.edit().putString("immich_frame_url", v).apply()

    val brokerUri: String get() = "tcp://$brokerHost:$brokerPort"
}
