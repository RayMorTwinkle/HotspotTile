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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 热点开关引擎。策略（从上到下，谁验证成功用谁）：
 *  1. 反射隐藏 API ConnectivityManager.startTethering / stopTethering —— 真·网络共享，
 *     使用系统已配置的热点名称/密码。Android ≤15 对普通应用放行（spoton 应用长期验证），
 *     Android 16 起需要 TETHER_PRIVILEGED，会失败。
 *  2. Root 自定义命令（设置页可配，例如 service call tethering ...）。
 *  3. Root cmd wifi start/stop-softap 多变体（T508N 实测语法为变体 0）；凭据按
 *     「进阶自定义 → 系统 WifiConfigStoreSoftAp.xml」解析，都拿不到则报错并请用户
 *     去设置页填写——不伪造默认 SSID/密码。注意 AOSP 文档注明该命令不激活
 *     internet tethering，能否上网取决于机型 ROM。
 *  4. 全部失败 → 由调用方打开系统热点设置页兜底。
 */
object HotspotEngine {
    private const val TAG = "HotspotEngine"
    private const val TETHERING_WIFI = 0
    private const val WIFI_AP_STATE_ENABLED = 13
    private const val VARIANT_FAIL_RESET = 3

    /** 一次开关的结果。fallback：失败时调用方应打开的兜底页面（见 FALLBACK_*）。 */
    data class ToggleResult(
        val ok: Boolean,
        val msg: String,
        val fallback: Int = FALLBACK_SYSTEM_PAGE
    ) {
        companion object {
            const val FALLBACK_NONE = 0
            const val FALLBACK_SYSTEM_PAGE = 1
            const val FALLBACK_APP_SETTINGS = 2
        }
    }

    @Volatile private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

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
    fun state(): Boolean? = probeState(12000)

