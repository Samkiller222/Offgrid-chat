package com.offgrid.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resume relaying after a reboot, so a phone left in a drawer still carries messages. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!MeshService.isEnabled(context)) return
        if (!MeshService.hasPermissions(context)) return
        runCatching { MeshService.start(context) }
    }
}
