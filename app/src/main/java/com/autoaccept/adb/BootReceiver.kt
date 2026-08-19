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
    }
}
