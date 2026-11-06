package com.study.keepawake

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : Activity() {

    private var intervalSeconds = 20
    private lateinit var statusBox: TextView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("StudyAwakePrefs", Context.MODE_PRIVATE)

        // BULLETPROOF CRASH CATCHER: Saves error to memory and exits safely
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            prefs.edit().putString("CRASH_LOG", throwable.stackTraceToString()).commit()
            Runtime.getRuntime().exit(1)
        }

        // Check if we crashed on the last run and show it
        val lastCrash = prefs.getString("CRASH_LOG", null)
        if (lastCrash != null) {
            val errView = TextView(this).apply {
                text = "PREVIOUS CRASH LOG:\n\n$lastCrash"
                setTextColor(Color.parseColor("#FF5252"))
                setBackgroundColor(Color.BLACK)
                setPadding(40, 40, 40, 40)
                isScrollContainer = true
            }
            val scroll = ScrollView(this).apply { addView(errView) }
            setContentView(scroll)
            
            // Clear it so it doesn't show forever
            prefs.edit().remove("CRASH_LOG").apply()
            return
        }

        try {
            val scrollView = ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#121212"))
            }

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(50, 40, 50, 60)
            }

            val title = TextView(this).apply {
                text = "StudyAwake Pro"
                textSize = 26f
                setTextColor(Color.parseColor("#00E676"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 10)
            }

            statusBox = TextView(this).apply {
                text = "Status: Ready\nConfigure security and display modes below."
                textSize = 13f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            }

            val intervalLabel = TextView(this).apply {
                text = "Scan Interval: 20 seconds"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 10)
            }

            val seekBar = SeekBar(this).apply {
                max = 50
                progress = 10
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        intervalSeconds = p + 10
                        intervalLabel.text = "Scan Interval: $intervalSeconds seconds"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }

            // Using CheckBox instead of Switch to prevent Samsung One UI crashes
            val warmSwitch = CheckBox(this).apply {
                text = "Dynamic Ambient Warmth & Dim"
                setTextColor(Color.WHITE)
                isChecked = prefs.getBoolean("ENABLE_WARMTH", true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("ENABLE_WARMTH", checked).apply()
                }
            }

            val privacySwitch = CheckBox(this).apply {
                text = "Intruder Screen Saver Shield"
                setTextColor(Color.WHITE)
                isChecked = prefs.getBoolean("ENABLE_PRIVACY", false)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("ENABLE_PRIVACY", checked).apply()
                }
            }

            val calibrateBtn = Button(this).apply {
                text = "Calibrate Primary Face"
                setOnClickListener {
                    prefs.edit().putBoolean("TRIGGER_CALIBRATE", true).apply()
                    Toast.makeText(this@MainActivity, "Look straight at front camera on next check!", Toast.LENGTH_LONG).show()
                }
            }

            val alarmSwitch = CheckBox(this).apply {
                text = "Sleep Detection: Sound Alarm"
                setTextColor(Color.WHITE)
                isChecked = prefs.getBoolean("ENABLE_ALARM", true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("ENABLE_ALARM", checked).apply()
                }
            }

            val startBtn = Button(this).apply {
                text = "START STUDY SYSTEM"
                setBackgroundColor(Color.parseColor("#00E676"))
                setTextColor(Color.BLACK)
                setOnClickListener {
                    if (hasPermissions()) {
                        val intent = Intent(this@MainActivity, KeepAwakeService::class.java).apply {
                            putExtra("INTERVAL", intervalSeconds * 1000L)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        statusBox.text = "Status: RUNNING\nProtected by StudyAwake AI."
                        statusBox.setTextColor(Color.GREEN)
                    } else {
                        requestAppPermissions()
                    }
                }
            }

            val stopBtn = Button(this).apply {
                text = "STOP SERVICE"
                setOnClickListener {
                    stopService(Intent(this@MainActivity, KeepAwakeService::class.java))
                    statusBox.text = "Status: Stopped."
                    statusBox.setTextColor(Color.LTGRAY)
                }
            }

            layout.addView(title)
            layout.addView(statusBox)
            layout.addView(intervalLabel)
            layout.addView(seekBar)
            layout.addView(createSpacer(20))
            layout.addView(warmSwitch)
            layout.addView(createSpacer(10))
            layout.addView(privacySwitch)
            layout.addView(calibrateBtn)
            layout.addView(createSpacer(10))
            layout.addView(alarmSwitch)
            layout.addView(createSpacer(30))
            layout.addView(startBtn)
            layout.addView(createSpacer(10))
            layout.addView(stopBtn)

            scrollView.addView(layout)
            setContentView(scrollView)

            if (!hasPermissions()) requestAppPermissions()

        } catch (t: Throwable) {
            val errView = TextView(this).apply {
                text = "Startup Error:\n${t.stackTraceToString()}"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                setPadding(40, 40, 40, 40)
            }
            setContentView(errView)
        }
    }

    private fun createSpacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightDp)
        }
    }

    private fun hasPermissions(): Boolean {
        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val overlay = Settings.canDrawOverlays(this)
        return cam && notif && overlay
    }

    private fun requestAppPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow 'Appear on top' for display tints & wake lock!", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), 101)
    }
}

