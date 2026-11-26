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
    private var recovery: AdbPromptRecovery? = null
    private var lastHandledTime = 0L          // ADB 授權視窗
    private var lastDataHandledTime = 0L      // USB 資料存取視窗 (獨立計時，避免擋到緊接著出現的 ADB 視窗)
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
            "allow usb debugging?",
            "wireless debugging",
            "allow wireless debugging",
            "allow wireless debugging?",
            "rsa key fingerprint",
            "rsa key",
            "the computer's rsa key fingerprint is",
            "always allow from this computer",
            "adb debugging",
            "allow debugging",
            "an authorized computer is connecting",
            // Traditional Chinese (繁體中文)
            "usb 偵錯",
            "usb偵錯",
            "允許 usb 偵錯",
            "允許usb偵錯",
            "允許 usb 偵錯？",
            "允許usb偵錯？",
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
            "允许 usb 调试？",
            "允许usb调试？",
            "无线调试",
            "允许无线调试",
            "rsa 密钥",
            "rsa密钥",
            "密钥指纹",
            "始终允许使用这台计算机进行调试",
            "允许调试",
            // Korean
            "usb 디버깅",
            "usb 디버깅을 허용",
            "무선 디버깅",
            "rsa 키 지문",
            "이 컴퓨터에서 항상 허용"
        )

        // 1b. USB data-access prompts (e.g. Samsung "Allow access to phone data?")
        //     shown when the host opens an MTP/PTP session after plugging in USB.
        private val DATA_ACCESS_PHRASES = listOf(
            // English
            "allow access to phone data",
            "allow access to tablet data",
            "allow access to device data",
            "allow access to data",
            "will be able to access data on this",
            "access data on this phone",
            "access data on this device",
            "access data on this tablet",
            // Traditional Chinese
            "允許存取手機資料",
            "允許存取平板資料",
            "允許存取裝置資料",
            "允許存取資料",
            "存取這支手機上的資料",
            "存取此手機上的資料",
            "存取手機上的資料",
            "存取此裝置上的資料",
            "存取裝置上的資料",
            "允許存取手機數據",
            // Simplified Chinese
            "允许访问手机数据",
            "允许访问平板电脑数据",
            "允许访问设备数据",
            "允许访问数据",
            "访问此手机上的数据",
            "访问手机上的数据",
            "访问此设备上的数据",
            "访问设备上的数据",
            // Korean (e.g. Samsung "휴대전화 데이터에 접근 허용")
            "데이터에 접근 허용",
            "데이터에 대한 접근 허용",
            "데이터에 액세스",
            "데이터 액세스 허용",
            "데이터에 접근합니다"
        )

        // System packages that only ever show the USB data-access (MTP) prompt
        private val DATA_ACCESS_PACKAGES = listOf(
            "com.samsung.android.mtp"
        )

        // Combined rule for data-access prompts: (access) AND (data) AND (usb / connected device / computer)
        private val DATA_ACCESS_VERBS = listOf("access", "存取", "访问", "訪問", "접근", "액세스")
        private val DATA_WORDS = listOf("data", "資料", "数据", "數據", "데이터")
        private val DATA_CONTEXT_WORDS = listOf(
            "usb", "connected device", "computer",
            "連接的裝置", "已連接", "連線的裝置", "電腦",
            "连接的设备", "已连接", "电脑", "计算机",
            "연결된 디바이스", "연결된 기기", "컴퓨터"
        )

        // 2. Keyword Group 1: Connection Medium
        private val CONNECTION_KEYWORDS = listOf(
            "usb", "wireless", "無線", "无线", "wi-fi", "wifi", "adb"
        )

        // 3. Keyword Group 2: Debugging Actions
        private val DEBUG_KEYWORDS = listOf(
            "debugging", "debug", "偵錯", "侦错", "调试", "調試", "디버깅"
        )

        // 4. Keyword Group 3: RSA & Security Keys
        private val SECURITY_KEY_KEYWORDS = listOf(
            "rsa", "fingerprint", "key", "金鑰", "金錀", "密钥", "金匙", "指紋", "指纹", "키 지문", "지문"
        )

        // 5. Safety Blacklist (Never touch dangerous system dialogs)
        private val BLACKLIST_KEYWORDS = listOf(
            "uninstall", "卸載", "卸载", "解除安裝", "刪除", "删除", "delete",
            "factory reset", "恢復出廠", "恢复出厂", "重設為原廠設定", "format", "格式化",
            "wipe data", "清除資料", "清除数据",
            "삭제", "초기화", "포맷"
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
            "Accept",
            "ACCEPT",
            "Grant",
            "GRANT",
            "Authorize",
            "AUTHORIZE",
            "同意",
            "開啟",
            "开启",
            "繼續",
            "继续",
            "下一步",
            "허용",
            "확인"
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
            "DISMISS",
            "Reject",
            "REJECT",
            "否",
            "拒绝",
            "허용 안 함",
            "거부",
            "취소"
        )

        // "Always allow" checkbox text patterns
        private val ALWAYS_ALLOW_PATTERNS = listOf(
            "Always allow",
            "always allow",
            "Always allow from this computer",
            "Remember this computer",
            "Always permit",
            "Don't ask again",
            "Do not ask again",
            "一律允許",
            "始終允許",
            "始终允许",
            "不再提示",
            "記住",
            "记住",
            "항상 허용"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        isHandlingDialog = false
        Log.i(TAG, "AdbAccessibilityService connected")
        // Some ROMs (e.g. Samsung) rebind accessibility services when the USB state changes,
        // which can happen while a dialog is already on screen. No new event will arrive for it,
        // so proactively inspect the current window after (re)connecting.
        handler.postDelayed({ inspectAndHandleAdbDialog() }, 500L)
        recovery = AdbPromptRecovery(this).also { it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        recovery?.stop()
        recovery = null
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

    private enum class DialogType { ADB, DATA_ACCESS }

    private fun detectDialog(node: AccessibilityNodeInfo): DialogType? {
        val pkg = node.packageName?.toString() ?: ""
        if (DATA_ACCESS_PACKAGES.any { pkg == it }) return DialogType.DATA_ACCESS
        val lowerText = collectLowerText(node)
        // Safety Blacklist Check: If blacklisted keywords exist, strictly abort!
        if (BLACKLIST_KEYWORDS.any { lowerText.contains(it.lowercase()) }) return null
        if (isAdbText(lowerText)) return DialogType.ADB
        if (isDataAccessText(lowerText)) return DialogType.DATA_ACCESS
        return null
    }

    /**
     * Find a window that contains an ADB / data-access dialog. Checks the active window first,
     * then every other window (the dialog may not be the active one, e.g. behind the shade).
     */
    private fun findDialogRoot(): Pair<AccessibilityNodeInfo, DialogType>? {
        rootInActiveWindow?.let { root -> detectDialog(root)?.let { return root to it } }
        try {
            for (w in windows) {
                val root = w.root ?: continue
                // our own non-active windows are never a system dialog
                if (!w.isActive && root.packageName?.toString() == packageName) continue
                detectDialog(root)?.let { return root to it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning windows", e)
        }
        return null
    }

    private fun inspectAndHandleAdbDialog() {
        if (isHandlingDialog) return

        try {
            val (rootNode, type) = findDialogRoot() ?: return
            val now = System.currentTimeMillis()
            val lastTime = if (type == DialogType.ADB) lastHandledTime else lastDataHandledTime
            if (now - lastTime < debounceIntervalMs) {
                return
            }

            isHandlingDialog = true

            val delayMs = 2000L // 固定等待 2 秒
            // 確保處理完畢後 5 秒內不重複觸發 (各類型獨立計時)
            if (type == DialogType.ADB) {
                lastHandledTime = now + delayMs + debounceIntervalMs
            } else {
                lastDataHandledTime = now + delayMs + debounceIntervalMs
            }

            val label = if (type == DialogType.ADB) "ADB 授權視窗" else "USB 資料存取視窗"
            val toastRes = if (type == DialogType.ADB) R.string.toast_adb_authorized else R.string.toast_data_access_allowed
            Log.i(TAG, "🔍 偵測到 $label！等待 2 秒後點擊放行...")

            handler.postDelayed({
                try {
                    val currentRoot = findDialogRoot()?.takeIf { it.second == type }?.first ?: rootNode
                    // 1. Check "Always allow" checkbox (ADB dialog only)
                    if (type == DialogType.ADB) {
                        checkAlwaysAllowCheckbox(currentRoot)
                    }

                    // 2. Click "Allow" / "OK" button
                    val clicked = clickAllowButton(currentRoot)
                    if (clicked) {
                        Log.i(TAG, "🚀 Successfully clicked Allow/OK on $type dialog")
                        showToast(getString(toastRes))
                        isHandlingDialog = false
                    } else {
                        // Retry after 400ms if button is still loading or countdown (e.g., MIUI countdown)
                        handler.postDelayed({
                            try {
                                findDialogRoot()?.let { (newRoot, newType) ->
                                    if (newType == type) {
                                        if (clickAllowButton(newRoot)) {
                                            Log.i(TAG, "🚀 (Retry) Successfully clicked Allow/OK on $type dialog")
                                            showToast(getString(toastRes))
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
        } catch (e: Exception) {
            Log.e(TAG, "Error handling dialog", e)
            isHandlingDialog = false
        }
    }

    private fun collectLowerText(node: AccessibilityNodeInfo): String {
        val allTexts = mutableListOf<String>()
        collectAllTexts(node, allTexts)
        return allTexts.joinToString(" ").lowercase()
    }

    /**
     * Determine if the window text is a USB data-access (MTP/PTP) prompt,
     * e.g. Samsung "Allow access to phone data?" / 「允許存取手機資料嗎？」
     */
    private fun isDataAccessText(lowerText: String): Boolean {
        if (DATA_ACCESS_PHRASES.any { lowerText.contains(it.lowercase()) }) return true
        return DATA_ACCESS_VERBS.any { lowerText.contains(it) } &&
                DATA_WORDS.any { lowerText.contains(it) } &&
                DATA_CONTEXT_WORDS.any { lowerText.contains(it) }
    }

    /**
     * Determine if current active window contains ADB authorization prompt
     * Strictly verifies USB / Wireless / RSA / Debugging keywords and rejects blacklisted dialogs.
     */
    private fun isAdbText(lowerText: String): Boolean {
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
                lowerText.contains("컴퓨터") ||
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
        // Only real checkable widgets. A label row with the same text must NOT be clicked as well,
        // otherwise the checkbox gets toggled twice (on, then off again).
        val checkables = mutableListOf<AccessibilityNodeInfo>()
        findNodesByCondition(root, checkables) { node ->
            val className = node.className?.toString() ?: ""
            node.isCheckable || className.contains("CheckBox", ignoreCase = true) ||
                    className.contains("CompoundButton", ignoreCase = true) ||
                    className.contains("Switch", ignoreCase = true)
        }

        if (checkables.isNotEmpty()) {
            var ok = false
            for (cb in checkables) {
                cb.refresh()
                val before = cb.isChecked
                if (!before) performClick(cb)
                cb.refresh()
                Log.i(TAG, "Always-allow checkbox ${cb.viewIdResourceName ?: cb.className}: $before -> ${cb.isChecked}")
                ok = ok || cb.isChecked || !before
            }
            return ok
        }

        // Fallback: no checkable node exposed; click the first "always allow" label once
        val labels = mutableListOf<AccessibilityNodeInfo>()
        findNodesByCondition(root, labels) { node ->
            val text = node.text?.toString() ?: ""
            ALWAYS_ALLOW_PATTERNS.any { text.contains(it, ignoreCase = true) }
        }
        val label = labels.firstOrNull() ?: return false
        Log.i(TAG, "Always-allow: clicking label \"${label.text}\"")
        return performClick(label)
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
