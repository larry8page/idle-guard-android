package com.larry8page.idleguard

import android.app.admin.DeviceAdminReceiver

/**
 * Minimal DeviceAdminReceiver — only used so the system can bind our
 * admin component and let us call DevicePolicyManager.lockNow().
 */
class IdleGuardDeviceAdminReceiver : DeviceAdminReceiver()
