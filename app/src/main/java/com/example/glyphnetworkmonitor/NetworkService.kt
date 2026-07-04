package com.example.glyphnetworkmonitor

import android.app.*
import android.content.Intent
import android.net.TrafficStats
import android.os.*
import androidx.core.app.NotificationCompat
import com.nothing.ketchum.GlyphFrame
import com.nothing.ketchum.GlyphManager

class NetworkService : Service() {
    private var gm: GlyphManager? = null
    private var lastBytes: Long = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("glyph", "Glyph HDD", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, "glyph")
            .setContentTitle("Glyph HDD Mode Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        gm = GlyphManager.getInstance(this)
        gm?.init(object : GlyphManager.Callback {
            override fun onServiceConnected(componentName: android.content.ComponentName) {
                gm?.register()
                startMonitoring()
            }
            override fun onServiceDisconnected(componentName: android.content.ComponentName) {}
        })

        return START_STICKY
    }

    private fun startMonitoring() {
        lastBytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()

        handler.post(object : Runnable {
            override fun run() {
                val current = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
                val delta = current - lastBytes
                lastBytes = current

                if (delta > 2048) {
                    val frame = gm!!.glyphFrameBuilder
                        .buildChannelA()
                        .build()
                    gm?.toggle(frame)

                    val duration = if (delta > 500000) 150L else 40L
                    handler.postDelayed({ gm?.turnOff() }, duration)
                }
                handler.postDelayed(this, 200)
            }
        })
    }

    override fun onDestroy() {
        gm?.turnOff()
        gm?.unInit()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
