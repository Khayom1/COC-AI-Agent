package com.khayom.cocai

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.content.pm.ServiceInfo
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

class CaptureOverlayService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null

    @Volatile private var latestFrame: Bitmap? = null
    private val frameLock = Any()
    private var lastFrameAt = 0L
    private var lastForegroundCheckAt = 0L
    @Volatile private var lastForegroundAllowed = false
    private var stoppingProjection = false
    private val analyzing = AtomicBoolean(false)

    private var windowManager: WindowManager? = null
    private var overlayView: android.view.View? = null
    private var overlayStatus: TextView? = null
    private var isCapturing = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("COC-AI-Capture").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CAPTURE -> stopProjectionKeepOverlay()
            ACTION_STOP_ALL -> {
                stopProjectionResources()
                isCapturing = false
                removeOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_ANALYZE -> analyzeLatestFrame()
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.parcelableIntent(EXTRA_PROJECTION_DATA)
                if (resultCode == 0 || data == null) {
                    updateStatus("Иҷозати сабти экран дастрас нест.")
                    return START_NOT_STICKY
                }
                startAsForeground(capturing = true)
                startProjection(resultCode, data)
                addOverlay()
            }
            else -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground(capturing = false)
                addOverlay()
            }
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        stopProjectionResources()
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stoppingProjection) stopProjectionKeepOverlay()
                }
            }, workerHandler)

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader?.setOnImageAvailableListener({ reader ->
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val now = SystemClock.elapsedRealtime()
                    // Read/process at no more than 30 frames per second.
                    if (now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                    lastFrameAt = now

                    if (!isAllowedGameForeground()) {
                        updateStatus("Таваққуф: танҳо Clash of Clans скан мешавад.")
                        return@setOnImageAvailableListener
                    }

                    val bitmap = imageToBitmap(image) ?: return@setOnImageAvailableListener
                    synchronized(frameLock) {
                        val old = latestFrame
                        latestFrame = bitmap
                        if (old != null && !old.isRecycled) old.recycle()
                    }
                    isCapturing = true
                    overlayStatus?.post { overlayStatus?.text = "COC • 30 FPS • ACTIVE" }
                    updateNotification("Мониторинги Achievement фаъол аст.")
                } catch (e: Exception) {
                    updateStatus("Хатои гирифтани экран: ${e.message ?: "номаълум"}")
                } finally {
                    image?.close()
                }
            }, workerHandler)

            virtualDisplay = projection?.createVirtualDisplay(
                "COC-AI-Achievement-Scanner",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                workerHandler
            )
            updateStatus("Сканер фаъол аст. Ҳадди коркард: 30 кадр/сония.")
        } catch (e: Exception) {
            stopProjectionKeepOverlay()
            updateStatus("Сабти экран оғоз нашуд: ${e.message ?: "номаълум"}")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 4) return null
        val paddingPixels = (rowStride - pixelStride * image.width) / pixelStride
        val paddedWidth = image.width + paddingPixels
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun isAllowedGameForeground(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastForegroundCheckAt < 300) return lastForegroundAllowed
        lastForegroundCheckAt = now

        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val usageAllowed = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
        if (!usageAllowed) {
            lastForegroundAllowed = false
            updateStatus("Usage Access хомӯш шуд; сканер таваққуф кард.")
            return false
        }

        return try {
            val usage = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usage.queryEvents(end - 10_000, end)
            val event = UsageEvents.Event()
            var latestPackage: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val foreground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= 29 &&
                        event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                if (foreground && event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    latestPackage = event.packageName
                }
            }
            lastForegroundAllowed = latestPackage == ALLOWED_PACKAGE
            lastForegroundAllowed
        } catch (_: Exception) {
            lastForegroundAllowed = false
            false
        }
    }

    private fun analyzeLatestFrame() {
        if (!isCapturing || !isAllowedGameForeground()) {
            updateStatus("Барои SCAN, Clash of Clans бояд кушода ва мониторинг фаъол бошад.")
            return
        }
        if (!analyzing.compareAndSet(false, true)) {
            updateStatus("Як таҳлил аллакай иҷро шуда истодааст.")
            return
        }

        val copy = synchronized(frameLock) {
            latestFrame?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (copy == null) {
            analyzing.set(false)
            updateStatus("Кадри экран ҳоло дастрас нест.")
            return
        }

        Thread {
            try {
                val key = SecureKeyStore.read(this)
                if (key.isNullOrBlank()) {
                    throw IllegalStateException("Аввал DeepSeek API Key-ро дар Settings ворид кун.")
                }
                val result = DeepSeekClient.analyzeAchievements(copy, key)
                publishResult(result)
                updateNotification("Таҳлил анҷом ёфт. Барои дидани натиҷа барномаро кушо.")
                updateStatus("Таҳлил анҷом ёфт.")
            } catch (e: Exception) {
                val message = "Хатои таҳлил: ${e.message ?: "номаълум"}"
                publishResult(message)
                updateNotification(message)
                updateStatus(message)
            } finally {
                if (!copy.isRecycled) copy.recycle()
                analyzing.set(false)
            }
        }.start()
    }

    private fun publishResult(result: String) {
        getSharedPreferences("coc_ai_results", MODE_PRIVATE)
            .edit().putString("last_analysis", result).apply()
        sendBroadcast(Intent(ACTION_RESULT)
            .setPackage(packageName)
            .putExtra(EXTRA_RESULT, result))
    }

    private fun stopProjectionKeepOverlay() {
        stopProjectionResources()
        isCapturing = false
        overlayStatus?.post { overlayStatus?.text = "COC • STOPPED" }
        startAsForeground(capturing = false)
        updateNotification("Сканер қатъ аст. Барои оғоз START-ро пахш кун.")
    }

    private fun stopProjectionResources() {
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        val old = projection
        projection = null
        if (old != null) {
            stoppingProjection = true
            try { old.stop() } catch (_: Exception) { }
            stoppingProjection = false
        }
        synchronized(frameLock) {
            latestFrame?.let { if (!it.isRecycled) it.recycle() }
            latestFrame = null
        }
    }

    private fun startAsForeground(capturing: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (capturing) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification, types)
        } else if (Build.VERSION.SDK_INT >= 29 && capturing) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, CaptureOverlayService::class.java)
            .setAction(ACTION_STOP_CAPTURE)
        val pending = PendingIntent.getService(
            this, 77, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("COC AI Agent")
            .setContentText(if (isCapturing) "Achievement scanner фаъол аст" else "Floating control фаъол аст")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "STOP", pending).build())
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "COC AI Agent", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("COC AI Agent")
            .setContentText(message.take(180))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateStatus(message: String) {
        overlayStatus?.post { overlayStatus?.text = "COC • ${message.take(46)}" }
        getSharedPreferences("coc_ai_results", MODE_PRIVATE)
            .edit().putString("service_status", message).apply()
    }

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this) || overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 20, 29, 43))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(74, 112, 151))
            }
            setPadding(dp(7), dp(5), dp(7), dp(5))
        }
        val header = TextView(this).apply {
            text = "COC AI • 30 FPS  ⋮"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(7), dp(4), dp(7), dp(5))
        }
        overlayStatus = TextView(this).apply {
            text = if (isCapturing) "ACTIVE" else "STOPPED"
            textSize = 10f
            setTextColor(Color.LTGRAY)
            setPadding(dp(7), dp(2), dp(7), dp(4))
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(overlayButton("START", Color.rgb(30, 135, 70)) {
            val open = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_START_FROM_OVERLAY
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(open)
        })
        actions.addView(overlayButton("SCAN", Color.rgb(45, 88, 150)) {
            startService(Intent(this, CaptureOverlayService::class.java).setAction(ACTION_ANALYZE))
        })
        actions.addView(overlayButton("STOP", Color.rgb(168, 48, 48)) {
            startService(Intent(this, CaptureOverlayService::class.java).setAction(ACTION_STOP_CAPTURE))
        })
        panel.addView(header)
        panel.addView(overlayStatus)
        panel.addView(actions)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = params.x
                    dragStartY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = dragStartX + (event.rawX - touchStartX).toInt()
                    params.y = dragStartY + (event.rawY - touchStartY).toInt()
                    try { windowManager?.updateViewLayout(panel, params) } catch (_: Exception) { }
                    true
                }
                else -> true
            }
        }

        try {
            windowManager?.addView(panel, params)
            overlayView = panel
        } catch (e: Exception) {
            updateStatus("Overlay илова нашуд: ${e.message ?: "хатогӣ"}")
        }
    }

    private fun overlayButton(label: String, color: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(8), dp(7), dp(8))
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(7).toFloat()
            }
            setOnClickListener { action() }
            val params = LinearLayout.LayoutParams(0, dp(36), 1f)
            params.marginEnd = dp(3)
            layoutParams = params
        }

    private fun removeOverlay() {
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        overlayView = null
        overlayStatus = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopProjectionResources()
        removeOverlay()
        try { worker?.quitSafely() } catch (_: Exception) { }
        worker = null
        workerHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun Intent.parcelableIntent(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java)
        else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }

    companion object {
        const val ALLOWED_PACKAGE = "com.supercell.clashofclans"
        const val ACTION_START_CAPTURE = "com.khayom.cocai.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.khayom.cocai.STOP_CAPTURE"
        const val ACTION_STOP_ALL = "com.khayom.cocai.STOP_ALL"
        const val ACTION_ANALYZE = "com.khayom.cocai.ANALYZE"
        const val ACTION_RESULT = "com.khayom.cocai.RESULT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_RESULT = "result"
        private const val CHANNEL_ID = "coc_ai_monitor"
        private const val NOTIFICATION_ID = 103
        private const val FRAME_INTERVAL_MS = 33L
    }
}
