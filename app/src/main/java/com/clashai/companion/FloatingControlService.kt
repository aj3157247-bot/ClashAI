package com.aiplayer.companion

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Kept for compatibility with the "show panel" button: it only asks the accessibility service to show the
 * floating control panel (BotHud) and ends itself. The panel needs no "display over other apps" permission.
 */
class FloatingControlService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val svc = AutoPlayAccessibilityService.instance
        if (svc != null) {
            svc.showHud()
        } else {
            AdviceLog.error("سرویس دسترسی روشن نیست؛ پنل ربات نمایش داده نمی‌شود")
        }
        stopSelf()
    }
}
