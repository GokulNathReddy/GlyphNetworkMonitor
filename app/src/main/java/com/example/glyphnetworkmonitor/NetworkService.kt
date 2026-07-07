package com.example.glyphnetworkmonitor

import android.app.*
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager

class NetworkService : Service() {

    private var gm: GlyphManager? = null
    private var lastBytes: Long = 0
    private var sessionOpen = false
    private var monitoringStarted = false

    private val handler = Handler(Looper.getMainLooper())
    private val CHANNEL_ID = "glyph_network_mon"
    private val TAG = "GlyphNetworkMonitor"

    // Phone 3a: B1-B5 = indices 31-35
    private val B_CHANNEL_RANGE = 31..35

    private val TICK_MS = 60L // faster sampling so state changes register quicker

    private enum class Tier { OFF, MEDIUM, HIGH }
    private var currentTier = Tier.OFF

    private var emaRate = 0.0
    private val EMA_ALPHA = 0.35 // more reactive than before (was 0.25) -> feels snappier

    // Hysteresis thresholds (bytes/sec)
    private val MEDIUM_ENTER_BPS = 12_000.0
    private val MEDIUM_EXIT_BPS = 5_000.0
    private val HIGH_ENTER_BPS = 350_000.0
    private val HIGH_EXIT_BPS = 180_000.0

    // --- MEDIUM: real hard flicker via toggle(), like a NIC activity LED ---
    private val MEDIUM_BRIGHTNESS_MIN = 900
    private val MEDIUM_BRIGHTNESS_MAX = 2000
    private val MEDIUM_ON_MS_FAST = 35L   // near HIGH_ENTER -> fast chatter
    private val MEDIUM_ON_MS_SLOW = 80L   // near MEDIUM_ENTER -> lazier flicker
    private val MEDIUM_OFF_MS_FAST = 60L
    private val MEDIUM_OFF_MS_SLOW = 180L
    private var blinkOn = false
    private var mediumFrameOn = false // tracks actual toggle state so we call toggle() correctly
    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (currentTier != Tier.MEDIUM) return
            blinkOn = !blinkOn
            val brightness = scaleLight(
                emaRate.toLong(),
                MEDIUM_ENTER_BPS.toLong(), HIGH_ENTER_BPS.toLong(),
                MEDIUM_BRIGHTNESS_MIN, MEDIUM_BRIGHTNESS_MAX
            )
            toggleFrame(if (blinkOn) brightness else 0)

            val ratio = ((emaRate - MEDIUM_ENTER_BPS) / (HIGH_ENTER_BPS - MEDIUM_ENTER_BPS))
                .coerceIn(0.0, 1.0)
            val onMs = (MEDIUM_ON_MS_SLOW - ratio * (MEDIUM_ON_MS_SLOW - MEDIUM_ON_MS_FAST)).toLong()
            val offMs = (MEDIUM_OFF_MS_SLOW - ratio * (MEDIUM_OFF_MS_SLOW - MEDIUM_OFF_MS_FAST)).toLong()

