package com.autoaccept.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutoAcceptADB_Boot"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return
        Log.i(TAG, "Received broadcast action: $action")

        try {
            KeepAliveService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start KeepAliveService from boot receiver", e)
        }

        if (action == Intent.ACTION_BOOT_COMPLETED || action.endsWith("QUICKBOOT_POWERON")) {
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (e: Exception) {
                // Blocked on Android 10+ background-start limits; the accessibility service launches it instead
                Log.w(TAG, "Could not launch MainActivity from boot receiver", e)
            }
        }
    }
}
