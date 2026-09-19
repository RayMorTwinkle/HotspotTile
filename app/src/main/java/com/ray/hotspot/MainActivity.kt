package com.ray.hotspot

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * 启动器入口：打开后按配置行为执行（默认直接跳系统热点设置页），然后退出。
 * 长按图标菜单里的「打开热点页」快捷方式也会带 mode=page 走到这里。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val ACTION_OPEN_PAGE = "com.ray.hotspot.action.OPEN_PAGE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HotspotEngine.init(applicationContext)
        requestAddTileOnce()

        // mode extra 仅对内部 shortcut action 生效，防止任意 App 借 exported
        // Activity 驱动反射/Root 开关路径
        val mode = (
            if (intent?.action == ACTION_OPEN_PAGE) intent?.getStringExtra("mode") else null
            ) ?: Prefs.launcherClick
        when (mode) {
            "settings" -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
            }
            "toggle" -> {
                // 切换要秒级到分钟级，先给即时反馈防止用户以为没点上而重复触发
                Toast.makeText(applicationContext, "正在切换热点…", Toast.LENGTH_SHORT).show()
                HotspotEngine.toggle { r ->
                    Toast.makeText(applicationContext, r.msg, Toast.LENGTH_SHORT).show()
                    if (!r.ok) when (r.fallback) {
                        HotspotEngine.ToggleResult.FALLBACK_APP_SETTINGS ->
                            startActivity(Intent(this, SettingsActivity::class.java))
                        HotspotEngine.ToggleResult.FALLBACK_SYSTEM_PAGE ->
                            if (!openSystemPage()) {
                                Toast.makeText(
                                    applicationContext, "无法打开系统热点设置页",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                    finish()
                }
            }
            else -> { // "page"
                if (!openSystemPage()) {
                    Toast.makeText(
                        applicationContext, "无法打开系统热点设置页",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                finish()
            }
        }
    }

    private fun openSystemPage(): Boolean =
        HotspotEngine.openHotspotSettings { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        }

    /** Android 13+ 弹一次「添加到控制中心」系统对话框。 */
    private fun requestAddTileOnce() {
        if (Build.VERSION.SDK_INT < 33 || Prefs.tilePrompted) return
        try {
            val smb = getSystemService(StatusBarManager::class.java) ?: return
            smb.requestAddTileService(
                ComponentName(this, HotspotTileService::class.java),
                getString(R.string.tile_name),
                Icon.createWithResource(this, R.drawable.ic_hotspot),
                mainExecutor
            ) { result ->
                // 用户在系统对话框拒绝或添加失败 → 复位，下次启动再引导一次
                if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED &&
                    result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                ) {
                    Prefs.tilePrompted = false
                }
            }
            // 调用成功发起才置位；用户在对话框拒绝由上面回调复位
            Prefs.tilePrompted = true
        } catch (t: Throwable) {
            Prefs.tilePrompted = false
            Log.w(TAG, "requestAddTileService failed: $t")
        }
    }
}
