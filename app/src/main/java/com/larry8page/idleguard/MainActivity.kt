package com.larry8page.idleguard

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.method.DigitsKeyListener
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.larry8page.idleguard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val batteryRequester = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result ignored; we re-check on resume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.editMinutes.keyListener = DigitsKeyListener.getInstance("0123456789")
        b.editMinutes.setText(SettingsStore.idleMinutes(this).toString())
        b.switchEnabled.isChecked = SettingsStore.isEnabled(this)

        b.switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val missing = missingPermissions()
                if (missing.isNotEmpty()) {
                    b.switchEnabled.isChecked = false
                    Toast.makeText(
                        this,
                        getString(R.string.toast_missing_perms, missing.joinToString("、")),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnCheckedChangeListener
                }
                SettingsStore.setEnabled(this, true)
                requestNotifPermissionIfNeeded()
                IdleGuardService.start(this)
            } else {
                SettingsStore.setEnabled(this, false)
                IdleGuardService.stop(this)
                TimerScheduler.cancel(this)
            }
        }

        b.editMinutes.doAfterTextChanged { et ->
            val v = et?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            val clamped = v.coerceIn(1, SettingsStore.MAX_IDLE_MIN)
            if (clamped != v) b.editMinutes.setText(clamped.toString())
            SettingsStore.setIdleMinutes(this, clamped)
            IdleGuardService.instance?.onUserActivity()
        }

        b.btnGrantAccessibility.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { toastCantOpen() }
        }
        b.btnGrantDeviceAdmin.setOnClickListener {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(this, IdleGuardDeviceAdminReceiver::class.java)
            )
            i.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_rationale)
            )
            runCatching { startActivity(i) }.onFailure { toastCantOpen() }
        }
        b.btnGrantDnd.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                .onFailure { toastCantOpen() }
        }
        b.btnIgnoreBattery.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            runCatching { batteryRequester.launch(intent) }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { toastCantOpen() }
            }
        }
        b.btnGrantExactAlarm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                intent.data = Uri.parse("package:$packageName")
                runCatching { startActivity(intent) }.onFailure { openAppDetailsSettings() }
            } else {
                openAppDetailsSettings()
            }
        }
        b.btnTestTrigger.setOnClickListener {
            Toast.makeText(this, R.string.toast_test_triggered, Toast.LENGTH_SHORT).show()
            ActionRunner.trigger(this)
        }
        b.btnOpenAutoStart.setOnClickListener {
            startActivity(Intent(this, AutoStartGuideActivity::class.java))
        }
        b.btnOpenLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
    }

    // ------- Permission checks -------

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(packageName, IdleAccessibilityService::class.java.name).flattenToString()
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName && it.resolveInfo.serviceInfo.name == IdleAccessibilityService::class.java.name } ||
            (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true)
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, IdleGuardDeviceAdminReceiver::class.java))
    }

    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
    }

    private fun isExactAlarmGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true // exact alarms always permitted pre-S
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    private fun missingPermissions(): List<String> {
        val out = mutableListOf<String>()
        if (!isAccessibilityEnabled()) out += getString(R.string.perm_accessibility)
        if (!isDeviceAdminActive()) out += getString(R.string.perm_device_admin)
        if (!isDndGranted()) out += getString(R.string.perm_dnd)
        return out
    }

    private fun refreshPermissionUi() {
        b.statusAccessibility.text = statusLine(getString(R.string.perm_accessibility), isAccessibilityEnabled())
        b.statusDeviceAdmin.text = statusLine(getString(R.string.perm_device_admin), isDeviceAdminActive())
        b.statusDnd.text = statusLine(getString(R.string.perm_dnd), isDndGranted())
        b.statusBattery.text = statusLine(getString(R.string.perm_battery), isIgnoringBattery())
        val exactOk = isExactAlarmGranted()
        b.statusExactAlarm.text = statusLine(getString(R.string.perm_exact_alarm), exactOk)
        b.btnGrantExactAlarm.visibility = if (exactOk) android.view.View.GONE else android.view.View.VISIBLE
        b.rowAutoStartHint.visibility = if (SettingsStore.isEnabled(this)) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun statusLine(name: String, ok: Boolean) =
        getString(R.string.status_line, name, if (ok) "✅" else "❌")

    private fun toastCantOpen() =
        Toast.makeText(this, R.string.toast_cant_open_settings, Toast.LENGTH_LONG).show()

    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:$packageName")
        runCatching { startActivity(intent) }.onFailure { toastCantOpen() }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }
}
