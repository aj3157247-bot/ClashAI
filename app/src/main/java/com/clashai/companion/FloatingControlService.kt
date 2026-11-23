package com.aiplayer.companion

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button

/** Shows a floating Start/Stop button on top of other apps. */
class FloatingControlService : Service() {

    private var windowManager: WindowManager? = null
    private var button: Button? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val btn = Button(this).apply {
            text = "شروع"
            setOnClickListener {
                if (AgentLoop.isRunning()) {
                    AgentLoop.stop()
                } else {
                    AgentLoop.start()
                }
                text = if (AgentLoop.isRunning()) "توقف" else "شروع"
            }
        }
        button = btn

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }
        windowManager?.addView(btn, params)
    }

    override fun onDestroy() {
        AgentLoop.stop()
        button?.let { windowManager?.removeView(it) }
        button = null
        super.onDestroy()
    }
}
