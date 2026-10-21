package com.uxspace.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide read-only view of the phone's status — what the workspace's status tray
 * (and, later, the quick-settings panel) renders.
 *
 * Battery, music-stream volume and audio output; Wi-Fi and cellular signal; and the
 * quick-settings states (Wi-Fi / Bluetooth / airplane / Do not disturb on, brightness).
 * Notifications live in [NotificationCenter]. See docs/TASKBAR.md.
 *
 * Receivers and observers are registered once in [init] and are tied to the app process —
 * the desktop UI just adds listeners that fire on the main thread when something changes.
 */
object SystemStatus {

    /** Notified on the main thread when any tracked status changes. */
    interface Listener {
        fun onSystemStatusChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    @Volatile
    var batteryPercent: Int = 100
        private set

    @Volatile
    var batteryCharging: Boolean = false
        private set

    /** Music volume as a 0..1 fraction. */
    @Volatile
    var volumeFraction: Float = 1f
        private set

    /**
     * The currently-routing audio output as we estimate it. Android doesn't expose
     * "which device am I really using" for general media, so we apply a priority
     * heuristic over the set of connected output devices: BT > USB > wired > builtin.
     * Matches what the system itself does in practice.
     */
    enum class AudioOutput(val displayName: String) {
        SPEAKER("Speaker"),
        WIRED("Headphones"),
        BLUETOOTH("Bluetooth"),
        USB("USB / Glasses"),
    }

    @Volatile
    var activeOutput: AudioOutput = AudioOutput.SPEAKER
        private set

    /**
     * Human-readable name for the active output device (e.g. "Pixel Buds Pro" for BT,
     * or "VITURE One USB Audio" for the glasses). Falls back to the [activeOutput]
     * display name when the device doesn't report a product name.
     */
    @Volatile
    var activeOutputName: String = AudioOutput.SPEAKER.displayName
        private set

    /** Wi-Fi radio on (not necessarily connected). */
    @Volatile
    var wifiEnabled: Boolean = false
        private set

    /** Connected to a Wi-Fi network. */
    @Volatile
    var wifiConnected: Boolean = false
        private set

    /** Wi-Fi signal, 0..4 bars; meaningful while [wifiConnected]. */
    @Volatile
    var wifiLevel: Int = 0
        private set

    /** The device has a SIM / mobile radio worth showing. */
    @Volatile
    var hasCellular: Boolean = false
        private set

    /** Mobile signal, 0..4 bars. */
    @Volatile
    var cellLevel: Int = 0
        private set

    @Volatile
    var bluetoothOn: Boolean = false
        private set

    @Volatile
    var airplaneOn: Boolean = false
        private set

    /** Do not disturb: any interruption filter other than "all". */
    @Volatile
    var dndOn: Boolean = false
        private set

    /** Screen brightness, 0..255. */
    @Volatile
    var brightness: Int = 128
        private set

