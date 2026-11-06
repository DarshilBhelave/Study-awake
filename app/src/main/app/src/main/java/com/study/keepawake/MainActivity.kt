package com.study.keepawake

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.ScrollView
import androidx.lifecycle.LifecycleService
import android.service.quicksettings.TileService

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. TURN ON CRASH CATCHER BEFORE ANYTHING ELSE!
        val prefs = getSharedPreferences("StudyAwakePrefs", Context.MODE_PRIVATE)
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            prefs.edit().putString("CRASH_LOG", throwable.stackTraceToString()).commit()
            Runtime.getRuntime().exit(1)
        }

        // 2. NOW boot the Android system Activity
        super.onCreate(savedInstanceState)

        // 3. Check if we crashed on the last attempt
        val lastCrash = prefs.getString("CRASH_LOG", null)
        if (lastCrash != null) {
            val errView = TextView(this).apply {
                text = "GHOST CRASH CAUGHT:\n\n$lastCrash"
                setTextColor(Color.parseColor("#FF5252"))
                setPadding(40, 40, 40, 40)
            }
            val scroll = ScrollView(this).apply { 
                addView(errView)
                setBackgroundColor(Color.BLACK) 
            }
            setContentView(scroll)
            
            // Clear the log so it doesn't show forever
            prefs.edit().remove("CRASH_LOG").apply()
            return
        }

        // 4. If no crash happened, show this success screen
        val text = TextView(this).apply {
            text = "SYSTEM BOOT SUCCESS.\nNO CRASHES DETECTED."
            setTextColor(Color.parseColor("#00E676"))
            textSize = 22f
            setPadding(50, 100, 50, 50)
        }
        val layout = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(text)
        }
        setContentView(layout)
    }
}

// Barebones stubs to keep the manifest happy while we test
class KeepAwakeService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }
}
class StudyAwakeTileService : TileService() {}
