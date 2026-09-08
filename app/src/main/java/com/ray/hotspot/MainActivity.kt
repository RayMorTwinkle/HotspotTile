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

    companion object { private const val TAG = "HotspotEngine" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HotspotEngine.init(applicationContext)
        requestAddTileOnce()

        val mode = intent?.getStringExtra("mode") ?: Prefs.launcherClick
        when (mode) {
            "settings" -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
            }
            "toggle" -> {
                HotspotEngine.toggle { ok, msg ->
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                    if (!ok) openSystemPage()
                    finish()
                }
            }
            else -> { // "page"
                openSystemPage()
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
        Prefs.tilePrompted = true
        try {
            val smb = getSystemService(StatusBarManager::class.java)
            smb?.requestAddTileService(
                ComponentName(this, HotspotTileService::class.java),
                getString(R.string.tile_name),
                Icon.createWithResource(this, R.drawable.ic_hotspot),
                mainExecutor
            ) { }
        } catch (t: Throwable) {
            Log.w(TAG, "requestAddTileService failed: $t")
        }
    }
}
