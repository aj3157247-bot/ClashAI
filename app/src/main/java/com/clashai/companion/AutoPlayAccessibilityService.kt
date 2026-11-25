package com.aiplayer.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * Performs taps, holds and swipes on the screen, and shows a pink marker where it touched.
 * The user must enable this service in Android Settings > Accessibility.
 */
class AutoPlayAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Size of the last screenshot. Gestures use the same full-display pixel space.
    @Volatile private var lastFrameW = 0
    @Volatile private var lastFrameH = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /**
     * Takes a screenshot on demand (Android 12+). Unlike MediaProjection, this works
     * even when the game screen is static. The callback runs on the main thread.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun captureFrame(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            AdviceLog.error("اسکرین‌شات از اندروید ۱۲ به بالا پشتیبانی می‌شود.")
            onResult(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hardware = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        hardware?.recycle()
                        screenshot.hardwareBuffer.close()
                        if (software != null) {
                            lastFrameW = software.width
                            lastFrameH = software.height
                        }
                        onResult(software)
                    }

                    override fun onFailure(errorCode: Int) {
                        val reason = when (errorCode) {
                            3 -> "درخواست اسکرین‌شات خیلی سریع فرستاده شد"
                            2 -> "دسترسی سرویس به اسکرین‌شات داده نشده"
                            else -> "خطای سیستم"
                        }
                        AdviceLog.error("اسکرین‌شات ناموفق بود: $reason (کد $errorCode)")
                        onResult(null)
                    }
                }
            )
        } catch (e: Exception) {
            AdviceLog.error("اسکرین‌شات اجرا نشد: ${e.message}")
            onResult(null)
        }
    }

    /** Full display size in pixels, the same space the screenshot and the gestures use. */
    private fun displaySize(): FloatArray {
        if (lastFrameW > 0 && lastFrameH > 0) {
            return floatArrayOf(lastFrameW.toFloat(), lastFrameH.toFloat())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val b = wm.maximumWindowMetrics.bounds
            return floatArrayOf(b.width().toFloat(), b.height().toFloat())
        }
        val m = resources.displayMetrics
        return floatArrayOf(m.widthPixels.toFloat(), m.heightPixels.toFloat())
    }

    fun perform(action: GameAction) {
        val size = displaySize()
        val w = size[0]
        val h = size[1]

        val path = Path()
        val points: FloatArray
        val stroke = when (action) {
            is GameAction.Tap -> {
                points = floatArrayOf(action.x * w, action.y * h)
                path.moveTo(points[0], points[1])
                GestureDescription.StrokeDescription(path, 0L, 90L)
            }
            is GameAction.Hold -> {
                points = floatArrayOf(action.x * w, action.y * h)
                path.moveTo(points[0], points[1])
                GestureDescription.StrokeDescription(path, 0L, action.durationMs)
            }
            is GameAction.Swipe -> {
                points = floatArrayOf(action.x1 * w, action.y1 * h, action.x2 * w, action.y2 * h)
                path.moveTo(points[0], points[1])
                path.lineTo(points[2], points[3])
                GestureDescription.StrokeDescription(path, 0L, action.durationMs)
            }
            GameAction.Wait -> return
        }

        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    AdviceLog.error("سیستم لمس را لغو کرد")
                }
            },
            null
        )
        if (!accepted) {
            AdviceLog.error("سیستم لمس را نپذیرفت (canPerformGestures را بررسی کن)")
        }
        showMarker(points)
    }

    private fun showMarker(points: FloatArray) {
        if (!AiSettings.showTouches) return
        mainHandler.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val view = TouchMarkerView(this, points)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                wm.addView(view, lp)
                mainHandler.postDelayed({
                    try {
                        wm.removeView(view)
                    } catch (e: Exception) {
                        // already removed
                    }
                }, MARKER_MS)
            } catch (e: Exception) {
                AdviceLog.error("نمایش نشانگر لمس ممکن نشد: ${e.message}")
            }
        }
    }

    companion object {
        private const val MARKER_MS = 900L

        @Volatile
        var instance: AutoPlayAccessibilityService? = null
            private set
    }
}
