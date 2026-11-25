package com.aiplayer.companion

import android.os.Handler
import android.os.HandlerThread

/**
 * Main loop: take the latest frame, find the matching game module,
 * ask it for an action, and perform that action.
 */
object AgentLoop {

    private const val TICK_MS = 300L
    private const val OWN_PACKAGE = "com.aiplayer.companion"

    @Volatile
    private var running = false

    private val thread = HandlerThread("AgentLoop").apply { start() }
    private val handler = Handler(thread.looper)

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                step()
            } catch (e: Throwable) {
                // Keep the loop alive if a single frame fails, and show why.
                AdviceLog.error("خطای حلقه: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun isRunning(): Boolean = running

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    @Volatile
    private var capturing = false

    // Android allows only a few screenshots per second, so space them out.
    private const val CAPTURE_GAP_MS = 700L
    private var lastCaptureAt = 0L

    private fun step() {
        val service = AutoPlayAccessibilityService.instance
        if (service == null) {
            AdviceLog.info("سرویس دسترسی متصل نیست؛ در تنظیمات دسترسی آن را خاموش و دوباره روشن کن")
            return
        }
        val pkg = service.currentPackage()
        // Some phones or games hide the package name (it can be null). Still try to act in that case.
        // Only skip when this app itself is in front.
        if (pkg == OWN_PACKAGE) {
            FrameBus.take()?.recycle()
            AdviceLog.info("AI Player جلوی صفحه است. بازی را جلو بیاور.")
            return
        }

        // Ask for a fresh screenshot. Only one request at a time.
        val now = System.currentTimeMillis()
        if (!capturing && now - lastCaptureAt >= CAPTURE_GAP_MS) {
            capturing = true
            lastCaptureAt = now
            service.captureFrame { bmp ->
                capturing = false
                if (bmp != null) FrameBus.publish(bmp)
            }
        }

        val frame = FrameBus.take() ?: return
        try {
            val module = GameRegistry.find(pkg, frame)
            if (module == null) {
                DebugDump.save(frame, "no_module")
                AdviceLog.info(
                    if (AiSettings.cloudAllowed())
                        "کلید API خالی است؛ آن را در تنظیمات ذخیره کن"
                    else
                        "حالت آفلاین: فقط لودو و شطرنج (از وضعیت شروع) پشتیبانی می‌شود. برای بازی‌های دیگر «آنلاین» یا «هوشمند» را انتخاب کن"
                )
                return
            }
            val action = module.decide(frame)
            service.perform(action)
        } finally {
            frame.recycle()
        }
    }
}
