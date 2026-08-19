package com.autoaccept.adb

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AdbAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastHandledTime = 0L
    private var lastInspectionTime = 0L
    private var isHandlingDialog = false
    private val debounceIntervalMs = 5000L // 5 秒防重複觸發間隔

    companion object {
        private const val TAG = "AutoAcceptADB"
        var isServiceRunning = false
            private set

        // 1. Explicit Full Phrases (English & Chinese)
        private val EXACT_ADB_PHRASES = listOf(
            // English
            "usb debugging",
            "allow usb debugging",
            "wireless debugging",
            "allow wireless debugging",
            "rsa key fingerprint",
            "rsa key",
            "always allow from this computer",
            "adb debugging",
            // Traditional Chinese (繁體中文)
            "usb 偵錯",
            "usb偵錯",
            "允許 usb 偵錯",
            "允許usb偵錯",
            "無線偵錯",
            "允許無線偵錯",
            "rsa 金鑰",
            "rsa金鑰",
            "rsa 金錀",
            "rsa金錀",
            "金鑰指紋",
            "金錀指紋",
            "一律允許透過這部電腦進行偵錯",
            "始終允許透過這部電腦進行偵錯",
            "這部電腦進行偵錯",
            "偵錯授權",
            // Simplified Chinese (簡體中文)
            "usb 调试",
            "usb调试",
            "允许 usb 调试",
            "允许usb调试",
            "无线调试",
            "允许无线调试",
            "rsa 密钥",
            "rsa密钥",
            "密钥指纹",
            "始终允许使用这台计算机进行调试",
            "允许调试"
        )

        // 2. Keyword Group 1: Connection Medium
        private val CONNECTION_KEYWORDS = listOf(
            "usb", "wireless", "無線", "无线", "wi-fi", "wifi", "adb"
        )

        // 3. Keyword Group 2: Debugging Actions
        private val DEBUG_KEYWORDS = listOf(
            "debugging", "debug", "偵錯", "侦错", "调试", "調試"
        )

        // 4. Keyword Group 3: RSA & Security Keys
        private val SECURITY_KEY_KEYWORDS = listOf(
            "rsa", "fingerprint", "金鑰", "金錀", "密钥", "金匙", "指紋", "指纹"
        )

        // 5. Safety Blacklist (Never touch dangerous system dialogs)
        private val BLACKLIST_KEYWORDS = listOf(
            "uninstall", "卸載", "卸载", "解除安裝", "刪除", "删除", "delete",
            "factory reset", "恢復出廠", "恢复出厂", "重設為原廠設定", "format", "格式化",
            "wipe data", "清除資料", "清除数据"
        )

        // Positive button text patterns
        private val ALLOW_BUTTON_TEXTS = listOf(
            "允許",
            "确定",
            "確定",
            "OK",
            "Ok",
            "Allow",
            "ALLOW",
            "同意",
            "開啟",
            "开启",
            "繼續",
            "继续",
            "下一步"
        )

        // Negative button text patterns to avoid clicking
        private val CANCEL_BUTTON_TEXTS = listOf(
            "取消",
            "Cancel",
            "CANCEL",
            "拒絕",
            "Deny",
            "DENY",
            "Dismiss",
            "否",
            "拒绝"
        )

        // "Always allow" checkbox text patterns
        private val ALWAYS_ALLOW_PATTERNS = listOf(
            "Always allow",
            "一律允許",
            "始終允許",
            "始终允许",
            "不再提示",
            "記住",
            "记住",
            "always allow"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, "AdbAccessibilityService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.i(TAG, "AdbAccessibilityService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isHandlingDialog) return

        val now = System.currentTimeMillis()
        if (now - lastInspectionTime < 350L) return
        lastInspectionTime = now

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                inspectAndHandleAdbDialog()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AdbAccessibilityService interrupted")
    }

    private fun inspectAndHandleAdbDialog() {
        if (isHandlingDialog) return
        val rootNode = rootInActiveWindow ?: return

        try {
            if (isAdbDialog(rootNode)) {
                val now = System.currentTimeMillis()
                if (now - lastHandledTime < debounceIntervalMs) {
                    return
                }

                isHandlingDialog = true

                val delayMs = 2000L // 固定等待 2 秒

                lastHandledTime = now + delayMs + debounceIntervalMs // 確保處理完畢後 5 秒內不重複觸發
                Log.i(TAG, "🔍 偵測到 ADB 授權視窗！等待 2 秒後點擊放行...")

                handler.postDelayed({
                    try {
                        val currentRoot = rootInActiveWindow ?: rootNode
                        // 1. Check "Always allow" checkbox
                        checkAlwaysAllowCheckbox(currentRoot)

                        // 2. Click "Allow" / "OK" button
                        val clicked = clickAllowButton(currentRoot)
                        if (clicked) {
                            Log.i(TAG, "🚀 已自動按下「允許/確定」按鈕，ADB 授權完成！")
                            showToast("🎉 AutoAccept ADB: 已成功自動授權 ADB 偵錯！")
                            isHandlingDialog = false
                        } else {
                            // Retry after 400ms if button is still loading or countdown (e.g., MIUI countdown)
                            handler.postDelayed({
                                try {
                                    rootInActiveWindow?.let { newRoot ->
                                        if (isAdbDialog(newRoot)) {
                                            if (clickAllowButton(newRoot)) {
                                                Log.i(TAG, "🚀 (重試) 已自動按下「允許/確定」按鈕！")
                                                showToast("🎉 AutoAccept ADB: 已成功自動授權 ADB 偵錯！")
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Retry error", e)
                                } finally {
                                    isHandlingDialog = false
                                }
                            }, 400L)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Delayed execution error", e)
                        isHandlingDialog = false
                    }
                }, delayMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ADB dialog", e)
            isHandlingDialog = false
        }
    }

    /**
     * Determine if current active window contains ADB authorization prompt
     * Strictly verifies USB / Wireless / RSA / Debugging keywords and rejects blacklisted dialogs.
     */
    private fun isAdbDialog(node: AccessibilityNodeInfo): Boolean {
        val allTexts = mutableListOf<String>()
        collectAllTexts(node, allTexts)

        val joinedText = allTexts.joinToString(" ")
        val lowerText = joinedText.lowercase()

        // 1. Safety Blacklist Check: If blacklisted keywords exist, strictly abort!
        if (BLACKLIST_KEYWORDS.any { lowerText.contains(it.lowercase()) }) {
            return false
        }

        // 2. Exact phrase match (e.g. "Allow USB debugging", "允許 USB 偵錯", "RSA 金鑰指紋", etc.)
        if (EXACT_ADB_PHRASES.any { lowerText.contains(it.lowercase()) }) {
            return true
        }

        // 3. Combined Rule A: (USB / Wireless / ADB) AND (Debugging / 偵錯 / 调试)
        val hasConnection = CONNECTION_KEYWORDS.any { lowerText.contains(it.lowercase()) }
        val hasDebug = DEBUG_KEYWORDS.any { lowerText.contains(it.lowercase()) }
        if (hasConnection && hasDebug) {
            return true
        }

        // 4. Combined Rule B: (RSA / Fingerprint / 金鑰 / 金錀 / 密钥) AND (Debugging / Computer / 電腦 / 计算机 / 授權)
        val hasSecurityKey = SECURITY_KEY_KEYWORDS.any { lowerText.contains(it.lowercase()) }
        val hasComputerOrDebug = hasDebug ||
                lowerText.contains("computer") ||
                lowerText.contains("電腦") ||
                lowerText.contains("计算机") ||
                lowerText.contains("授權") ||
                lowerText.contains("授权") ||
                lowerText.contains("指紋") ||
                lowerText.contains("指纹")

        if (hasSecurityKey && hasComputerOrDebug) {
            return true
        }

        return false
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?, result: MutableList<String>) {
        if (node == null) return

        node.text?.toString()?.let { if (it.isNotBlank()) result.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) result.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectAllTexts(child, result)
        }
    }

    /**
     * Find and check "Always allow from this computer" checkbox
     */
    private fun checkAlwaysAllowCheckbox(root: AccessibilityNodeInfo): Boolean {
        val checkboxes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByCondition(root, checkboxes) { node ->
            val className = node.className?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val isCheckboxType = className.contains("CheckBox", ignoreCase = true) ||
                    className.contains("CompoundButton", ignoreCase = true)
            val hasPattern = ALWAYS_ALLOW_PATTERNS.any { text.contains(it, ignoreCase = true) }

            isCheckboxType || hasPattern
        }

        var checkedAny = false
        for (cb in checkboxes) {
            if (!cb.isChecked) {
                // Click the checkbox itself or its clickable parent
                if (performClick(cb)) {
                    checkedAny = true
                }
            } else {
                checkedAny = true
            }
        }
        return checkedAny
    }

    /**
     * Find and click the Positive "Allow" / "OK" button
     */
    private fun clickAllowButton(root: AccessibilityNodeInfo): Boolean {
        val candidateButtons = mutableListOf<AccessibilityNodeInfo>()
        
        findNodesByCondition(root, candidateButtons) { node ->
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            val viewId = node.viewIdResourceName ?: ""

            val isCancel = CANCEL_BUTTON_TEXTS.any { text.equals(it, ignoreCase = true) }
            if (isCancel) {
                return@findNodesByCondition false
            }

            val isAllowText = ALLOW_BUTTON_TEXTS.any { text.equals(it, ignoreCase = true) || text.startsWith(it) }
            val isAllowId = viewId.endsWith(":id/button1") ||
                    viewId.endsWith(":id/ok") ||
                    viewId.endsWith(":id/accept")

            isAllowText || isAllowId
        }

        for (btn in candidateButtons) {
            if (btn.isEnabled && performClick(btn)) {
                return true
            }
        }

        return false
    }

    private fun findNodesByCondition(
        node: AccessibilityNodeInfo?,
        result: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ) {
        if (node == null) return

        if (predicate(node)) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            findNodesByCondition(child, result, predicate)
        }
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun showToast(message: String) {
        handler.post {
            try {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing toast", e)
            }
        }
    }
}
