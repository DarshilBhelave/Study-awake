package com.study.keepawake

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private var intervalSeconds = 20
    private lateinit var statusBox: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Looper.prepare()
            Toast.makeText(applicationContext, "Crash Error: ${throwable.message}", Toast.LENGTH_LONG).show()
            Looper.loop()
        }

        try {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#121212"))
                setPadding(60, 60, 60, 60)
            }

            val title = TextView(this).apply {
                text = "StudyAwake"
                textSize = 26f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 20)
            }

            statusBox = TextView(this).apply {
                text = "Status: Ready\nChecks front camera while you write assignments."
                textSize = 14f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 50)
            }

            val intervalLabel = TextView(this).apply {
                text = "Check Camera Every: 20 seconds"
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 20)
            }

            val seekBar = SeekBar(this).apply {
                max = 50
                progress = 10
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                        intervalSeconds = p + 10
                        intervalLabel.text = "Check Camera Every: $intervalSeconds seconds"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }

            val startBtn = Button(this).apply {
                text = "START ASSIGNMENT MODE"
                setOnClickListener {
                    try {
                        if (hasPermissions()) {
                            val intent = Intent(this@MainActivity, KeepAwakeService::class.java)
                            intent.putExtra("INTERVAL", intervalSeconds * 1000L)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                            statusBox.text = "Status: RUNNING!\nYou can minimize this app and open your assignment now."
                            statusBox.setTextColor(Color.GREEN)
                        } else {
                            requestAppPermissions()
                        }
                    } catch (e: Exception) {
                        statusBox.text = "Start Error:\n${e.javaClass.simpleName}: ${e.message}"
                        statusBox.setTextColor(Color.RED)
                    }
                }
            }

            val stopBtn = Button(this).apply {
                text = "STOP SERVICE"
                setOnClickListener {
                    try {
                        stopService(Intent(this@MainActivity, KeepAwakeService::class.java))
                        statusBox.text = "Status: Stopped."
                        statusBox.setTextColor(Color.LTGRAY)
                    } catch (e: Exception) {
                        statusBox.text = "Stop Error: ${e.message}"
                    }
                }
            }

            layout.addView(title)
            layout.addView(statusBox)
            layout.addView(intervalLabel)
            layout.addView(seekBar)
            layout.addView(startBtn)
            layout.addView(stopBtn)

            setContentView(layout)
            if (!hasPermissions()) requestAppPermissions()

        } catch (e: Exception) {
            val errView = TextView(this).apply {
                text = "Startup Error:\n${e.stackTraceToString()}"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                setPadding(40, 40, 40, 40)
            }
            setContentView(errView)
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
            Toast.makeText(this, "Please allow 'Appear on top' to keep screen awake!", Toast.LENGTH_LONG).show()
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

    private var wakeLock: PowerManager.WakeLock? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var checkIntervalMs = 20000L
    private var missedDetections = 0

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.10f)
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
        checkIntervalMs = intent?.getLongExtra("INTERVAL", 20000L) ?: 20000L
        try {
            val notif = buildNotification("Active: Keeping screen awake")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(1, notif)
            }
            acquireWakeLock()
            handler.removeCallbacks(checkRunnable)
            handler.post(checkRunnable)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        // 1. Traditional WakeLock (Often ignored by Samsung)
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

        // 2. The Samsung Override: 1-Pixel Invisible Overlay
        try {
            if (overlayView == null && Settings.canDrawOverlays(this)) {
                windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                overlayView = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
                val params = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, // The magic unbreakable flag
                    PixelFormat.TRANSPARENT
                )
                params.gravity = Gravity.TOP or Gravity.START
                windowManager?.addView(overlayView, params)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (_: Exception) {}
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
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        detector.process(image)
                            .addOnSuccessListener { faces ->
                                if (faces.isNotEmpty()) {
                                    missedDetections = 0
                                    acquireWakeLock()
                                    updateNotification("You're here! Screen locked awake.")
                                } else {
                                    missedDetections++
                                    if (missedDetections >= 2) {
                                        releaseWakeLock()
                                        updateNotification("Away (${missedDetections}x) - Letting screen sleep.")
                                    } else {
                                        updateNotification("Missed 1 check - Rechecking soon...")
                                    }
                                }
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
            } catch (e: Exception) {
                updateNotification("Camera busy or blocked.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateNotification(statusText: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification(statusText))
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "study_awake_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "StudyAwake Service", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
            return Notification.Builder(this, channelId)
                .setContentTitle("StudyAwake Assignment Mode")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("StudyAwake Assignment Mode")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        releaseWakeLock()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
