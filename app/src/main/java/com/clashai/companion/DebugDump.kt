package com.aiplayer.companion

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Saves a screenshot when an offline bot cannot read the screen, so the problem can be studied later.
 * Files go to /Android/data/com.aiplayer.companion/files/debug/ (visible in a file manager).
 * At most one file every 15 seconds and 12 files per run, so it never fills the phone.
 */
object DebugDump {
    private var last = 0L
    private var count = 0

    @Synchronized
    fun save(frame: Bitmap, tag: String) {
        val ctx = AiSettings.appContext ?: return
        val now = System.currentTimeMillis()
        if (now - last < 15000L || count >= 12) return
        last = now
        count++
        try {
            val dir = File(ctx.getExternalFilesDir(null), "debug")
            dir.mkdirs()
            FileOutputStream(File(dir, tag + "_" + now + ".png")).use { out ->
                frame.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (t: Throwable) {
            // Debug output must never break the bot.
        }
    }
}