            handler.postDelayed(this, if (blinkOn) onMs else offMs)
        }
    }

    // --- HIGH: continuous native breathing, fast period so it reads as a quick pulse ---
    private val HIGH_BRIGHTNESS_MIN = 2400
    private val HIGH_BRIGHTNESS_MAX = 4096
    private val BREATH_PERIOD_MS = 260   // was 900 -> much quicker pulse
    private val BREATH_INTERVAL_MS = 40  // tiny gap so cycles don't visually merge into a flat glow
    private val BREATH_CYCLES = 60_000

    private val monitorRunnable = object : Runnable {
        override fun run() {
            val current = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()

            if (current != TrafficStats.UNSUPPORTED.toLong()) {
                val delta = (current - lastBytes).coerceAtLeast(0)
                lastBytes = current

                val instantBps = (delta * 1000.0) / TICK_MS
                emaRate = EMA_ALPHA * instantBps + (1 - EMA_ALPHA) * emaRate

                val nextTier = classifyTier(emaRate)
                if (nextTier != currentTier) {
                    transitionTo(nextTier)
                } else if (nextTier == Tier.HIGH) {
                    // re-brighten in place as load climbs further, without restarting the breath
                    maybeRebrightenHigh()
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun classifyTier(rate: Double): Tier {
        return when (currentTier) {
            Tier.OFF -> if (rate > MEDIUM_ENTER_BPS) Tier.MEDIUM else Tier.OFF
            Tier.MEDIUM -> when {
                rate > HIGH_ENTER_BPS -> Tier.HIGH
                rate < MEDIUM_EXIT_BPS -> Tier.OFF
                else -> Tier.MEDIUM
            }
            Tier.HIGH -> if (rate < HIGH_EXIT_BPS) Tier.MEDIUM else Tier.HIGH
        }
    }

    private fun transitionTo(newTier: Tier) {
        when (currentTier) {
            Tier.MEDIUM -> handler.removeCallbacks(blinkRunnable)
            else -> {}
        }

        currentTier = newTier

        when (newTier) {
            Tier.OFF -> {
                mediumFrameOn = false
                try { gm?.turnOff() } catch (e: Exception) { Log.e(TAG, "turnOff failed: ${e.message}") }
            }
            Tier.MEDIUM -> {
                blinkOn = false
                mediumFrameOn = false
                handler.removeCallbacks(blinkRunnable)
                handler.post(blinkRunnable)
            }
            Tier.HIGH -> startBreathing(HIGH_BRIGHTNESS_MIN)
        }
    }

    private var lastHighBrightness = HIGH_BRIGHTNESS_MIN
    private fun maybeRebrightenHigh() {
        val brightness = scaleLight(
            emaRate.toLong(),
            HIGH_ENTER_BPS.toLong(), (HIGH_ENTER_BPS * 6).toLong(),
            HIGH_BRIGHTNESS_MIN, HIGH_BRIGHTNESS_MAX
        )
        // only re-issue animate() if brightness moved meaningfully -> avoid spamming the service
        if (Math.abs(brightness - lastHighBrightness) >= 150) {
            startBreathing(brightness)
        }
    }

    /** Instant hard on/off at a given brightness -> real flicker, no fade. */
    private fun toggleFrame(brightness: Int) {
        val manager = gm ?: return
        if (!sessionOpen) return
        try {
            val builder = manager.glyphFrameBuilder
            for (index in B_CHANNEL_RANGE) {
                builder.buildChannel(index, brightness)
            }
            val frame = builder.build()
            manager.toggle(frame)
            mediumFrameOn = brightness > 0
        } catch (e: Exception) {
            Log.e(TAG, "toggleFrame failed: ${e.message}")
        }
    }

    /** Continuous native breathing animation -> fires on HIGH entry / rebrighten, hardware loops it. */
    private fun startBreathing(brightness: Int) {
        val manager = gm ?: return
        if (!sessionOpen) return
        lastHighBrightness = brightness
        try {
            val builder = manager.glyphFrameBuilder
            for (index in B_CHANNEL_RANGE) {
                builder.buildChannel(index, brightness)
            }
            val frame = builder
                .buildPeriod(BREATH_PERIOD_MS)
                .buildCycles(BREATH_CYCLES)
                .buildInterval(BREATH_INTERVAL_MS)
                .build()
            manager.animate(frame)
        } catch (e: Exception) {
            Log.e(TAG, "startBreathing failed: ${e.message}")
        }
    }

    /** Linearly scales a value from [inMin, inMax] into [outMin, outMax], clamped. */
    private fun scaleLight(value: Long, inMin: Long, inMax: Long, outMin: Int, outMax: Int): Int {
        val clamped = value.coerceIn(inMin, inMax)
        val ratio = (clamped - inMin).toDouble() / (inMax - inMin).toDouble()
        return (outMin + ratio * (outMax - outMin)).toInt().coerceIn(outMin, outMax)
    }

    private val glyphCallback = object : GlyphManager.Callback {
        override fun onServiceConnected(componentName: ComponentName) {
            gm?.let { manager ->
                if (Common.is24111()) {
                    manager.register(Glyph.DEVICE_24111)
                } else {
                    manager.register(Build.MODEL)
                }
                try {
                    manager.openSession()
                    sessionOpen = true
                } catch (e: GlyphException) {
                    Log.e(TAG, "openSession failed: ${e.message}")
                }
            }
            startMonitoring()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            sessionOpen = false
        }
    }

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(CHANNEL_ID, "Glyph Monitor", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Glyph HDD Mode")
            .setContentText("Monitoring network activity (B1–B5)")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        gm = GlyphManager.getInstance(applicationContext)
        gm?.init(glyphCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true
        lastBytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        handler.post(monitorRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        gm?.let { manager ->
            try {
                manager.turnOff()
                if (sessionOpen) {
                    manager.closeSession()
                }
            } catch (e: GlyphException) {
                Log.e(TAG, "closeSession failed: ${e.message}")
            }
            manager.unInit()
        }
        sessionOpen = false
        monitoringStarted = false
        currentTier = Tier.OFF
        emaRate = 0.0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}