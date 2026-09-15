package com.ray.hotspot

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * 热点开关引擎。策略（从上到下，谁验证成功用谁）：
 *  1. 反射隐藏 API ConnectivityManager.startTethering / stopTethering —— 真·网络共享，
 *     使用系统已配置的热点名称/密码。Android ≤15 对普通应用放行（spoton 应用长期验证），
 *     Android 16 起需要 TETHER_PRIVILEGED，会失败。
 *  2. Root 自定义命令（设置页可配，例如 service call tethering ...）。
 *  3. Root cmd wifi start/stop-softap —— T508N 面板实测语法；注意 AOSP 文档注明该命令
 *     不激活 internet tethering，能否上网取决于机型 ROM。
 *  4. 全部失败 → 由调用方打开系统热点设置页兜底。
 */
object HotspotEngine {
    private const val TAG = "HotspotEngine"
    private const val TETHERING_WIFI = 0
    private const val WIFI_AP_STATE_ENABLED = 13

    @Volatile private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (appCtx == null) {
            val app = context.applicationContext
            appCtx = app
            Prefs.init(app)
            RootShell.init(app.cacheDir)
        }
    }

    private fun ctx(): Context = appCtx ?: error("HotspotEngine.init() 未调用")

    // ---------------- 状态查询 ----------------

    /** true=开 false=关 null=无法得知（全部手段失败） */
    fun state(): Boolean? {
        reflectWifiApEnabled()?.let { return it }
        reflectWifiApState()?.let { return it }
        return rootApState()
    }

    private fun reflectWifiApEnabled(): Boolean? = try {
        val wm = ctx().getSystemService(Context.WIFI_SERVICE) as WifiManager
        val m = WifiManager::class.java.methods
            .firstOrNull { it.name == "isWifiApEnabled" && it.parameterCount == 0 }
        m?.isAccessible = true
        m?.invoke(wm) as? Boolean
    } catch (t: Throwable) {
        null
    }

    private fun reflectWifiApState(): Boolean? = try {
        val wm = ctx().getSystemService(Context.WIFI_SERVICE) as WifiManager
        val m = WifiManager::class.java.methods
            .firstOrNull { it.name == "getWifiApState" && it.parameterCount == 0 }
        m?.isAccessible = true
        val st = m?.invoke(wm) as? Int ?: return null
        st == WIFI_AP_STATE_ENABLED
    } catch (t: Throwable) {
        null
    }

    private fun rootApState(): Boolean? {
        if (!RootShell.available()) return null
        val r = RootShell.exec("dumpsys wifi | grep -c ROLE_SOFTAP_TETHERED", timeoutMs = 12000)
        val n = r.out.trim().toIntOrNull() ?: return null
        return n > 0
    }

    // ---------------- 切换 ----------------

    /** 读当前状态并取反；onDone 在主线程回调。 */
    fun toggle(onDone: (ok: Boolean, msg: String) -> Unit) {
        Thread {
            if (state() == true) turnOff(onDone) else turnOn(onDone)
        }.start()
    }

    fun turnOn(onDone: (ok: Boolean, msg: String) -> Unit) {
        Thread {
            if (reflectToggle(on = true) && awaitState(true)) {
                return@Thread done(onDone, true, "热点已开启（系统共享）")
            }
            if (RootShell.available()) {
                val custom = Prefs.customOn.trim()
                if (custom.isNotEmpty()) {
                    RootShell.exec(custom, timeoutMs = 15000)
                    if (awaitState(true)) return@Thread done(onDone, true, "热点已开启（自定义命令）")
                }
                for (v in orderedVariants(Prefs.startVariant, 0..4)) {
                    RootShell.exec(startCmd(v), timeoutMs = 12000)
                    if (awaitState(true)) {
                        Prefs.startVariant = v
                        return@Thread done(onDone, true, "热点已开启（cmd wifi）")
                    }
                }
                done(onDone, false, "Root 命令未能开启热点")
            } else {
                done(onDone, false, "无法直接开启热点")
            }
        }.start()
    }

    fun turnOff(onDone: (ok: Boolean, msg: String) -> Unit) {
        Thread {
            if (reflectToggle(on = false) && awaitState(false)) {
                return@Thread done(onDone, true, "热点已关闭（系统共享）")
            }
            if (RootShell.available()) {
                val custom = Prefs.customOff.trim()
                if (custom.isNotEmpty()) {
                    RootShell.exec(custom, timeoutMs = 15000)
                    if (awaitState(false)) return@Thread done(onDone, true, "热点已关闭（自定义命令）")
                }
                for (v in orderedVariants(Prefs.stopVariant, 0..2)) {
                    RootShell.exec(stopCmd(v), timeoutMs = 12000)
                    if (awaitState(false)) {
                        Prefs.stopVariant = v
                        return@Thread done(onDone, true, "热点已关闭（cmd wifi）")
                    }
                }
                done(onDone, false, "Root 命令未能关闭热点")
            } else {
                done(onDone, false, "无法直接关闭热点")
            }
        }.start()
    }

    private fun done(cb: (Boolean, String) -> Unit, ok: Boolean, msg: String) {
        main.post { cb(ok, msg) }
    }

    /** 轮询验证状态是否翻转；需连续两次读到目标状态（间隔 600ms，约 1.2 秒）才算稳定，
     *  避免系统短暂上报目标状态后又回滚被误判为成功；状态完全读不到时按失败处理（宁可信其无）。 */
    private fun awaitState(target: Boolean, timeoutMs: Long = 6000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var hits = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(600)
            hits = if (state() == target) hits + 1 else 0
            if (hits >= 2) return true
        }
        return false
    }

    /**
     * 反射调用隐藏的 startTethering / stopTethering。
     * callback 传 null：其 NPE 只会发生在我们自己的进程线程里，用兜底
     * UncaughtExceptionHandler 吞掉即可；system_server 不受影响，
     * 真正的 tethering 指令在 NPE 之前就已经通过 binder 发出。
     */
    private fun reflectToggle(on: Boolean): Boolean {
        var thread: HandlerThread? = null
        return try {
            val cm = ctx().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ht = HandlerThread("hsx-toggle").apply {
                start()
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                    Log.w(TAG, "hidden callback NPE swallowed: ${e.message}")
                }
            }
            thread = ht
            val handler = Handler(ht.looper)
            var invoked = false

            if (on) {
                outer@ for (m in ConnectivityManager::class.java.methods) {
                    if (m.name != "startTethering") continue
                    val pt = m.parameterTypes
                    if (pt.size < 3 || pt.size > 4) continue
                    if (pt[0] != Int::class.javaPrimitiveType || pt[1] != java.lang.Boolean.TYPE) continue
                    if (!pt[2].name.endsWith("OnStartTetheringCallback")) continue
                    if (pt.size == 4 && pt[3] != Handler::class.java) continue
                    m.isAccessible = true
                    val args = mutableListOf<Any?>(TETHERING_WIFI, false, null)
                    if (pt.size == 4) args.add(handler)
                    handler.post { runCatching { m.invoke(cm, *args.toTypedArray()) } }
                    invoked = true
                    break@outer
                }
            } else {
                for (m in ConnectivityManager::class.java.methods) {
                    if (m.name != "stopTethering") continue
                    val pt = m.parameterTypes
                    if (pt.size == 1 && pt[0] == Int::class.javaPrimitiveType) {
                        m.isAccessible = true
                        handler.post { runCatching { m.invoke(cm, TETHERING_WIFI) } }
                        invoked = true
                        break
                    }
                }
            }

            Thread.sleep(400) // 给 runOnLooper 的 binder 调用一点时间
            invoked
        } catch (t: Throwable) {
            Log.w(TAG, "reflectToggle($on) failed: $t")
            false
        } finally {
            // quitSafely 会先跑完已投递的消息再退出
            thread?.quitSafely()
        }
    }

    // ---------------- Root cmd wifi 命令变体 ----------------

    private fun orderedVariants(cached: Int, range: IntRange): List<Int> =
        listOfNotNull(cached.takeIf { it in range }) + range.filter { it != cached }

    private fun ssidOrDefault(): String =
        Prefs.apSsid.trim().ifEmpty {
            Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "AndroidHotspot"
        }

    private fun startCmd(v: Int): String {
        val ssid = ssidOrDefault()
        val pass = Prefs.apPass.trim().ifEmpty { "12345678" }
        return when (v) {
            0 -> "cmd wifi start-softap \"$ssid\" wpa2 \"$pass\""      // T508N 实测语法
            1 -> "cmd wifi start-softap \"$ssid\" wpa3 \"$pass\""
            2 -> "cmd wifi start-softap ap0 wpa2 \"$ssid\" \"$pass\""  // AOSP ifname 风格
            3 -> "cmd wifi start-softap ap0 wpa2-psk \"$ssid\" \"$pass\""
            else -> "cmd wifi start-softap \"$ssid\" open"
        }
    }

    private fun stopCmd(v: Int): String = when (v) {
        0 -> "cmd wifi stop-softap"
        1 -> "cmd wifi stop-softap ap0"
        else -> "cmd wifi stop-softap \"${ssidOrDefault()}\""
    }

    // ---------------- 打开系统热点设置页 ----------------

    private fun settingsCandidates(): List<Intent> = listOf(
        Intent("android.settings.TETHER_SETTINGS"),
        Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    /** 逐个候选尝试启动；start 返回 true 表示已发起。 */
    fun openHotspotSettings(start: (Intent) -> Boolean): Boolean {
        for (i in settingsCandidates()) {
            try {
                if (start(i)) return true
            } catch (t: Throwable) {
                Log.w(TAG, "start failed: ${i.component ?: i.action} -> $t")
            }
        }
        return false
    }

    /** 设置页诊断信息（在后台线程调用）。 */
    fun diag(): String {
        val root = RootShell.available()
        val st = when (val s = state()) {
            true -> "开"
            false -> "关"
            null -> "未知"
        }
        val reflect = if (Build.VERSION.SDK_INT <= 35) "大概率支持（≤ Android 15）" else "大概率被系统拦截（Android 16+）"
        val sb = StringBuilder()
        sb.appendLine("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        sb.appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Root：${if (root) "有（su 可用）" else "无"}")
        sb.appendLine("热点状态：$st")
        sb.appendLine("反射直控（真共享）：$reflect")
        if (root) {
            sb.appendLine("当前生效命令：${startCmd(Prefs.startVariant.takeIf { it >= 0 } ?: 0)}")
        }
        return sb.toString()
    }
}
