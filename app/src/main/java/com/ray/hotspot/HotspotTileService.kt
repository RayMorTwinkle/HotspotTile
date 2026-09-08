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
        if (isLocked) unlockAndRun { act() } else act()
    }

    private fun act() {
        when (Prefs.tileClick) {
            "page" -> {
                openSystemPage()
                refresh()
            }
            else -> { // "toggle"
                setBusy()
                HotspotEngine.toggle { ok, msg ->
                    toast(msg)
                    if (!ok) openSystemPage()
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

    private fun openSystemPage() {
        HotspotEngine.openHotspotSettings { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(intent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                )
            }
            true
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