    private fun probeState(rootTimeoutMs: Long): Boolean? {
        reflectWifiApEnabled()?.let { return it }
        reflectWifiApState()?.let { return it }
        return rootApState(rootTimeoutMs)
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

    private fun rootApState(timeoutMs: Long): Boolean? {
        if (!RootShell.available()) return null
        val r = RootShell.exec("dumpsys wifi", timeoutMs = timeoutMs)
        val out = r.out
        if (out.isBlank()) return null
        if (out.contains("ROLE_SOFTAP_TETHERED") || out.contains("ROLE_SOFTAP_LOCAL_ONLY")) return true
        // 有 SoftAp 痕迹但没有 active role → 确定是关；
        // 完全认不出格式 → null（"读不到"诚实兜底，胜过自信地误报关）
        return if (out.contains("softap", ignoreCase = true)) false else null
    }

    // ---------------- 切换 ----------------

    private fun rejectBusy(onDone: (ToggleResult) -> Unit) {
        main.post {
            onDone(ToggleResult(false, "操作进行中，请稍候", ToggleResult.FALLBACK_NONE))
        }
    }

    /** 读当前状态并取反；onDone 在主线程回调。state()==null 按"关"处理是刻意取舍（宁可信其无）。 */
    fun toggle(onDone: (ToggleResult) -> Unit) {
        if (!busy.compareAndSet(false, true)) { rejectBusy(onDone); return }
        Thread {
            try {
                if (probeState(12000) == true) turnOffWork(onDone) else turnOnWork(onDone)
            } catch (t: Throwable) {
                done(onDone, false, "切换异常：${t.message ?: t}")
            }
        }.start()
    }

    fun turnOn(onDone: (ToggleResult) -> Unit) {
        if (!busy.compareAndSet(false, true)) { rejectBusy(onDone); return }
        Thread {
            try { turnOnWork(onDone) }
            catch (t: Throwable) { done(onDone, false, "开启异常：${t.message ?: t}") }
        }.start()
    }

    fun turnOff(onDone: (ToggleResult) -> Unit) {
        if (!busy.compareAndSet(false, true)) { rejectBusy(onDone); return }
        Thread {
            try { turnOffWork(onDone) }
            catch (t: Throwable) { done(onDone, false, "关闭异常：${t.message ?: t}") }
        }.start()
    }

    private fun turnOnWork(onDone: (ToggleResult) -> Unit) {
        if (reflectToggle(on = true) && awaitState(true)) {
            return done(onDone, true, "热点已开启（系统共享）")
        }
        if (!RootShell.available()) {
            return done(onDone, false, "无法直接开启热点")
        }
        val custom = Prefs.customOn.trim()
        if (custom.isNotEmpty()) {
            RootShell.exec(custom, timeoutMs = 15000)
            if (awaitState(true)) return done(onDone, true, "热点已开启（自定义命令）")
        }
        val creds = apCreds()
        if (creds == null) {
            // 拿不到凭据宁可报错，不伪造默认名称/密码
            return done(onDone, false,
                "读不到热点名称/密码，请到设置页开启进阶并填写",
                ToggleResult.FALLBACK_APP_SETTINGS)
        }
        // 系统配置本身是 open 才允许开放变体；加密配置永远不走 open（防静默降级）
        val range = if (creds.open) 4..4 else 0..3
        for (v in orderedVariants(Prefs.startVariant, range)) {
            RootShell.exec(startCmd(v, creds), timeoutMs = 12000)
            if (awaitState(true)) {
                if (!creds.open) Prefs.startVariant = v
                Prefs.startVariantFails = 0
                val msg = if (creds.open) "热点已开启（开放网络，无密码）" else "热点已开启（cmd wifi）"
                return done(onDone, true, msg)
            }
        }
        onVariantFail(on = true)
        done(onDone, false, "Root 命令未能开启热点")
    }

    private fun turnOffWork(onDone: (ToggleResult) -> Unit) {
        if (reflectToggle(on = false) && awaitState(false)) {
            return done(onDone, true, "热点已关闭（系统共享）")
        }
        if (!RootShell.available()) {
            return done(onDone, false, "无法直接关闭热点")
        }
        val custom = Prefs.customOff.trim()
        if (custom.isNotEmpty()) {
            RootShell.exec(custom, timeoutMs = 15000)
            if (awaitState(false)) return done(onDone, true, "热点已关闭（自定义命令）")
        }
        // 关闭不需要凭据：变体 0/1 不带 SSID；变体 2 需 SSID，读不到就跳过
        val ssid = apCreds()?.ssid
        val range = if (ssid != null) 0..2 else 0..1
        for (v in orderedVariants(Prefs.stopVariant, range)) {
            RootShell.exec(stopCmd(v, ssid), timeoutMs = 12000)
            if (awaitState(false)) {
                Prefs.stopVariant = v
                Prefs.stopVariantFails = 0
                return done(onDone, true, "热点已关闭（cmd wifi）")
            }
        }
        onVariantFail(on = false)
        done(onDone, false, "Root 命令未能关闭热点")
    }

    /** 整轮变体失败计数；连续 N 轮全败说明缓存变体可能已失效（OTA/ROM 变更），重置掉。 */
    private fun onVariantFail(on: Boolean) {
        if (on) {
            val n = Prefs.startVariantFails + 1
            if (n >= VARIANT_FAIL_RESET) { Prefs.startVariant = -1; Prefs.startVariantFails = 0 }
            else Prefs.startVariantFails = n
        } else {
            val n = Prefs.stopVariantFails + 1
            if (n >= VARIANT_FAIL_RESET) { Prefs.stopVariant = -1; Prefs.stopVariantFails = 0 }
            else Prefs.stopVariantFails = n
        }
    }

    private fun done(cb: (ToggleResult) -> Unit, ok: Boolean, msg: String,
                     fallback: Int = ToggleResult.FALLBACK_SYSTEM_PAGE) {
        main.post {
            busy.set(false)
            cb(ToggleResult(ok, msg, fallback))
        }
    }

    /** 轮询验证状态是否翻转；需连续两次读到目标状态（间隔 600ms，约 1.2 秒）才算稳定，
     *  避免系统短暂上报目标状态后又回滚被误判为成功；状态完全读不到时按失败处理（宁可信其无）。 */
    private fun awaitState(target: Boolean, timeoutMs: Long = 6000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var hits = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(600)
            // 轮询内 root 探测用较短超时，避免单次 dumpsys 卡死把预算撑爆
            hits = if (probeState(4000) == target) hits + 1 else 0
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
                    handler.post {
                        runCatching { m.invoke(cm, *args.toTypedArray()) }
                            .onFailure { Log.w(TAG, "startTethering invoke failed: $it") }
                    }
                    invoked = true
                    break@outer
                }
            } else {
                for (m in ConnectivityManager::class.java.methods) {
                    if (m.name != "stopTethering") continue
                    val pt = m.parameterTypes
                    if (pt.size == 1 && pt[0] == Int::class.javaPrimitiveType) {
                        m.isAccessible = true
                        handler.post {
                            runCatching { m.invoke(cm, TETHERING_WIFI) }
                                .onFailure { Log.w(TAG, "stopTethering invoke failed: $it") }
                        }
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

    // ---------------- 凭据解析（自定义进阶 > 系统 XML > 无） ----------------

    private data class ApCreds(val ssid: String, val pass: String?, val open: Boolean)

    private val SYS_AP_PATHS = listOf(
        "/data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml", // Android 11+
        "/data/misc/wifi/WifiConfigStoreSoftAp.xml",                    // 旧版本
    )

    /** 热点凭据：进阶自定义优先，否则读系统配置；null = 拿不到（调用方报错，不伪造）。 */
    private fun apCreds(): ApCreds? {
        if (Prefs.customApConfig) {
            val s = Prefs.apSsid.trim()
            val p = Prefs.apPass.trim()
            // 进阶模式要求完整凭据（开放热点只允许来自系统配置，见 P2-5）
            if (s.isNotEmpty() && p.length >= 8) return ApCreds(s, p, open = false)
            return null
        }
        return readSystemApConfig()
    }

    /** root 读取系统 SoftAp 配置；各候选路径或解析失败返回 null。 */
    private fun readSystemApConfig(): ApCreds? {
        for (p in SYS_AP_PATHS) {
            val xml = RootShell.exec("cat $p", timeoutMs = 8000).out
            // AOSP WifiConfigStore 子元素格式（Android 11+ 实测）：
            //   <SoftAp><string name="WifiSsid">&quot;ssid&quot;</string>
            //           <int name="SecurityType" value="1" />
            //           <string name="Passphrase">pass</string>…</SoftAp>
            val tag = Regex("<SoftAp\\b[^>]*>").find(xml)?.value ?: continue
            val body = Regex("<SoftAp\\b[^>]*>([\\s\\S]*?)</SoftAp>").find(xml)
                ?.groupValues?.get(1) ?: continue
            val ssid = (
                strElem(body, "WifiSsid") ?: attr(tag, "SSID")
            )?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: continue
            val pass = (
                strElem(body, "Passphrase") ?: attr(tag, "Passphrase")
            )?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
            // SecurityType：0=open；加密配置却读不到密码 = 凭据不全，不可当开放用
            val security = Regex("<int name=\"SecurityType\" value=\"(\\d+)\"")
                .find(body)?.groupValues?.get(1)?.toIntOrNull()
            if (security != null && security != 0 && pass == null) continue
            return ApCreds(ssid, pass, open = security == 0 || (security == null && pass == null))
        }
        return null
    }

    private fun strElem(body: String, name: String): String? =
        Regex("<string name=\"$name\">(.*?)</string>").find(body)
            ?.groupValues?.get(1)?.let(::unescapeXml)

    private fun attr(el: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"").find(el)?.groupValues?.get(1)?.let(::unescapeXml)

    /** XML 实体反转义；&amp; 必须最后处理，避免双重反转义。 */
    private fun unescapeXml(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    // ---------------- Root cmd wifi 命令变体 ----------------

    private fun orderedVariants(cached: Int, range: IntRange): List<Int> =
        listOfNotNull(cached.takeIf { it in range }) + range.filter { it != cached }

    /** shell 单引号包裹（内部 ' → '\''），防凭据中的 $ ` " \ 等字符破坏命令。 */
    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun startCmd(v: Int, c: ApCreds): String = when (v) {
        0 -> "cmd wifi start-softap ${shq(c.ssid)} wpa2 ${shq(c.pass!!)}"     // T508N 实测语法
        1 -> "cmd wifi start-softap ${shq(c.ssid)} wpa3 ${shq(c.pass!!)}"
        2 -> "cmd wifi start-softap ap0 wpa2 ${shq(c.ssid)} ${shq(c.pass!!)}" // AOSP ifname 风格
        3 -> "cmd wifi start-softap ap0 wpa2-psk ${shq(c.ssid)} ${shq(c.pass!!)}"
        else -> "cmd wifi start-softap ${shq(c.ssid)} open"                   // 仅 creds.open 时可达
    }

    private fun stopCmd(v: Int, ssid: String?): String = when (v) {
        0 -> "cmd wifi stop-softap"
        1 -> "cmd wifi stop-softap ap0"
        else -> "cmd wifi stop-softap ${shq(ssid!!)}" // 调用方保证 ssid 非空才选变体 2
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
            val creds = apCreds()
            // 系统热点配置单独列出展示（密码只显示位数）；
            // 进阶模式下 creds 是自定义值，需另读一次系统 XML
            val sys = if (Prefs.customApConfig) readSystemApConfig() else creds
            if (sys != null) {
                sb.appendLine("系统热点名称：${sys.ssid}")
                sb.appendLine(
                    "系统热点密码：" + (
                        sys.pass?.let { "${it.length}位（${"*".repeat(it.length)}）" }
                            ?: "无（开放网络）"
                        )
                )
            }
            if (creds != null) {
                val src = if (Prefs.customApConfig) "自定义（进阶）" else "系统配置"
                // 密码打码后再进命令文本，诊断页不出现明文密码
                val masked = creds.copy(pass = creds.pass?.let { "****" })
                val v = if (masked.open) 4 else Prefs.startVariant.takeIf { it in 0..3 } ?: 0
                sb.appendLine("热点凭据：$src")
                sb.appendLine("当前生效命令：${startCmd(v, masked)}")
            } else {
                val why = if (Prefs.customApConfig) "自定义未填完整" else "系统配置读取失败"
                sb.appendLine("热点凭据：无（$why）")
                sb.appendLine("cmd wifi 直启：不可用，请到设置页填写名称/密码")
            }
        }
        return sb.toString()
    }
}
