package com.brestkorej.t5

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Автозапуск при включении магнитолы. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = ctx.getSharedPreferences("t5", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", true)) return
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
