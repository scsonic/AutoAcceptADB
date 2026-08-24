package com.autoaccept.adb

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autoaccept.adb.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            KeepAliveService.start(this)
        }
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        updateStatus()

        // Start KeepAlive service if notification permission is ready
        if (hasNotificationPermission()) {
            KeepAliveService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupListeners() {
        binding.btnRefreshStatus.setOnClickListener {
            updateStatus()
            Toast.makeText(this, getString(R.string.toast_status_refreshed), Toast.LENGTH_SHORT).show()
        }

        // Wizard button: Guide through missing permissions
        binding.btnGrantWizard.setOnClickListener {
            handleWizardClick()
        }

        // Step 1: Accessibility
        binding.btnStepAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // Step 2: Battery Optimization
        binding.btnStepBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        // Step 3: Notification
        binding.btnStepNotification.setOnClickListener {
            requestNotification()
        }

        // Step 4: OEM Auto-start / App Info
        binding.btnStepOEM.setOnClickListener {
            openAppDetailsSettings()
        }

        // Test Dialog Button
        binding.btnTriggerTestDialog.setOnClickListener {
            showMockAdbDialog()
        }
    }

    private fun updateStatus() {
        val isAccessibilityOk = isAccessibilityServiceEnabled()
        val isBatteryOk = isIgnoringBatteryOptimization()
        val isNotificationOk = hasNotificationPermission()

        // 1. Accessibility Status
        if (isAccessibilityOk) {
            binding.tvStatusAccessibility.text = getString(R.string.status_service_running)
            binding.tvStatusAccessibility.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.btnStepAccessibility.text = getString(R.string.step1_accessibility_btn_enabled)
            binding.btnStepAccessibility.isEnabled = true
        } else {
            binding.tvStatusAccessibility.text = getString(R.string.status_service_stopped)
            binding.tvStatusAccessibility.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.btnStepAccessibility.text = getString(R.string.step1_accessibility_btn)
            binding.btnStepAccessibility.isEnabled = true
        }

        // 2. Battery Status
        if (isBatteryOk) {
            binding.tvStatusBattery.text = getString(R.string.status_battery_ignored)
            binding.tvStatusBattery.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.btnStepBattery.text = getString(R.string.step2_battery_btn_enabled)
        } else {
            binding.tvStatusBattery.text = getString(R.string.status_battery_optimizing)
            binding.tvStatusBattery.setTextColor(ContextCompat.getColor(this, R.color.warning))
            binding.btnStepBattery.text = getString(R.string.step2_battery_btn)
        }

        // 3. Notification Status
        if (isNotificationOk) {
            binding.tvStatusNotification.text = getString(R.string.status_notification_granted)
            binding.tvStatusNotification.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.btnStepNotification.text = getString(R.string.step3_notification_btn_enabled)
        } else {
            binding.tvStatusNotification.text = getString(R.string.status_notification_denied)
            binding.tvStatusNotification.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnStepNotification.text = getString(R.string.step3_notification_btn)
        }

        // Update Wizard Button text if all core permissions are ready
        if (isAccessibilityOk && isBatteryOk && isNotificationOk) {
            binding.btnGrantWizard.text = getString(R.string.btn_grant_all_completed)
            binding.btnGrantWizard.setBackgroundColor(ContextCompat.getColor(this, R.color.success))
        } else {
            binding.btnGrantWizard.text = getString(R.string.btn_grant_all)
            binding.btnGrantWizard.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    private fun handleWizardClick() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, getString(R.string.toast_wizard_accessibility), Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        if (!isIgnoringBatteryOptimization()) {
            requestIgnoreBatteryOptimization()
            return
        }

        if (!hasNotificationPermission()) {
            requestNotification()
            return
        }

        Toast.makeText(this, getString(R.string.toast_wizard_all_done), Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, AdbAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true) ||
                componentName.contains(packageName)
            ) {
                return true
            }
        }
        return AdbAccessibilityService.isServiceRunning
    }

    private fun isIgnoringBatteryOptimization(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_open_accessibility_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e2: Exception) {
                    Toast.makeText(this, getString(R.string.toast_open_battery_error), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, getString(R.string.toast_battery_not_needed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            KeepAliveService.start(this)
            updateStatus()
            Toast.makeText(this, getString(R.string.toast_notification_started), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_open_app_details_error), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Display a mock ADB authorization dialog to test accessibility service
     */
    private fun showMockAdbDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 20, 60, 10)
        }

        val msgView = TextView(this).apply {
            text = getString(R.string.test_dialog_message)
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            textSize = 14f
        }

        val checkBox = CheckBox(this).apply {
            id = android.R.id.checkbox
            text = getString(R.string.test_dialog_checkbox)
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            isChecked = false
        }

        container.addView(msgView)
        container.addView(checkBox)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.test_dialog_title))
            .setView(container)
            .setPositiveButton(getString(R.string.test_dialog_allow)) { _, _ ->
                Toast.makeText(this, getString(R.string.toast_sim_dialog_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.test_dialog_cancel)) { d, _ ->
                d.dismiss()
            }
            .create()

        dialog.show()
    }
}
