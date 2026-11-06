package com.study.keepawake

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleService
import android.service.quicksettings.TileService

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // SAFE MODE UI: Absolutely zero complex widgets
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }

        val text = TextView(this).apply {
            text = "SAFE MODE DIAGNOSTIC"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(0, 0, 0, 50)
        }
        
        val btn = Button(this).apply {
            text = "TEST SERVICE"
            setBackgroundColor(Color.parseColor("#00E676"))
            setTextColor(Color.BLACK)
            setOnClickListener {
                text = "CLICKED"
            }
        }

        layout.addView(text)
        layout.addView(btn)
        setContentView(layout)
    }
}

// Stubs to prevent build errors during diagnostic
class KeepAwakeService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }
}

class StudyAwakeTileService : TileService() {}
