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
        if (AiSettings.hudVisible) showHud()
    }

    private var hud: BotHud? = null

    /** Shows the floating control panel (Start / Stop and live status). */
    fun showHud() {
        mainHandler.post {
            if (hud == null) hud = BotHud(this)
            hud?.show()
            AiSettings.hudVisible = true
        }
    }

    fun hideHud() {
        mainHandler.post {
            hud?.hide()
            AiSettings.hudVisible = false
        }
    }

    fun isHudShown(): Boolean = hud?.isShown() == true

    /** Last app (other than this one) that opened a window. Used when the floating panel steals the "active window". */
    @Volatile private var lastForeign: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != packageName && pkg != "com.android.systemui" && !pkg.contains("inputmethod")) lastForeign = pkg
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        try {
            hud?.hide()
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    fun currentPackage(): String? {
        val p = rootInActiveWindow?.packageName?.toString()
        // Tapping the floating panel makes it the active window; that must not look like "this app is in front",
        // otherwise the bot stops playing until the user touches the game again.
        if (p == packageName && !AppState.inFront) return lastForeign
        if (p == null) return lastForeign
        return p
    }

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
        // Hide the floating panel for a moment so that it is not part of the picture the AI sees.
        val h = hud
        if (h != null) {
            mainHandler.post { h.setInvisible(true) }
        }
        val restore: () -> Unit = { h?.let { hh -> mainHandler.post { hh.setInvisible(false) } } }
        mainHandler.postDelayed({
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            restore()
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
                            restore()
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
                restore()
                AdviceLog.error("اسکرین‌شات اجرا نشد: ${e.message}")
                onResult(null)
            }
        }, if (h != null) 90L else 0L)
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

    /** Turns an action into finger strokes (and the markers that show them). Multi = several fingers at once. */
    private fun collect(
        action: GameAction, w: Float, h: Float,
        strokes: MutableList<GestureDescription.StrokeDescription>,
        markers: MutableList<MarkerItem>
    ) {
        if (strokes.size >= GestureDescription.getMaxStrokeCount()) return
        fun px(v: Float): Float = (v * w).coerceIn(1f, w - 2f)
        fun py(v: Float): Float = (v * h).coerceIn(1f, h - 2f)
        when (action) {
            is GameAction.Tap -> {
                val x = px(action.x)
                val y = py(action.y)
                val path = Path()
                path.moveTo(x, y)
                strokes.add(GestureDescription.StrokeDescription(path, 0L, 90L))
                markers.add(MarkerItem(0, x, y, x, y, 90L))
            }
            is GameAction.Hold -> {
                val x = px(action.x)
                val y = py(action.y)
                val ms = action.durationMs.coerceIn(60L, 8000L)
                val path = Path()
                path.moveTo(x, y)
                strokes.add(GestureDescription.StrokeDescription(path, 0L, ms))
                markers.add(MarkerItem(1, x, y, x, y, ms))
            }
            is GameAction.Swipe -> {
                val x1 = px(action.x1)
                val y1 = py(action.y1)
                val x2 = px(action.x2)
                val y2 = py(action.y2)
                val ms = action.durationMs.coerceIn(60L, 8000L)
                val path = Path()
                path.moveTo(x1, y1)
                if (Math.abs(x2 - x1) + Math.abs(y2 - y1) >= 2f) path.lineTo(x2, y2)
                strokes.add(GestureDescription.StrokeDescription(path, 0L, ms))
                markers.add(MarkerItem(2, x1, y1, x2, y2, ms))
            }
            is GameAction.Multi -> {
                for (part in action.parts) collect(part, w, h, strokes, markers)
            }
            GameAction.Wait -> {
            }
        }
    }

    fun perform(action: GameAction) {
        if (action === GameAction.Wait) return
        val size = displaySize()
        val strokes = ArrayList<GestureDescription.StrokeDescription>()
        val markers = ArrayList<MarkerItem>()
        collect(action, size[0], size[1], strokes, markers)
        if (strokes.isEmpty()) return

        val builder = GestureDescription.Builder()
        for (st in strokes) builder.addStroke(st)
        val accepted = try {
            dispatchGesture(
                builder.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        AdviceLog.error("سیستم لمس را لغو کرد")
                    }
                },
                null
            )
        } catch (e: Exception) {
            AdviceLog.error("لمس اجرا نشد: ${e.message}")
            false
        }
        if (!accepted) {
            AdviceLog.error("سیستم لمس را نپذیرفت (سرویس دسترسی را خاموش و روشن کن)")
        }
        showMarkers(markers)
    }

    /** Time until the last marker disappears (uptime ms). The agent does not take a screenshot before that. */
    @Volatile var markerUntil = 0L
        private set

    private fun showMarkers(markers: List<MarkerItem>) {
        if (!AiSettings.showTouches || markers.isEmpty()) return
        var life = MARKER_MS
        for (m in markers) life = Math.max(life, m.durationMs + 350L)
        markerUntil = android.os.SystemClock.uptimeMillis() + life
        mainHandler.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val view = TouchMarkerView(this, markers, life)
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
                }, life)
            } catch (e: Exception) {
                AdviceLog.error("نمایش نشانگر لمس ممکن نشد: ${e.message}")
            }
        }
    }

    companion object {
        private const val MARKER_MS = 1300L

        @Volatile
        var instance: AutoPlayAccessibilityService? = null
            private set
    }
}
