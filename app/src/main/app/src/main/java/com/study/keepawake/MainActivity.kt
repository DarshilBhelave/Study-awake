package com.study.keepawake

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.util.Size
import android.view.Gravity
import android.widget.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private var intervalSeconds = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "StudyAwake"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val subtitle = TextView(this).apply {
            text = "Checks front camera periodically while you write assignments and keeps your screen awake."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }

        val intervalLabel = TextView(this).apply {
            text = "Check Camera Every: 20 seconds"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
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
                if (hasPermissions()) {
                    try {
                        val intent = Intent(this@MainActivity, KeepAwakeService::class.java)
                        intent.putExtra("INTERVAL", intervalSeconds * 1000L)
                        ContextCompat.startForegroundService(this@MainActivity, intent)
                        Toast.makeText(this@MainActivity, "StudyAwake Running! Switch to your assignment now.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    requestPermissions()
                }
            }
        }

        val stopBtn = Button(this).apply {
            text = "STOP SERVICE"
            setOnClickListener {
                stopService(Intent(this@MainActivity, KeepAwakeService::class.java))
                Toast.makeText(this@MainActivity, "StudyAwake Stopped.", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(intervalLabel)
        layout.addView(seekBar)
        layout.addView(startBtn)
        layout.addView(stopBtn)

        setContentView(layout)
        if (!hasPermissions()) requestPermissions()
    }

    private fun hasPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return cam && notif
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }
}

class KeepAwakeService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
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
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    1,
                    buildNotification("Active: Keeping screen awake"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(1, buildNotification("Active: Keeping screen awake"))
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
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
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
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )
            } catch (e: Exception) {
                updateNotification("Camera busy or in use.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(statusText))
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "study_awake_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "StudyAwake Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
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

            
