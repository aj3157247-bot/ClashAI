package com.aiplayer.companion

import android.os.Handler
import android.os.HandlerThread

/**
 * Main loop: take the latest frame, find the matching game module,
 * ask it for an action, and perform that action.
 */
object AgentLoop {

    private const val TICK_MS = 200L
    private const val FAST_TICK_MS = 150L

    /** In "hybrid" mode: if a local game module has done nothing for this long, the cloud AI takes one look. */
    private const val STUCK_MS = 30_000L
    private const val CLOUD_ID = "grok_general"
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
            handler.postDelayed(this, if (AiSettings.realtime) FAST_TICK_MS else TICK_MS)
        }
    }

    fun isRunning(): Boolean = running

    fun start() {
        if (running) return
        running = true
        capturing = false
        captureStartedAt = 0L
        captureToken++
        lastActionAt = System.currentTimeMillis()
        AdviceLog.info("ربات روشن شد؛ در حال نگاه کردن به صفحه...")
        handler.post(tick)
    }

    fun stop() {
        running = false
        if (LearningCoach.learning) {
            LearningCoach.stopLearning()
            AutoPlayAccessibilityService.instance?.setLearnCapture(false)
        }
        if (LearningCoach.playing) LearningCoach.stopPlaying()
        handler.removeCallbacks(tick)
        // Invalidate any callback that may arrive after Stop.
        captureToken++
        capturing = false
        captureStartedAt = 0L
        AdviceLog.info("ربات متوقف شد")
    }

    @Volatile
    private var capturing = false

    // Android screenshot callbacks can occasionally be lost. Expire a stuck request
    // so the agent can recover without requiring the user to restart it.
    @Volatile
    private var captureStartedAt = 0L
    @Volatile
    private var captureToken = 0L

    private const val CAPTURE_GAP_MS = 350L
    private const val FAST_CAPTURE_GAP_MS = 280L
    private const val CAPTURE_TIMEOUT_MS = 2500L
    private var lastCaptureAt = 0L
    private var lastActionAt = 0L

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
        val gap = if (AiSettings.realtime) FAST_CAPTURE_GAP_MS else CAPTURE_GAP_MS
        if (capturing && now - captureStartedAt > CAPTURE_TIMEOUT_MS) {
            // A timed-out callback may still arrive later; its token prevents it
            // from publishing an obsolete frame over a newer screenshot.
            capturing = false
            captureStartedAt = 0L
            captureToken++
            AdviceLog.error("گرفتن تصویر گیر کرد؛ تلاش دوباره...")
        }
        if (!capturing && now - lastCaptureAt >= gap) {
            capturing = true
            captureStartedAt = now
            lastCaptureAt = now
            val token = ++captureToken
            try {
                service.captureFrame { bmp ->
                    if (token == captureToken) {
                        capturing = false
                        captureStartedAt = 0L
                        if (bmp != null) FrameBus.publish(bmp)
                    } else {
                        // Ignore and release a late frame from an expired request.
                        bmp?.recycle()
                    }
                }
            } catch (e: Throwable) {
                if (token == captureToken) {
                    capturing = false
                    captureStartedAt = 0L
                }
                throw e
            }
        }

        val frame = FrameBus.take() ?: return
        try {
            if (LearningCoach.learning) {
                // Learn mode: only watch. The user plays; the bot never touches the screen.
                LearningCoach.onFrame(pkg, frame)
                return
            }
            val module = GameRegistry.find(pkg, frame)
            if (module == null) {
                DebugDump.save(frame, "no_module")
                AdviceLog.info(
                    if (AiSettings.cloudAllowed())
                        "کلید API خالی است؛ آن را در تنظیمات ذخیره کن"
                    else
                        "حالت آفلاین: فقط لودو و شطرنج پشتیبانی می‌شود (شطرنج حتی وسط بازی). برای بازی‌های دیگر «آنلاین» یا «هوشمند» را انتخاب کن"
                )
                return
            }
            var action = module.decide(frame)
            val t = System.currentTimeMillis()
            if (action === GameAction.Wait && module.id != CLOUD_ID && AiSettings.mode == AiSettings.MODE_AUTO &&
                AiSettings.cloudAllowed() && t - lastActionAt >= STUCK_MS
            ) {
                // The local module has been idle for a long time (a menu, an ad, a "game over" dialog?).
                // Let the cloud AI look at the screen once.
                val cloud = GameRegistry.byId(CLOUD_ID)
                if (cloud != null && cloud.matches(pkg, frame)) {
                    AdviceLog.info("مدتی است کاری نشده؛ هوش مصنوعی ابری صفحه را بررسی می‌کند")
                    action = cloud.decide(frame)
                }
            }
            if (action !== GameAction.Wait) lastActionAt = t
            service.perform(action)
        } finally {
            frame.recycle()
        }
    }
}
