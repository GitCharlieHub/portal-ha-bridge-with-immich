package com.aeonos.portalha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.Inet4Address
import java.net.NetworkInterface

class BridgeService : Service(), MqttCallbackExtended {

    companion object {
        private const val TAG = "PortalHA"
        private const val CHANNEL_ID = "portal_ha"
        private const val NOTIF_ID = 1

        private const val ACTION_START = "com.aeonos.portalha.START"
        private const val ACTION_STOP = "com.aeonos.portalha.STOP"
        private const val ACTION_ENSURE_CAMERA = "com.aeonos.portalha.ENSURE_CAMERA"
        private const val ACTION_SET_CAMERA = "com.aeonos.portalha.SET_CAMERA"
        private const val ACTION_SET_ROTATION = "com.aeonos.portalha.SET_ROTATION"
        private const val ACTION_APPLY_DISPLAY = "com.aeonos.portalha.APPLY_DISPLAY"

        private const val EXTRA_CAMERA_ON = "camera_on"
        private const val EXTRA_ROTATION = "rotation"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, BridgeService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BridgeService::class.java).setAction(ACTION_STOP))
        }

        fun ensureCamera(context: Context) {
            context.startService(
                Intent(context, BridgeService::class.java).setAction(ACTION_ENSURE_CAMERA))
        }

        fun setCamera(context: Context, on: Boolean) {
            context.startService(
                Intent(context, BridgeService::class.java)
                    .setAction(ACTION_SET_CAMERA).putExtra(EXTRA_CAMERA_ON, on))
        }

        fun setRotation(context: Context, rotation: Int) {
            context.startService(
                Intent(context, BridgeService::class.java)
                    .setAction(ACTION_SET_ROTATION).putExtra(EXTRA_ROTATION, rotation))
        }

        fun applyDisplaySettings(context: Context) {
            context.startService(
                Intent(context, BridgeService::class.java).setAction(ACTION_APPLY_DISPLAY))
        }

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { !it.isLoopback && it.isUp }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    private lateinit var prefs: Prefs
    private lateinit var mainHandler: Handler
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    @Volatile private var isStarted = false

    private var mqtt: MqttAsyncClient? = null
    private val mqttLock = Any()

    private var cameraStream: CameraStream? = null
    private var rtspStreamer: RtspStreamer? = null
    private var mjpegServer: MjpegServer? = null
    private var motionDetector: MotionDetector? = null
    private var sensorBridge: SensorBridge? = null
    private var soundMonitor: SoundMonitor? = null
    private var presenceMonitor: PresenceMonitor? = null
    private val mediaKeepAlive = MediaKeepAlive()
    private var overlayView: View? = null

    private var orientationListener: OrientationEventListener? = null
    private var lastAutoRotation = -1

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { ScreenControl.sleep() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            publish(HaDiscovery.stateTopic(prefs.deviceId), if (on) "ON" else "OFF", 0)
            if (on) resetScreenTimeout() else timeoutHandler.removeCallbacks(timeoutRunnable)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        installRtspCrashGuard()
        prefs = Prefs(this)
        mainHandler = Handler(Looper.getMainLooper())
        workerThread = HandlerThread("portal-ha-service").also { it.start() }
        workerHandler = Handler(workerThread.looper)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                startForeground(NOTIF_ID, buildNotification("Running"))
                if (!isStarted) {
                    isStarted = true
                    workerHandler.post { startAllServices() }
                }
            }
            ACTION_STOP -> {
                isStarted = false
                stopSelf()
            }
            ACTION_ENSURE_CAMERA -> workerHandler.post { ensureCameraState() }
            ACTION_SET_CAMERA -> {
                val on = intent.getBooleanExtra(EXTRA_CAMERA_ON, prefs.cameraOn)
                prefs.cameraOn = on
                if (!on) {
                    prefs.lastMotionEnabled = prefs.motionEnabled
                    prefs.lastStreamEnabled = prefs.streamEnabled
                    prefs.motionEnabled = false
                    prefs.streamEnabled = false
                } else {
                    if (!prefs.motionEnabled && !prefs.streamEnabled) {
                        prefs.motionEnabled = prefs.lastMotionEnabled
                        prefs.streamEnabled = prefs.lastStreamEnabled
                    }
                }
                workerHandler.post { applyCameraState() }
                publish(HaDiscovery.cameraStateTopic(prefs.deviceId), if (on) "ON" else "OFF", 0)
            }
            ACTION_SET_ROTATION -> {
                val rot = intent.getIntExtra(EXTRA_ROTATION, prefs.streamRotation)
                rtspStreamer?.let {
                    it.rotationOffset = rot
                    workerHandler.post { it.restart() }
                }
                cameraStream?.let { it.rotation = ((rot + lastAutoRotation.coerceAtLeast(0)) % 360) }
            }
            ACTION_APPLY_DISPLAY -> workerHandler.post { applyDisplaySettingsInternal() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isStarted = false
        timeoutHandler.removeCallbacks(timeoutRunnable)
        orientationListener?.disable()
        orientationListener = null
        runCatching { unregisterReceiver(screenReceiver) }
        workerHandler.post {
            stopCamera()
            sensorBridge?.stop(); sensorBridge = null
            soundMonitor?.stop(); soundMonitor = null
            presenceMonitor?.release(); presenceMonitor = null
            mjpegServer?.stop(); mjpegServer = null
            disconnectMqtt()
            mainHandler.post {
                mediaKeepAlive.stop()
                removeOverlay()
            }
        }
        workerThread.quitSafely()
        super.onDestroy()
    }

    // ── Service startup ───────────────────────────────────────────────────────

    private fun startAllServices() {
        mainHandler.post { mediaKeepAlive.start(this) }
        addOverlay()
        registerScreenReceiver()
        startSensors()
        startPresenceIfEnabled()
        connectMqtt()
        // Camera is NOT started here. DashboardActivity.onResume() calls
        // ensureCamera() once we are truly foreground — Portal blocks camera
        // from background processes, so attempting it here causes crashes.
        applyDisplaySettingsInternal()
        mainHandler.post { startOrientationListener() }
    }

    // ── Screen receiver ───────────────────────────────────────────────────────

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { registerReceiver(screenReceiver, filter) }
    }

    // ── SYSTEM_ALERT_WINDOW overlay (keeps process "visible") ─────────────────

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        mainHandler.post {
            if (overlayView != null) return@post
            runCatching {
                val wm = getSystemService(WindowManager::class.java)
                val params = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.START
                val v = View(this)
                wm.addView(v, params)
                overlayView = v
                Log.i(TAG, "overlay added (keeps camera accessible)")
            }.onFailure { Log.w(TAG, "overlay add failed: ${it.message}") }
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            runCatching { getSystemService(WindowManager::class.java).removeView(it) }
            overlayView = null
        }
    }

    // RTSP-Server 1.3.0 leaks an uncaught InterruptedException from its accept
    // thread when stopStream() is called. Swallow only that specific case so the
    // library bug doesn't kill the process; all other crashes propagate normally.
    private fun installRtspCrashGuard() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            if (ex is InterruptedException &&
                ex.stackTrace.any { it.className.contains("rtspserver", ignoreCase = true) }
            ) {
                Log.w(TAG, "swallowed RtspServer InterruptedException on ${thread.name}")
                return@setDefaultUncaughtExceptionHandler
            }
            existing?.uncaughtException(thread, ex)
        }
    }

    // ── Sensors ───────────────────────────────────────────────────────────────

    private fun startSensors() {
        val sb = SensorBridge(this) { topic, payload, qos -> publish(topic, payload, qos) }
        sb.start(prefs)
        sensorBridge = sb

        val sm = SoundMonitor(this) { level ->
            publish(HaDiscovery.soundStateTopic(prefs.deviceId), level.toString(), 0)
        }
        sm.start()
        soundMonitor = sm
    }

    // ── Presence ──────────────────────────────────────────────────────────────

    private fun startPresenceIfEnabled() {
        if (!prefs.presenceEnabled) return
        val id = prefs.deviceId
        val pm = PresenceMonitor { present ->
            publish(HaDiscovery.presenceStateTopic(id), if (present) "ON" else "OFF", 0)
            if (present) resetScreenTimeout() else scheduleScreenTimeout()
        }
        pm.start()
        presenceMonitor = pm
    }

    // ── MQTT ──────────────────────────────────────────────────────────────────

    private fun connectMqtt() {
        synchronized(mqttLock) {
            runCatching { mqtt?.disconnect() }
            val id = prefs.deviceId
            val client = try {
                MqttAsyncClient(prefs.brokerUri, "portal-ha-$id", MemoryPersistence())
            } catch (e: MqttException) {
                Log.w(TAG, "MQTT client create failed: ${e.message}")
                scheduleReconnect()
                return
            }
            client.setCallback(this)
            mqtt = client

            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                keepAliveInterval = 60
                connectionTimeout = 10
                isAutomaticReconnect = true
                if (prefs.username.isNotBlank()) userName = prefs.username
                if (prefs.password.isNotBlank()) password = prefs.password.toCharArray()
                setWill(HaDiscovery.stateTopic(id), "offline".toByteArray(), 1, true)
            }
            try {
                client.connect(opts, null, object : IMqttActionListener {
                    override fun onSuccess(token: IMqttToken?) {
                        Log.i(TAG, "MQTT connected to ${prefs.brokerUri}")
                    }
                    override fun onFailure(token: IMqttToken?, e: Throwable?) {
                        Log.w(TAG, "MQTT connect failed: ${e?.message}")
                        scheduleReconnect()
                    }
                })
            } catch (e: MqttException) {
                Log.w(TAG, "MQTT connect exception: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        workerHandler.postDelayed({ if (isStarted) connectMqtt() }, 20_000L)
    }

    private fun disconnectMqtt() {
        synchronized(mqttLock) {
            runCatching { mqtt?.disconnect() }
            mqtt = null
        }
    }

    // MqttCallbackExtended ────────────────────────────────────────────────────

    override fun connectComplete(reconnect: Boolean, serverURI: String) {
        Log.i(TAG, "MQTT connectComplete reconnect=$reconnect")
        workerHandler.post { onMqttConnected() }
    }

    override fun connectionLost(cause: Throwable?) {
        Log.w(TAG, "MQTT connection lost: ${cause?.message}")
    }

    override fun messageArrived(topic: String, message: MqttMessage) {
        val payload = message.toString().trim()
        workerHandler.post { handleCommand(topic, payload) }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit

    private fun onMqttConnected() {
        val id = prefs.deviceId
        val name = prefs.deviceName

        // Remove stale retained topics from old firmware versions
        HaDiscovery.staleTopics(id).forEach { t ->
            runCatching { mqtt?.publish(t, ByteArray(0), 1, true) }
        }

        // Clear stale retained commands — old builds published them with retain=true,
        // which causes the broker to replay them on every reconnect (e.g. screen=OFF
        // kills the camera right after startup).
        HaDiscovery.commandTopics(id).forEach { t ->
            runCatching { mqtt?.publish(t, ByteArray(0), 1, true) }
        }

        publishDiscovery(id, name)

        HaDiscovery.commandTopics(id).forEach { t ->
            runCatching { mqtt?.subscribe(t, 1) }
        }

        publishAllStates()
    }

    private fun publishDiscovery(id: String, name: String) {
        fun pub(topic: String, payload: String) =
            runCatching { mqtt?.publish(topic, payload.toByteArray(), 1, true) }

        pub(HaDiscovery.discoveryTopic(id), HaDiscovery.configPayload(id, name))
        pub(HaDiscovery.lightDiscoveryTopic(id), HaDiscovery.lightConfigPayload(id, name))

        if (sensorBridge?.hasTemperature == true) {
            pub(HaDiscovery.tempDiscoveryTopic(id), HaDiscovery.tempConfigPayload(id, name))
            pub(HaDiscovery.tempOffsetDiscoveryTopic(id), HaDiscovery.tempOffsetConfigPayload(id, name))
        }

        for (axis in listOf("x", "y", "z"))
            pub(HaDiscovery.accelDiscoveryTopic(id, axis), HaDiscovery.accelConfigPayload(id, name, axis))

        if (sensorBridge?.hasRgb == true) {
            for (ch in listOf("r", "g", "b"))
                pub(HaDiscovery.rgbDiscoveryTopic(id, ch), HaDiscovery.rgbConfigPayload(id, name, ch))
        }

        pub(HaDiscovery.tapDiscoveryTopic(id), HaDiscovery.tapConfigPayload(id, name))
        pub(HaDiscovery.sensitivityDiscoveryTopic(id), HaDiscovery.sensitivityConfigPayload(id, name))
        pub(HaDiscovery.soundDiscoveryTopic(id), HaDiscovery.soundConfigPayload(id, name))
        pub(HaDiscovery.micMuteDiscoveryTopic(id), HaDiscovery.micMuteConfigPayload(id, name))
        pub(HaDiscovery.volumeDiscoveryTopic(id), HaDiscovery.volumeConfigPayload(id, name))
        pub(HaDiscovery.volumeMuteDiscoveryTopic(id), HaDiscovery.volumeMuteConfigPayload(id, name))
        pub(HaDiscovery.doorbellDiscoveryTopic(id), HaDiscovery.doorbellConfigPayload(id, name))
        pub(HaDiscovery.alertDiscoveryTopic(id), HaDiscovery.alertConfigPayload(id, name))
        pub(HaDiscovery.brightnessDiscoveryTopic(id), HaDiscovery.brightnessConfigPayload(id, name))
        pub(HaDiscovery.ipDiscoveryTopic(id), HaDiscovery.ipConfigPayload(id, name))
        pub(HaDiscovery.presenceDiscoveryTopic(id), HaDiscovery.presenceConfigPayload(id, name))
        pub(HaDiscovery.presenceEnableDiscoveryTopic(id), HaDiscovery.presenceEnableConfigPayload(id, name))
        pub(HaDiscovery.screenTimeoutDiscoveryTopic(id), HaDiscovery.screenTimeoutConfigPayload(id, name))
        pub(HaDiscovery.screenTimeoutMinsDiscoveryTopic(id), HaDiscovery.screenTimeoutMinsConfigPayload(id, name))

        if (prefs.cameraServiceEnabled) {
            pub(HaDiscovery.cameraDiscoveryTopic(id), HaDiscovery.cameraConfigPayload(id, name))
            pub(HaDiscovery.motionDiscoveryTopic(id), HaDiscovery.motionConfigPayload(id, name))
            pub(HaDiscovery.motionSensitivityDiscoveryTopic(id), HaDiscovery.motionSensitivityConfigPayload(id, name))
            pub(HaDiscovery.motionEnableDiscoveryTopic(id), HaDiscovery.motionEnableConfigPayload(id, name))
            pub(HaDiscovery.streamEnableDiscoveryTopic(id), HaDiscovery.streamEnableConfigPayload(id, name))
        }
    }

    private fun publishAllStates() {
        val id = prefs.deviceId
        val am = getSystemService(AudioManager::class.java)

        publish(HaDiscovery.stateTopic(id), "ON", 0)
        publish(HaDiscovery.ipStateTopic(id), localIp() ?: "unknown", 0)
        publish(HaDiscovery.sensitivityStateTopic(id), "%.1f".format(prefs.tapThreshold), 0)
        publish(HaDiscovery.volumeStateTopic(id), volumePercent(am).toString(), 0)
        publish(HaDiscovery.volumeMuteStateTopic(id),
            if (am.isStreamMute(AudioManager.STREAM_MUSIC)) "ON" else "OFF", 0)
        publish(HaDiscovery.micMuteStateTopic(id),
            if (am.isMicrophoneMute) "ON" else "OFF", 0)
        publish(HaDiscovery.brightnessStateTopic(id), brightnessPercent().toString(), 0)
        publish(HaDiscovery.presenceEnableStateTopic(id),
            if (prefs.presenceEnabled) "ON" else "OFF", 0)
        publish(HaDiscovery.presenceStateTopic(id),
            if (presenceMonitor?.isPresent == true) "ON" else "OFF", 0)
        publish(HaDiscovery.screenTimeoutStateTopic(id),
            if (prefs.screenTimeoutEnabled) "ON" else "OFF", 0)
        publish(HaDiscovery.screenTimeoutMinsStateTopic(id),
            prefs.screenTimeoutMinutes.toString(), 0)

        if (sensorBridge?.hasTemperature == true) {
            publish(HaDiscovery.tempOffsetStateTopic(id), "%.1f".format(prefs.tempOffset), 0)
            sensorBridge?.republishTemperature()
        }

        if (prefs.cameraServiceEnabled) {
            publish(HaDiscovery.cameraStateTopic(id), if (prefs.cameraOn) "ON" else "OFF", 0)
            publish(HaDiscovery.motionStateTopic(id), "OFF", 0)
            publish(HaDiscovery.motionSensitivityStateTopic(id), prefs.motionSensitivity.toString(), 0)
            publish(HaDiscovery.motionEnableStateTopic(id),
                if (prefs.motionEnabled) "ON" else "OFF", 0)
            publish(HaDiscovery.streamEnableStateTopic(id),
                if (prefs.streamEnabled) "ON" else "OFF", 0)
        }
    }

    private fun handleCommand(topic: String, payload: String) {
        val id = prefs.deviceId
        val am = getSystemService(AudioManager::class.java)
        Log.d(TAG, "mqtt cmd: $topic = $payload")

        when (topic) {
            HaDiscovery.commandTopic(id) -> when (payload) {
                "ON" -> {
                    mainHandler.post { ScreenControl.wake(this) }
                    publish(HaDiscovery.stateTopic(id), "ON", 0)
                }
                "OFF" -> {
                    mainHandler.post { ScreenControl.sleep() }
                    publish(HaDiscovery.stateTopic(id), "OFF", 0)
                }
            }

            HaDiscovery.sensitivityCommandTopic(id) ->
                payload.toFloatOrNull()?.coerceIn(2f, 15f)?.let {
                    prefs.tapThreshold = it
                    publish(HaDiscovery.sensitivityStateTopic(id), "%.1f".format(it), 0)
                }

            HaDiscovery.volumeCommandTopic(id) ->
                payload.toIntOrNull()?.coerceIn(0, 100)?.let { pct ->
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, pct * max / 100,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                    publish(HaDiscovery.volumeStateTopic(id), pct.toString(), 0)
                }

            HaDiscovery.volumeMuteCommandTopic(id) -> {
                val mute = payload == "ON"
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                publish(HaDiscovery.volumeMuteStateTopic(id), if (mute) "ON" else "OFF", 0)
            }

            HaDiscovery.micMuteCommandTopic(id) -> {
                val mute = payload == "ON"
                am.isMicrophoneMute = mute
                publish(HaDiscovery.micMuteStateTopic(id), if (mute) "ON" else "OFF", 0)
            }

            HaDiscovery.brightnessCommandTopic(id) ->
                payload.toIntOrNull()?.coerceIn(0, 100)?.let { pct ->
                    setBrightness(pct)
                    publish(HaDiscovery.brightnessStateTopic(id), pct.toString(), 0)
                }

            HaDiscovery.cameraCommandTopic(id) -> {
                if (!prefs.cameraServiceEnabled) return
                val on = payload == "ON"
                prefs.cameraOn = on
                if (on) {
                    if (!prefs.motionEnabled && !prefs.streamEnabled) {
                        prefs.motionEnabled = prefs.lastMotionEnabled
                        prefs.streamEnabled = prefs.lastStreamEnabled
                    }
                } else {
                    prefs.lastMotionEnabled = prefs.motionEnabled
                    prefs.lastStreamEnabled = prefs.streamEnabled
                    prefs.motionEnabled = false
                    prefs.streamEnabled = false
                }
                applyCameraState()
                publish(HaDiscovery.cameraStateTopic(id), if (on) "ON" else "OFF", 0)
                publish(HaDiscovery.motionEnableStateTopic(id), if (prefs.motionEnabled) "ON" else "OFF", 0)
                publish(HaDiscovery.streamEnableStateTopic(id), if (prefs.streamEnabled) "ON" else "OFF", 0)
            }

            HaDiscovery.motionEnableCommandTopic(id) -> {
                if (!prefs.cameraServiceEnabled) return
                val en = payload == "ON"
                prefs.motionEnabled = en
                if (en) { prefs.streamEnabled = false; prefs.cameraOn = true }
                else if (!prefs.streamEnabled) prefs.cameraOn = false
                applyCameraState()
                publish(HaDiscovery.motionEnableStateTopic(id), if (en) "ON" else "OFF", 0)
                publish(HaDiscovery.cameraStateTopic(id), if (prefs.cameraOn) "ON" else "OFF", 0)
            }

            HaDiscovery.streamEnableCommandTopic(id) -> {
                if (!prefs.cameraServiceEnabled) return
                val en = payload == "ON"
                prefs.streamEnabled = en
                if (en) { prefs.motionEnabled = false; prefs.cameraOn = true }
                else if (!prefs.motionEnabled) prefs.cameraOn = false
                applyCameraState()
                publish(HaDiscovery.streamEnableStateTopic(id), if (en) "ON" else "OFF", 0)
                publish(HaDiscovery.cameraStateTopic(id), if (prefs.cameraOn) "ON" else "OFF", 0)
            }

            HaDiscovery.motionSensitivityCommandTopic(id) ->
                payload.toIntOrNull()?.coerceIn(1, 100)?.let {
                    prefs.motionSensitivity = it
                    publish(HaDiscovery.motionSensitivityStateTopic(id), it.toString(), 0)
                }

            HaDiscovery.soundCommandTopic(id) -> TonePlayer.play(payload)

            HaDiscovery.presenceEnableCommandTopic(id) -> {
                val en = payload == "ON"
                prefs.presenceEnabled = en
                if (en && presenceMonitor == null) {
                    val pm = PresenceMonitor { present ->
                        publish(HaDiscovery.presenceStateTopic(id), if (present) "ON" else "OFF", 0)
                        if (present) resetScreenTimeout() else scheduleScreenTimeout()
                    }
                    pm.start(); presenceMonitor = pm
                } else if (!en) {
                    presenceMonitor?.release(); presenceMonitor = null
                    publish(HaDiscovery.presenceStateTopic(id), "OFF", 0)
                }
                publish(HaDiscovery.presenceEnableStateTopic(id), if (en) "ON" else "OFF", 0)
            }

            HaDiscovery.screenTimeoutCommandTopic(id) -> {
                val en = payload == "ON"
                prefs.screenTimeoutEnabled = en
                applyDisplaySettingsInternal()
                publish(HaDiscovery.screenTimeoutStateTopic(id), if (en) "ON" else "OFF", 0)
            }

            HaDiscovery.screenTimeoutMinsCommandTopic(id) ->
                payload.toIntOrNull()?.coerceIn(1, 240)?.let {
                    prefs.screenTimeoutMinutes = it
                    applyDisplaySettingsInternal()
                    publish(HaDiscovery.screenTimeoutMinsStateTopic(id), it.toString(), 0)
                }

            HaDiscovery.tempOffsetCommandTopic(id) ->
                payload.toFloatOrNull()?.coerceIn(-20f, 20f)?.let {
                    prefs.tempOffset = it
                    sensorBridge?.republishTemperature()
                    publish(HaDiscovery.tempOffsetStateTopic(id), "%.1f".format(it), 0)
                }
        }
    }

    private fun publish(topic: String, payload: String, qos: Int, retained: Boolean = false) {
        runCatching { mqtt?.publish(topic, payload.toByteArray(), qos, retained) }
    }

    // ── Camera management ─────────────────────────────────────────────────────

    private fun applyCameraState() {
        if (!prefs.cameraServiceEnabled || !prefs.cameraOn) {
            stopCamera()
            return
        }
        when {
            prefs.streamEnabled -> { stopCameraStream(); startRtsp() }
            prefs.motionEnabled -> { stopRtsp(); startCameraStream() }
            else -> stopCamera()
        }
    }

    private fun ensureCameraState() {
        if (!prefs.cameraServiceEnabled || !prefs.cameraOn) return
        if (prefs.streamEnabled && rtspStreamer?.isStreaming != true) {
            stopRtsp(); startRtsp()
        } else if (prefs.motionEnabled && cameraStream?.isActive != true) {
            cameraStream?.start() ?: startCameraStream()
        }
    }

    private fun startCameraStream() {
        if (cameraStream?.isActive == true) return
        val ms = mjpegServer ?: MjpegServer(8080).also {
            it.username = "stream"
            it.password = prefs.mjpegToken
            it.start()
            mjpegServer = it
        }
        val md = motionDetector ?: MotionDetector().also { motionDetector = it }
        val id = prefs.deviceId
        val cs = CameraStream(this).apply {
            rotation = (prefs.streamRotation + lastAutoRotation.coerceAtLeast(0)) % 360
            onFrame = { jpeg ->
                ms.pushFrame(jpeg)
                if (prefs.motionEnabled) {
                    val detected = md.detect(jpeg, prefs.motionSensitivity)
                    publish(HaDiscovery.motionStateTopic(id), if (detected) "ON" else "OFF", 0)
                    if (detected) resetScreenTimeout()
                }
            }
            onStateChange = { active ->
                Log.i(TAG, "CameraStream active=$active")
                if (!active) publish(HaDiscovery.motionStateTopic(id), "OFF", 0)
            }
        }
        cs.start()
        cameraStream = cs
        Log.i(TAG, "CameraStream (MJPEG/motion) started on :8080")
    }

    private fun startRtsp() {
        if (rtspStreamer?.isStreaming == true) return
        val rs = RtspStreamer(this, 8554).apply {
            rotationOffset = prefs.streamRotation
            if (lastAutoRotation >= 0) autoRotation = lastAutoRotation
        }
        if (rs.start(1280, 720, 15, 2_000_000, false)) {
            rtspStreamer = rs
        } else {
            Log.w(TAG, "RTSP start failed")
        }
    }

    private fun stopCamera() {
        stopCameraStream()
        stopRtsp()
    }

    private fun stopCameraStream() {
        cameraStream?.release(); cameraStream = null
        motionDetector?.reset(); motionDetector = null
        publish(HaDiscovery.motionStateTopic(prefs.deviceId), "OFF", 0)
    }

    private fun stopRtsp() {
        rtspStreamer?.stop(); rtspStreamer = null
    }

    // ── Display settings ──────────────────────────────────────────────────────

    private fun applyDisplaySettingsInternal() {
        if (prefs.screenTimeoutEnabled) scheduleScreenTimeout()
        else timeoutHandler.removeCallbacks(timeoutRunnable)
    }

    private fun scheduleScreenTimeout() {
        if (!prefs.screenTimeoutEnabled) return
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, prefs.screenTimeoutMinutes * 60_000L)
    }

    private fun resetScreenTimeout() {
        if (!prefs.screenTimeoutEnabled) return
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, prefs.screenTimeoutMinutes * 60_000L)
    }

    // ── Orientation (auto-rotates RTSP stream) ────────────────────────────────

    private fun startOrientationListener() {
        val listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val auto = when {
                    orientation in 45..134  -> 270
                    orientation in 135..224 -> 180
                    orientation in 225..314 -> 90
                    else -> 0
                }
                if (auto == lastAutoRotation) return
                lastAutoRotation = auto
                rtspStreamer?.let { rs ->
                    rs.autoRotation = auto
                    workerHandler.post { rs.restart() }
                }
                cameraStream?.let { cs ->
                    cs.rotation = ((prefs.streamRotation + auto) % 360)
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationListener = listener
        }
    }

    // ── Audio / brightness helpers ────────────────────────────────────────────

    private fun volumePercent(am: AudioManager): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) cur * 100 / max else 0
    }

    private fun brightnessPercent(): Int = try {
        val raw = Settings.System.getInt(contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, 128)
        (raw * 100 / 255).coerceIn(0, 100)
    } catch (_: Exception) { 50 }

    private fun setBrightness(percent: Int) {
        if (!Settings.System.canWrite(this)) return
        runCatching {
            Settings.System.putInt(contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (percent * 255 / 100).coerceIn(1, 255))
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Portal HA Bridge",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the Home Assistant bridge running"
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Portal HA Bridge")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
