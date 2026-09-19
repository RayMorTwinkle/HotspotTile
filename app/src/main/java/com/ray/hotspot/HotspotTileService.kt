package com.ray.hotspot

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** 控制中心「WiFi热点」磁贴。 */
class HotspotTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        HotspotEngine.init(applicationContext)
    }

    override fun onStartListening() = refresh()
    override fun onTileAdded() = refresh()

    override fun onClick() {
        // isLocked/unlockAndRun 是 API 27 方法，API 26 直接走 act()
        if (Build.VERSION.SDK_INT >= 27 && isLocked) unlockAndRun { act() } else act()
    }

    private fun act() {
        when (Prefs.tileClick) {
            "page" -> {
                openSystemPage()
                refresh()
            }
            else -> { // "toggle"
                setBusy()
                HotspotEngine.toggle { r ->
                    toast(r.msg)
                    when (r.fallback.takeIf { !r.ok }) {
                        HotspotEngine.ToggleResult.FALLBACK_APP_SETTINGS -> openAppSettings()
                        HotspotEngine.ToggleResult.FALLBACK_SYSTEM_PAGE -> openSystemPage()
                    }
                    refresh()
                }
            }
        }
    }

    private fun setBusy() {
        qsTile?.let {
            it.state = Tile.STATE_UNAVAILABLE
            it.updateTile()
        }
    }

    private fun refresh() {
        Thread {
            val s = HotspotEngine.state()
            val rooted = RootShell.available()
            main.post {
                val t = qsTile ?: return@post
                // API 26-28 无 subtitle 可显示，未知状态只能呈现 INACTIVE（取舍已记录）
                t.state = if (s == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) {
                    t.subtitle = when {
                        s == null && !rooted -> "点击打开热点页"
                        s == null -> "状态未知 · 点击切换"
                        rooted -> "Root/直控 · 长按设置"
                        else -> "直控 · 长按设置"
                    }
                }
                t.updateTile()
            }
        }.start()
    }

    private fun collapseAndStart(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(intent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        }
    }

    private fun openSystemPage() {
        HotspotEngine.openHotspotSettings { intent ->
            collapseAndStart(intent)
            true
        }
    }

    /** 缺凭据等需要用户到本 App 设置页补信息时的兜底。 */
    private fun openAppSettings() =
        collapseAndStart(Intent(this, SettingsActivity::class.java))

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
