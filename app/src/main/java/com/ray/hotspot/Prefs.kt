package com.ray.hotspot

import android.content.Context
import android.content.SharedPreferences

/** 所有可配置项集中存放。 */
object Prefs {
    private const val NAME = "hotspot_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    private inline fun <reified T> get(key: String, def: T): T = when (T::class) {
        String::class -> sp.getString(key, def as String) as T
        Boolean::class -> sp.getBoolean(key, def as Boolean) as T
        Int::class -> sp.getInt(key, def as Int) as T
        else -> def
    }

    private fun putString(key: String, v: String) = sp.edit().putString(key, v).apply()
    private fun putBoolean(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()

    /** 应用图标（桌面）点击行为：page=打开系统热点页 / toggle=切换热点 / settings=打开本应用设置 */
    var launcherClick: String
        get() = get("launcher_click", "page")
        set(v) = putString("launcher_click", v)

    /** 控制中心磁贴点击行为：toggle=切换热点 / page=打开系统热点页 */
    var tileClick: String
        get() = get("tile_click", "toggle")
        set(v) = putString("tile_click", v)

    /** 控制中心磁贴长按行为：settings=本应用设置 / page=系统热点页 / system=系统默认(应用信息) */
    var tileLongPress: String
        get() = get("tile_longpress", "settings")
        set(v) = putString("tile_longpress", v)

    /**
     * 进阶开关：自定义热点名称/密码。
     * false（默认）= cmd wifi 直启时读取系统热点配置（root 读
     * WifiConfigStoreSoftAp.xml）；读不到会报错并请用户来此开启后填写。
     */
    var customApConfig: Boolean
        get() = get("custom_ap_config", false)
        set(v) = putBoolean("custom_ap_config", v)

    /** 热点名称（仅 customApConfig=true 时生效） */
    var apSsid: String
        get() = get("ap_ssid", "")
        set(v) = putString("ap_ssid", v)

    /**
     * 热点密码（仅 customApConfig=true 时生效）。
     * 明文存储是有意取舍：MODE_PRIVATE + allowBackup=false + 零依赖（无
     * EncryptedSharedPreferences）；本文件与 ap_ssid 的凭据经单引号转义后
     * 才会进入 shell 命令。
     */
    var apPass: String
        get() = get("ap_pass", "")
        set(v) = putString("ap_pass", v)

    /** 高级：Root 自定义开启命令（留空=用内置策略） */
    var customOn: String
        get() = get("custom_on", "")
        set(v) = putString("custom_on", v)

    /** 高级：Root 自定义关闭命令 */
    var customOff: String
        get() = get("custom_off", "")
        set(v) = putString("custom_off", v)

    /** 首次启动引导添加磁贴只弹一次 */
    var tilePrompted: Boolean
        get() = get("tile_prompted", false)
        set(v) = putBoolean("tile_prompted", v)

    /** 验证成功的 cmd wifi 命令变体，加速后续切换 */
    var startVariant: Int
        get() = get("start_variant", -1)
        set(v) = putInt("start_variant", v)

    var stopVariant: Int
        get() = get("stop_variant", -1)
        set(v) = putInt("stop_variant", v)

    /** cmd wifi 变体整轮失败计数；达到阈值后重置缓存变体（见 HotspotEngine） */
    var startVariantFails: Int
        get() = get("start_variant_fails", 0)
        set(v) = putInt("start_variant_fails", v)

    var stopVariantFails: Int
        get() = get("stop_variant_fails", 0)
        set(v) = putInt("stop_variant_fails", v)
}
