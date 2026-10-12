package com.larry8page.idleguard

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Per-OEM autostart whitelist guide. Detects the device brand, shows step-by-step
 * instructions, and tries to jump straight into the vendor's own
 * "startup manager" / "background management" screen when known.
 */
class AutoStartGuideActivity : AppCompatActivity() {

    data class Brand(
        val displayName: String,
        val guideText: String,
        val candidates: List<Pair<String, String>>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_start)
        title = getString(R.string.title_auto_start)

        val brand = detectBrand()
        findViewById<TextView>(R.id.oemName).text = "检测到的机型品牌：${brand.displayName}"
        findViewById<TextView>(R.id.guideText).text = brand.guideText
        findViewById<Button>(R.id.btnOpenAutostart).setOnClickListener { openAutostart(brand) }
        findViewById<Button>(R.id.btnAppDetails).setOnClickListener { openAppDetails() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun detectBrand(): Brand {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
                b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> Brand(
                "小米 / Redmi / POCO (MIUI / HyperOS)",
                "步骤：设置 → 应用设置 → 授权管理 → 自启动管理 → 找到闲置守护 → 打开。同时到应用信息 → 省电策略，选择“无限制”。",
                listOf(
                    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
            m.contains("huawei") || b.contains("huawei") || b.contains("honor") -> Brand(
                "华为 / 荣耀 (EMUI / MagicOS)",
                "步骤：手机管家 → 应用启动管理 → 找到闲置守护 → 关闭“自动管理”，在弹出菜单里把三项都设为“允许”。",
                listOf(
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            )
            m.contains("oppo") || b.contains("oppo") ||
                m.contains("realme") || b.contains("realme") ||
                m.contains("oneplus") || b.contains("oneplus") -> Brand(
                "OPPO / realme / 一加 (ColorOS)",
                "步骤：设置 → 应用 → 自启动管理（部分版本：安全与隐私 → 权限与隐私 → 自启动）→ 允许闲置守护。",
                listOf(
                    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                    "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
                    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            )
            m.contains("vivo") || b.contains("vivo") || b.contains("iqoo") -> Brand(
                "vivo / iQOO (OriginOS / Funtouch OS)",
                "步骤：i管家 → 应用管理 → 权限管理 → 自启动 → 允许闲置守护；同时在“电池”里把闲置守护设为“允许后台高耗电”。",
                listOf(
                    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
            )
            m.contains("samsung") || b.contains("samsung") -> Brand(
                "三星 (One UI)",
                "步骤：设置 → 常规管理 → 电池 → 后台使用限制 → 确保闲置守护不在“睡眠应用 / 深度睡眠应用”列表；也可加入“永不睡眠”。",
                listOf(
                    "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            )
            m.contains("meizu") || b.contains("meizu") -> Brand(
                "魅族 (Flyme)",
                "步骤：安全中心 → 权限管理 → 后台管理 → 允许闲置守护。",
                listOf(
                    "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC"
                )
            )
            else -> Brand(
                "${Build.MANUFACTURER} / ${Build.BRAND}",
                "未识别到常见国产 ROM。请到“应用信息 → 权限/自启动/电池”里，允许闲置守护自启动，并关闭电池优化。",
                emptyList()
            )
        }
    }

    private fun openAutostart(brand: Brand) {
        for ((pkg, cls) in brand.candidates) {
            val intent = Intent().setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        }
        Toast.makeText(this, "未能直达，改用应用信息页手动进入。", Toast.LENGTH_SHORT).show()
        openAppDetails()
    }

    private fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "无法跳转到系统设置", Toast.LENGTH_LONG).show()
        }
    }
}
