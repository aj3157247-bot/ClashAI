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
            } catch (e: Exception) {
                // Keep the loop alive if a single frame fails.
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

    private fun step() {
        val service = AutoPlayAccessibilityService.instance ?: return
        val pkg = service.currentPackage()
        if (pkg == null || pkg == OWN_PACKAGE) {
            FrameBus.take()?.recycle()
            return
        }

        // Ask for a fresh screenshot. Only one request at a time.
        if (!capturing) {
            capturing = true
            service.captureFrame { bmp ->
                capturing = false
                if (bmp != null) FrameBus.publish(bmp)
            }
        }

        val frame = FrameBus.take() ?: return
        try {
            val module = GameRegistry.find(pkg, frame) ?: return
            val action = module.decide(frame)
            service.perform(action)
        } finally {
            frame.recycle()
        }
    }
}
