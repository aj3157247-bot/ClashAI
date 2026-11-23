package com.aiplayer.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
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
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.aiplayer.companion.CAPTURE_START"
        const val ACTION_STOP = "com.aiplayer.companion.CAPTURE_STOP"
        const val ACTION_FRAME = "com.aiplayer.companion.CAPTURE_FRAME"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"

        const val EXTRA_BRIGHTNESS = "brightness"
        const val EXTRA_RED = "red"
        const val EXTRA_GREEN = "green"
        const val EXTRA_BLUE = "blue"
        const val EXTRA_DESCRIPTION = "description"

        private const val CHANNEL_ID = "ai_player_screen_capture"
        private const val NOTIFICATION_ID = 1001
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var frameCounter = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForegroundSafely()

                val resultCode =
                    intent.getIntExtra(EXTRA_RESULT_CODE, 0)

                val data = getProjectionIntent(intent)

                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startCapture(resultCode, data)
            }
        }

        return START_NOT_STICKY
    }

    private fun getProjectionIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
    }

    private fun startForegroundSafely() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {
        if (projection != null) return

        try {
            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            val currentProjection = projection ?: run {
                stopSelf()
                return
            }

            workerThread = HandlerThread("AIPlayerFrameAnalyzer").also {
                it.start()
            }

            workerHandler = Handler(workerThread!!.looper)

            val metrics = getScreenMetrics()

            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            currentProjection.registerCallback(
                projectionCallback,
                workerHandler
            )

            imageReader?.setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                    image.use {
                        frameCounter++

                        // برای جلوگیری از مصرف زیاد باتری،
                        // هر چند فریم یک بار تحلیل انجام می‌شود.
                        if (frameCounter % 8 == 0) {
                            analyzeFrame(it)
                        }
                    }
                },
                workerHandler
            )

            virtualDisplay = currentProjection.createVirtualDisplay(
                "AIPlayer-ScreenObserver",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                workerHandler
            )

            sendDiagnostic(
                brightness = 0,
                red = 0,
                green = 0,
                blue = 0,
                description = "مشاهده صفحه فعال شد. در حال دریافت تصویر..."
            )

        } catch (error: Exception) {
            sendDiagnostic(
                0, 0, 0, 0,
                "خطا در شروع مشاهده صفحه: ${error.message ?: "نامشخص"}"
            )
            stopCapture()
            stopSelf()
        }
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        val windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        return metrics
    }

    /**
     * تحلیل ساده رنگ و روشنایی تصویر.
     * این نسخه کارت‌ها، سربازها یا اکسیر را با دقت تشخیص نمی‌دهد.
     */
    private fun analyzeFrame(image: Image) {
        FrameBus.publishImage(image)
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            if (width <= 0 || height <= 0 || pixelStride < 4) return

            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            var redTotal = 0L
            var greenTotal = 0L
            var blueTotal = 0L
            var samples = 0

            // نمونه‌برداری پراکنده از تصویر برای کاهش مصرف پردازنده
            val stepX = max(1, width / 40)
            val stepY = max(1, height / 40)

            var y = stepY / 2

            while (y < height) {
                var x = stepX / 2

                while (x < width) {
                    val index =
                        y * rowStride + x * pixelStride

                    if (index >= 0 && index + 2 < bytes.size) {
                        redTotal += bytes[index].toInt() and 0xFF
                        greenTotal += bytes[index + 1].toInt() and 0xFF
                        blueTotal += bytes[index + 2].toInt() and 0xFF
                        samples++
                    }

                    x += stepX
                }

                y += stepY
            }

            if (samples == 0) return

            val red = (redTotal / samples).toInt()
            val green = (greenTotal / samples).toInt()
            val blue = (blueTotal / samples).toInt()

            val brightness =
                ((red * 299 + green * 587 + blue * 114) / 1000)

            val description = when {
                brightness < 45 ->
                    "تصویر بسیار تاریک است."
                red > green * 1.25 && red > blue * 1.15 ->
                    "رنگ قرمز در نمونه تصویر غالب است."
                blue > red * 1.15 && blue > green * 1.05 ->
                    "رنگ آبی در نمونه تصویر غالب است."
                else ->
                    "تصویر دریافت شد؛ رنگ غالب مشخص نیست."
            }

            sendDiagnostic(
                brightness,
                red,
                green,
                blue,
                description
            )

        } catch (error: Exception) {
            sendDiagnostic(
                0, 0, 0, 0,
                "خطا در تحلیل فریم: ${error.message ?: "نامشخص"}"
            )
        }
    }

    private fun sendDiagnostic(
        brightness: Int,
        red: Int,
        green: Int,
        blue: Int,
        description: String
    ) {
        val intent = Intent(ACTION_FRAME).apply {
            setPackage(packageName)
            putExtra(EXTRA_BRIGHTNESS, brightness)
            putExtra(EXTRA_RED, red)
            putExtra(EXTRA_GREEN, green)
            putExtra(EXTRA_BLUE, blue)
            putExtra(EXTRA_DESCRIPTION, description)
        }

        sendBroadcast(intent)
    }

    private fun stopCapture() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()

            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {
        }

        virtualDisplay = null
        imageReader = null
        projection = null

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Player Screen Observer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "مشاهده صفحه با اجازه کاربر"
            }

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AI Player فعال است")
                .setContentText("مشاهده صفحه با اجازه شما در حال اجراست.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AI Player فعال است")
                .setContentText("مشاهده صفحه فعال است.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