class KeepAwakeService : LifecycleService() {

    companion object {
        var isRunning = false
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var windowManager: WindowManager? = null
    private var overlayFilterView: View? = null
    private var privacyShieldView: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences

    private var baseIntervalMs = 20000L
    private var checkIntervalMs = 20000L
    private var missedDetections = 0
    private var consecutiveSleepChecks = 0
    private var ringtone: Ringtone? = null

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build()
        FaceDetection.getClient(options)
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            performCameraCheck()
            handler.postDelayed(this, checkIntervalMs)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        isRunning = true
        prefs = getSharedPreferences("StudyAwakePrefs", Context.MODE_PRIVATE)
        baseIntervalMs = intent?.getLongExtra("INTERVAL", 20000L) ?: 20000L
        checkIntervalMs = baseIntervalMs

        try {
            val notif = buildNotification("Monitoring presence & ambient light")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(1, notif)
            }
            acquireWakeLock()
            setupOverlayViews()
            handler.removeCallbacks(checkRunnable)
            handler.post(checkRunnable)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "StudyAwake::ScreenLock"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(15 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun setupOverlayViews() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Tint / Warmth View
        if (overlayFilterView == null) {
            overlayFilterView = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            windowManager?.addView(overlayFilterView, params)
        }

        // Intruder Screen Saver Shield View
        if (privacyShieldView == null) {
            privacyShieldView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE

                val text = TextView(this@KeepAwakeService).apply {
                    text = "🔒 Screen Shield Active\nTap to Dismiss"
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                }
                addView(text)
                setOnClickListener {
                    visibility = View.GONE
                }
            }

            val shieldParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager?.addView(privacyShieldView, shieldParams)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun performCameraCheck() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var analyzed = false

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (analyzed) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        analyzed = true

                        val yPlane = mediaImage.planes[0].buffer
                        val ambientLuma = calculateAverageLuma(yPlane, mediaImage.width, mediaImage.height)
                        handler.post { applyAmbientDisplayAdjustment(ambientLuma) }

                        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        detector.process(inputImage)
                            .addOnSuccessListener { faces ->
                                processFaceDetections(faces)
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                                handler.post { cameraProvider.unbindAll() }
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
            } catch (_: Exception) {
                updateNotification("Camera busy or blocked.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun calculateAverageLuma(buffer: ByteBuffer, width: Int, height: Int): Int {
        buffer.rewind()
        var sum = 0L
        var count = 0
        val step = 16 
        val limit = buffer.remaining()
        var i = 0
        while (i < limit) {
            sum += (buffer.get(i).toInt() and 0xFF)
            count++
            i += step
        }
        return if (count > 0) (sum / count).toInt() else 128
    }

    private fun applyAmbientDisplayAdjustment(luma: Int) {
        if (!prefs.getBoolean("ENABLE_WARMTH", true) || overlayFilterView == null) {
            overlayFilterView?.setBackgroundColor(Color.TRANSPARENT)
            return
        }
        val filterColor = when {
            luma < 45 -> Color.argb(85, 255, 140, 0)
            luma < 120 -> Color.argb(40, 255, 165, 0)
            luma < 190 -> Color.TRANSPARENT
            else -> Color.argb(20, 0, 0, 0)
        }
        overlayFilterView?.setBackgroundColor(filterColor)
    }

    private fun processFaceDetections(faces: List<Face>) {
        if (faces.isEmpty()) {
            missedDetections++
            consecutiveSleepChecks = 0
            stopAlarm()
            releaseWakeLock()

            if (missedDetections >= 62) {
                checkIntervalMs = 60 * 60 * 1000L
                updateNotification("Away a long time. Next check in 1 hr.")
            } else if (missedDetections >= 50) {
                checkIntervalMs = 5 * 60 * 1000L
                updateNotification("Away. Battery saver: checking every 5 mins.")
            } else if (missedDetections >= 2) {
                checkIntervalMs = baseIntervalMs
                updateNotification("Away (${missedDetections}x) - Letting screen sleep.")
            }
            return
        }

        missedDetections = 0
        checkIntervalMs = baseIntervalMs
        acquireWakeLock()

        val primaryFace = faces[0]

        if (prefs.getBoolean("TRIGGER_CALIBRATE", false)) {
            calibrateFaceProfile(primaryFace)
            prefs.edit().putBoolean("TRIGGER_CALIBRATE", false).apply()
            handler.post { Toast.makeText(this, "Face calibration saved!", Toast.LENGTH_SHORT).show() }
        }

        val privacyEnabled = prefs.getBoolean("ENABLE_PRIVACY", false)
        if (privacyEnabled) {
            val isIntruderPresent = faces.size > 1 || !isRecognizedFace(primaryFace)
            handler.post {
                if (isIntruderPresent) {
                    privacyShieldView?.visibility = View.VISIBLE
                    updateNotification("Intruder Shield ACTIVE!")
                }
            }
        }

        val leftOpen = primaryFace.leftEyeOpenProbability ?: 1.0f
        val rightOpen = primaryFace.rightEyeOpenProbability ?: 1.0f

        if (leftOpen < 0.25f && rightOpen < 0.25f) {
            consecutiveSleepChecks++
            if (consecutiveSleepChecks >= 2) {
                handleSleepDetected()
            }
        } else {
            consecutiveSleepChecks = 0
            stopAlarm()
            updateNotification("Active: User awake and studying.")
        }
    }

    private fun calibrateFaceProfile(face: Face) {
        val bounds = face.boundingBox
        val ratio = bounds.width().toFloat() / bounds.height().toFloat()

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

        var eyeDistRatio = 0f
        if (leftEye != null && rightEye != null) {
            val dist = hypot((leftEye.x - rightEye.x).toDouble(), (leftEye.y - rightEye.y).toDouble()).toFloat()
            eyeDistRatio = dist / bounds.width().toFloat()
        }

        prefs.edit()
            .putFloat("PROFILE_RATIO", ratio)
            .putFloat("PROFILE_EYE_RATIO", eyeDistRatio)
            .putBoolean("PROFILE_SAVED", true)
            .apply()
    }

    private fun isRecognizedFace(face: Face): Boolean {
        if (!prefs.getBoolean("PROFILE_SAVED", false)) return true 
        val savedRatio = prefs.getFloat("PROFILE_RATIO", 0f)
        val bounds = face.boundingBox
        val currentRatio = bounds.width().toFloat() / bounds.height().toFloat()
        return abs(currentRatio - savedRatio) <= 0.25f
    }

    private fun handleSleepDetected() {
        val soundAlarm = prefs.getBoolean("ENABLE_ALARM", true)
        if (soundAlarm) {
            updateNotification("DROWSINESS DETECTED! Waking up...")
            playAlarm()
        } else {
            updateNotification("Sleep detected: Powering display down.")
            releaseWakeLock()
        }
    }

    private fun playAlarm() {
        try {
            if (ringtone == null) {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ringtone?.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
            }
            if (ringtone?.isPlaying == false) {
                ringtone?.play()
            }
        } catch (_: Exception) {}
    }

    private fun stopAlarm() {
        try {
            if (ringtone?.isPlaying == true) {
                ringtone?.stop()
            }
        } catch (_: Exception) {}
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "study_awake_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "StudyAwake Engine", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
            return Notification.Builder(this, channelId)
                .setContentTitle("StudyAwake Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("StudyAwake Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        stopAlarm()
        handler.removeCallbacks(checkRunnable)
        releaseWakeLock()

        try {
            if (overlayFilterView != null) {
                windowManager?.removeView(overlayFilterView)
                overlayFilterView = null
            }
            if (privacyShieldView != null) {
                windowManager?.removeView(privacyShieldView)
                privacyShieldView = null
            }
        } catch (_: Exception) {}

        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

class StudyAwakeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = if (KeepAwakeService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        if (KeepAwakeService.isRunning) {
            stopService(Intent(this, KeepAwakeService::class.java))
            tile.state = Tile.STATE_INACTIVE
        } else {
            val intent = Intent(this, KeepAwakeService::class.java).apply {
                putExtra("INTERVAL", 20000L)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tile.state = Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }
}
