package com.aiplayer.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * Performs taps, holds and swipes on the screen. The user must enable this
 * service in Android Settings > Accessibility.
 */
class AutoPlayAccessibilityService : AccessibilityService() {

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
     * Takes a screenshot on demand (Android 11+). Unlike MediaProjection, this works
     * even when the game screen is static. The callback runs on the main thread.
     */
    fun captureFrame(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
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
                    onResult(software)
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null)
                }
            }
        )
    }

    fun perform(action: GameAction) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        val path = Path()
        val stroke = when (action) {
            is GameAction.Tap -> {
                path.moveTo(action.x * w, action.y * h)
                GestureDescription.StrokeDescription(path, 0L, 50L)
            }
            is GameAction.Hold -> {
                path.moveTo(action.x * w, action.y * h)
                GestureDescription.StrokeDescription(path, 0L, action.durationMs)
            }
            is GameAction.Swipe -> {
                path.moveTo(action.x1 * w, action.y1 * h)
                path.lineTo(action.x2 * w, action.y2 * h)
                GestureDescription.StrokeDescription(path, 0L, action.durationMs)
            }
            GameAction.Wait -> return
        }

        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    companion object {
        @Volatile
        var instance: AutoPlayAccessibilityService? = null
            private set
    }
}
