package com.autoaccept.adb

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Fix: track adbd's auth state from logcat (including lines logged before this service started,
 * e.g. during boot while the keyguard was showing). When USB is connected with the adb function
 * enabled but the state stays "prompt pending" for a while, dismiss a non-secure keyguard if
 * needed, then restart adbd by toggling Settings.Global.ADB_ENABLED off/on. adbd re-sends the
 * auth request and the fresh dialog is clicked by [AdbAccessibilityService].
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

        private const val CHECK_INTERVAL_MS = 10_000L
        private const val PENDING_TIMEOUT_MS = 10_000L

        // Immediate triggers: the prompt is known to be stuck
        private val STUCK_LINES = listOf(
            "prompt currently pending, skipping",
            "isLockScreenMode"
        )
        // A prompt was sent (normally answered by the accessibility service within seconds)
        private val PROMPT_LINES = listOf(
            "prompting user to authorize key",
            "sending prompt"
        )
        private val AUTHORIZED_LINES = listOf("adb client authorized")
        private val DISCONNECT_LINES = listOf("ADB disconnect", "UsbFfs: offline")
    }

    enum class AuthState { UNKNOWN, PENDING, AUTHORIZED }

    @Volatile var authState = AuthState.UNKNOWN
        private set
    @Volatile private var stateSince = System.currentTimeMillis()

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
        thread = Thread({
            seedFromHistory()
            watchLogcat()
        }, "adb-prompt-recovery").apply {
            isDaemon = true
            start()
        }
        handler.postDelayed(periodicCheck, CHECK_INTERVAL_MS)
        Log.i(TAG, "Recovery watcher started (writeSecureSettings=${hasWriteSecureSettings()})")
    }

    private val periodicCheck = object : Runnable {
        override fun run() {
            if (!running) return
            checkNow()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /** USB cable connected to a host with the adb function enabled (sticky USB_STATE broadcast). */
    fun isUsbAdbConnected(): Boolean {
        return try {
            val i = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
                ?: return false
            i.getBooleanExtra("connected", false) && i.getBooleanExtra("adb", false)
        } catch (e: Exception) {
            false
        }
    }

    /** USB + adb present but authorization has been pending too long -> recover. */
    fun checkNow() {
        val pendingFor = System.currentTimeMillis() - stateSince
        if (authState == AuthState.PENDING && pendingFor >= PENDING_TIMEOUT_MS && isUsbAdbConnected()) {
            val since = if (stateSince == 0L) "since before startup" else "for ${pendingFor / 1000}s"
            Log.i(TAG, "USB connected but ADB not authorized $since -> recovering")
            requestRecovery()
        }
    }

    private fun setState(state: AuthState) {
        if (state != authState) {
            authState = state
            stateSince = System.currentTimeMillis()
            Log.i(TAG, "ADB auth state -> $state")
        }
    }

    /** Returns true if the line means the prompt is stuck (needs immediate recovery). */
    private fun classify(line: String): Boolean {
        when {
            AUTHORIZED_LINES.any { line.contains(it) } -> setState(AuthState.AUTHORIZED)
            DISCONNECT_LINES.any { line.contains(it) } -> setState(AuthState.UNKNOWN)
            STUCK_LINES.any { line.contains(it) } -> { setState(AuthState.PENDING); return true }
            PROMPT_LINES.any { line.contains(it) } -> setState(AuthState.PENDING)
        }
        return false
    }

    /** Replay adbd lines already in the log buffer (e.g. a prompt deferred during boot). */
    private fun seedFromHistory() {
        try {
            val p = ProcessBuilder("logcat", "-d", "-v", "brief", "adbd:I", "AdbDebuggingManager:D", "*:S")
                .redirectErrorStream(true).start()
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                while (true) classify(r.readLine() ?: break)
            }
            p.waitFor()
            // Pending since before we started: make it eligible right away
            if (authState == AuthState.PENDING) stateSince = 0L
            Log.i(TAG, "ADB auth state from log history: $authState")
            handler.post { checkNow() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log history", e)
        }
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
                // -T 1: start from the newest line (history was handled by seedFromHistory)
                val p = ProcessBuilder(
                    "logcat", "-v", "brief", "-T", "1",
                    "adbd:I", "AdbDebuggingManager:D", "*:S"
                ).redirectErrorStream(true).start()
                process = p
                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (classify(line)) {
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
