package com.example.glyphnetworkmonitor

import android.content.Intent
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toggle = findViewById<Switch>(R.id.serviceToggle)
        val statsText = findViewById<TextView>(R.id.statsText)

        toggle.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, NetworkService::class.java)
            if (isChecked) startForegroundService(intent) else stopService(intent)
        }

        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            var last = TrafficStats.getTotalRxBytes()
            override fun run() {
                val curr = TrafficStats.getTotalRxBytes()
                val speed = (curr - last) / 1024
                statsText.text = "Activity: $speed KB/s"
                last = curr
                handler.postDelayed(this, 1000)
            }
        })
    }
}
