package com.study.keepawake

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val tv = TextView(this).apply {
            text = "APP LAUNCHED SUCCESSFULLY"
            setTextColor(Color.GREEN)
            textSize = 24f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        
        setContentView(tv)
    }
}
