package com.autoaccept.adb

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Recovers from a "stuck" ADB authorization prompt.
 *
 * Problem: when a new host connects while the phone is on the lock screen, Samsung defers the
 * confirmation ("startConfirmation: isLockScreenMode"). adbd then believes a prompt is pending and
 * skips every later request ("prompt currently pending, skipping"), even after unlock or re-plug,
 * so the device stays "unauthorized" forever.
 *
 * Fix: watch logcat for those lines; when seen, dismiss a non-secure keyguard if needed, then
 * restart adbd by toggling Settings.Global.ADB_ENABLED off/on. adbd re-sends the auth request and
 * the fresh dialog is clicked by [AdbAccessibilityService].
 *
 * Requires permissions granted once over adb (see setup_unattended.sh):
 *   pm grant com.autoaccept.adb android.permission.READ_LOGS
 *   pm grant com.autoaccept.adb android.permission.WRITE_SECURE_SETTINGS
 */
class AdbPromptRecovery(private val context: Context) {

    companion object {
        private const val TAG = "AutoAcceptADB"
        private const val MIN_INTERVAL_MS = 30_000L
        private const val UNLOCK_POLL_MS = 2_000L

        private val TRIGGERS = listOf(
            "prompt currently pending, skipping",
            "isLockScreenMode"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var process: Process? = null
    private var thread: Thread? = null
    private var lastRestart = 0L
    private var waitingForUnlock = false

    fun hasReadLogs() = granted(Manifest.permission.READ_LOGS)
    fun hasWriteSecureSettings() = granted(Manifest.permission.WRITE_SECURE_SETTINGS)

    private fun granted(p: String) =
        context.packageManager.checkPermission(p, context.packageName) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running) return
        if (!hasReadLogs()) {
            // Permission may be granted later over adb (pm grant); check again periodically
            Log.w(TAG, "Recovery waiting: READ_LOGS not granted yet")
            handler.postDelayed({ start() }, 15_000L)
            return
        }
        running = true
        thread = Thread({ watchLogcat() }, "adb-prompt-recovery").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Recovery watcher started (writeSecureSettings=${hasWriteSecureSettings()})")
    }

    fun stop() {
        running = false
        process?.destroy()
        process = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun watchLogcat() {
        while (running) {
            try {
                // -T 1: only new lines from now on
                val p = ProcessBuilder(
                    "logcat", "-v", "brief", "-T", "1",
                    "adbd:I", "AdbDebuggingManager:D", "*:S"
                ).redirectErrorStream(true).start()
                process = p
                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (TRIGGERS.any { line.contains(it) }) {
                            Log.i(TAG, "Recovery trigger: $line")
                            handler.post { requestRecovery() }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recovery watcher error", e)
            }
            if (running) Thread.sleep(3_000L)
        }
    }

    private fun requestRecovery() {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            if (!km.isKeyguardSecure) {
                // Swipe-only lock: dismiss it so the dialog can be shown
                try {
                    context.startActivity(
                        Intent(context, DismissKeyguardActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot launch keyguard dismiss activity", e)
                }
            } else {
                Log.w(TAG, "Secure lock screen: waiting for user unlock before restarting adbd")
            }
            if (!waitingForUnlock) {
                waitingForUnlock = true
                pollUnlock()
            }
            return
        }
        restartAdbd()
    }

    private fun pollUnlock() {
        handler.postDelayed({
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked) {
                pollUnlock()
            } else {
                waitingForUnlock = false
                restartAdbd()
            }
        }, UNLOCK_POLL_MS)
    }

    private fun restartAdbd() {
        val now = System.currentTimeMillis()
        if (now - lastRestart < MIN_INTERVAL_MS) return
        if (!hasWriteSecureSettings()) {
            Log.w(TAG, "Cannot restart adbd: WRITE_SECURE_SETTINGS not granted")
            return
        }
        lastRestart = now
        try {
            Log.i(TAG, "🔁 Restarting adbd (USB debugging off/on) to re-show the auth prompt")
            Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
            handler.postDelayed({
                try {
                    Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
                    Log.i(TAG, "USB debugging re-enabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to re-enable USB debugging", e)
                }
            }, 1_500L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart adbd", e)
        }
    }
}
