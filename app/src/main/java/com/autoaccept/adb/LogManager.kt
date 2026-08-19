package com.autoaccept.adb

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LogManager {
    private const val PREFS_NAME = "auto_accept_adb_logs"
    private const val KEY_LOGS = "saved_logs"
    private const val MAX_LOGS = 60

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val logList = CopyOnWriteArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs?.getString(KEY_LOGS, "") ?: ""
            if (saved.isNotEmpty()) {
                val lines = saved.split("\n").filter { it.isNotBlank() }
                logList.clear()
                logList.addAll(lines)
            }
        }
    }

    fun addLog(message: String) {
        val timeStr = dateFormat.format(Date())
        val entry = "[$timeStr] $message"
        
        logList.add(0, entry)
        while (logList.size > MAX_LOGS) {
            logList.removeAt(logList.size - 1)
        }

        val fullText = getLogs()
        prefs?.edit()?.putString(KEY_LOGS, fullText)?.apply()

        listeners.forEach { it.invoke(fullText) }
    }

    fun getLogs(): String {
        return if (logList.isEmpty()) {
            ""
        } else {
            logList.joinToString("\n")
        }
    }

    fun clearLogs() {
        logList.clear()
        prefs?.edit()?.remove(KEY_LOGS)?.apply()
        listeners.forEach { it.invoke("") }
    }

    fun registerListener(listener: (String) -> Unit) {
        listeners.add(listener)
        listener.invoke(getLogs())
    }

    fun unregisterListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