    /** Wire up the system listeners. Call once from the Application. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        registerBatteryReceiver()
        registerVolumeObserver()
        registerAudioDeviceCallback()
        registerToggleObservers()
        registerWifiCallback()
        registerCellularCallback()
        readVolume()
        readActiveOutput()
        readToggles()
    }

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    private fun notifyChanged() {
        listeners.forEach { runCatching { it.onSystemStatusChanged() } }
    }

    /**
     * Set the music-stream volume to [fraction] (0..1). Returns the actual new fraction the
     * `AudioManager` accepted (stream volumes are discrete steps).
     */
    fun setVolume(fraction: Float): Float {
        val ctx = appContext ?: return volumeFraction
        val am = ctx.getSystemService(AudioManager::class.java) ?: return volumeFraction
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return volumeFraction
        val target = (fraction.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        // The observer will fire and update volumeFraction — but update eagerly too.
        volumeFraction = target.toFloat() / max
        return volumeFraction
    }

    private fun registerBatteryReceiver() {
        val ctx = appContext ?: return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ctx.registerReceiver(batteryReceiver, filter)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                batteryPercent = level * 100 / scale
            }
            val status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN,
            )
            batteryCharging =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            notifyChanged()
        }
    }

    private fun registerVolumeObserver() {
        val ctx = appContext ?: return
        ctx.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    readVolume()
                    readToggles()
                    notifyChanged()
                }
            },
        )
    }

    private fun readVolume() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (max > 0) volumeFraction = cur.toFloat() / max
    }

    private fun registerAudioDeviceCallback() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    readActiveOutput()
                    notifyChanged()
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    readActiveOutput()
                    notifyChanged()
                }
            },
            mainHandler,
        )
    }

    /**
     * Pick the most-likely active media output from `getDevices(GET_DEVICES_OUTPUTS)`
     * using Android's own priority order: BT > USB > wired > builtin. There is no
     * public API for "the one Android is actually using" outside of voice calls, so
     * this mirrors the routing the system itself does.
     */
    private fun readActiveOutput() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var best: AudioDeviceInfo? = null
        var bestRank = -1
        for (d in devices) {
            val rank = rankFor(d.type)
            if (rank > bestRank) {
                bestRank = rank
                best = d
            }
        }
        val (kind, name) = if (best != null) {
            classify(best)
        } else AudioOutput.SPEAKER to AudioOutput.SPEAKER.displayName
        activeOutput = kind
        activeOutputName = name
    }

    private fun rankFor(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> 4
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 3
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 2
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> 1
        else -> 0
    }

    private fun classify(d: AudioDeviceInfo): Pair<AudioOutput, String> {
        val productName = runCatching { d.productName?.toString() }.getOrNull().orEmpty()
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> AudioOutput.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutput.USB
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioOutput.WIRED
            else -> AudioOutput.SPEAKER
        }
        val name = productName.ifBlank { kind.displayName }
        return kind to name
    }

    // region Quick-settings states + signal

    /** Wi-Fi / Bluetooth / airplane live in Settings.Global; DND has its own broadcast. */
    private fun registerToggleObservers() {
        val ctx = appContext ?: return
        ctx.contentResolver.registerContentObserver(
            Settings.Global.CONTENT_URI,
            true,
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    readToggles()
                    notifyChanged()
                }
            },
        )
        ctx.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    readToggles()
                    notifyChanged()
                }
            },
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
        )
    }

    private fun readToggles() {
        val ctx = appContext ?: return
        val cr = ctx.contentResolver
        wifiEnabled = Settings.Global.getInt(cr, Settings.Global.WIFI_ON, 0) != 0
        bluetoothOn = Settings.Global.getInt(cr, Settings.Global.BLUETOOTH_ON, 0) != 0
        airplaneOn = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        brightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, brightness)
        val filter = ctx.getSystemService(NotificationManager::class.java)
            ?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        dndOn = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun registerWifiCallback() {
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    wifiConnected = true
                    wifiLevel = wifiBars(caps.signalStrength)
                    notifyChanged()
                }

                override fun onLost(network: Network) {
                    wifiConnected = false
                    wifiLevel = 0
                    notifyChanged()
                }
            },
            mainHandler,
        )
    }

    /** RSSI (dBm) to 0..4 bars, the same buckets the status bar uses. */
    private fun wifiBars(rssi: Int): Int {
        if (rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) return 4
        val wm = appContext?.getSystemService(WifiManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            (wm.calculateSignalLevel(rssi) * 4 / wm.maxSignalLevel.coerceAtLeast(1)).coerceIn(0, 4)
        } else {
            @Suppress("DEPRECATION")
            WifiManager.calculateSignalLevel(rssi, 5)
        }
    }

    private fun registerCellularCallback() {
        val ctx = appContext ?: return
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
        hasCellular = tm.phoneType != TelephonyManager.PHONE_TYPE_NONE &&
            tm.simState == TelephonyManager.SIM_STATE_READY
        if (!hasCellular) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tm.registerTelephonyCallback(
                    ctx.mainExecutor,
                    object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                        override fun onSignalStrengthsChanged(strength: SignalStrength) {
                            cellLevel = strength.level
                            notifyChanged()
                        }
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                tm.listen(
                    object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onSignalStrengthsChanged(strength: SignalStrength?) {
                            cellLevel = strength?.level ?: 0
                            notifyChanged()
                        }
                    },
                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS,
                )
            }
        }.onFailure { Log.w("UxSpace/Status", "no signal-strength updates", it) }
    }

    // endregion
}
