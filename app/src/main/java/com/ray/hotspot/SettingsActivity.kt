package com.ray.hotspot

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

/** 应用设置页：热点名称/密码（进阶）、磁贴与图标行为、Root 自定义命令、诊断。 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HotspotEngine.init(applicationContext)

        // 从控制中心磁贴长按进入：按用户配置改道（在 inflate 前判断，省去无用布局）
        if (intent?.action == TileService.ACTION_QS_TILE_PREFERENCES) {
            when (Prefs.tileLongPress) {
                "page" -> {
                    openSystemPage()
                    finish()
                    return
                }
                "system" -> {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                    finish()
                    return
                }
            }
        }

        setContentView(R.layout.activity_settings)

        val diag = findViewById<TextView>(R.id.diag)
        val etSsid = findViewById<EditText>(R.id.et_ssid)
        val etPass = findViewById<EditText>(R.id.et_pass)
        val cbCustomAp = findViewById<CheckBox>(R.id.cb_custom_ap)
        val layoutCustomAp = findViewById<LinearLayout>(R.id.layout_custom_ap)
        val cbShowPass = findViewById<CheckBox>(R.id.cb_show_pass)
        val etCustomOn = findViewById<EditText>(R.id.et_custom_on)
        val etCustomOff = findViewById<EditText>(R.id.et_custom_off)
        val rgTileClick = findViewById<RadioGroup>(R.id.rg_tile_click)
        val rgTileLongpress = findViewById<RadioGroup>(R.id.rg_tile_longpress)
        val rgLauncher = findViewById<RadioGroup>(R.id.rg_launcher)
        val btnOn = findViewById<Button>(R.id.btn_test_on)
        val btnOff = findViewById<Button>(R.id.btn_test_off)
        val btnRecheckRoot = findViewById<Button>(R.id.btn_recheck_root)

        // ---- 载入 ----
        cbCustomAp.isChecked = Prefs.customApConfig
        layoutCustomAp.visibility = if (Prefs.customApConfig) View.VISIBLE else View.GONE
        etSsid.setText(Prefs.apSsid)
        etPass.setText(Prefs.apPass)
        etCustomOn.setText(Prefs.customOn)
        etCustomOff.setText(Prefs.customOff)
        rgTileClick.check(
            when (Prefs.tileClick) {
                "page" -> R.id.rb_tc_page
                else -> R.id.rb_tc_toggle
            }
        )
        rgTileLongpress.check(
            when (Prefs.tileLongPress) {
                "page" -> R.id.rb_lp_page
                "system" -> R.id.rb_lp_system
                else -> R.id.rb_lp_settings
            }
        )
        rgLauncher.check(
            when (Prefs.launcherClick) {
                "toggle" -> R.id.rb_lc_toggle
                "settings" -> R.id.rb_lc_settings
                else -> R.id.rb_lc_page
            }
        )

        // ---- 即时保存 ----
        val refreshTile = {
            try {
                TileService.requestListeningState(
                    this,
                    ComponentName(this, HotspotTileService::class.java)
                )
            } catch (_: Throwable) { }
        }
        cbCustomAp.setOnCheckedChangeListener { _, checked ->
            Prefs.customApConfig = checked
            layoutCustomAp.visibility = if (checked) View.VISIBLE else View.GONE
            loadDiag(diag)
        }
        cbShowPass.setOnCheckedChangeListener { _, checked ->
            etPass.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etPass.setSelection(etPass.text.length)
        }
        etSsid.addTextChangedListener(simpleWatcher(etSsid) { Prefs.apSsid = it.text.toString() })
        etPass.addTextChangedListener(simpleWatcher(etPass) { Prefs.apPass = it.text.toString() })
        etCustomOn.addTextChangedListener(simpleWatcher(etCustomOn) { Prefs.customOn = it.text.toString() })
        etCustomOff.addTextChangedListener(simpleWatcher(etCustomOff) { Prefs.customOff = it.text.toString() })
        rgTileClick.setOnCheckedChangeListener { _, id ->
            Prefs.tileClick = if (id == R.id.rb_tc_page) "page" else "toggle"
            refreshTile()
        }
        rgTileLongpress.setOnCheckedChangeListener { _, id ->
            Prefs.tileLongPress = when (id) {
                R.id.rb_lp_page -> "page"
                R.id.rb_lp_system -> "system"
                else -> "settings"
            }
        }
        rgLauncher.setOnCheckedChangeListener { _, id ->
            Prefs.launcherClick = when (id) {
                R.id.rb_lc_toggle -> "toggle"
                R.id.rb_lc_settings -> "settings"
                else -> "page"
            }
        }

        // ---- 测试按钮 ----
        fun runTest(turnOn: Boolean, btn: Button, other: Button) {
            btn.isEnabled = false
            other.isEnabled = false
            Toast.makeText(
                applicationContext,
                if (turnOn) "正在开启…" else "正在关闭…",
                Toast.LENGTH_SHORT
            ).show()
            val cb: (HotspotEngine.ToggleResult) -> Unit = { r ->
                Toast.makeText(applicationContext, r.msg, Toast.LENGTH_SHORT).show()
                btn.isEnabled = true
                other.isEnabled = true
                loadDiag(diag)
            }
            if (turnOn) HotspotEngine.turnOn(cb) else HotspotEngine.turnOff(cb)
        }
        btnOn.setOnClickListener { runTest(true, btnOn, btnOff) }
        btnOff.setOnClickListener { runTest(false, btnOn, btnOff) }

        // 重新探测 Root（Magisk 授权弹窗曾超时导致缓存假阴性时使用）
        btnRecheckRoot.setOnClickListener {
            RootShell.forgetCache()
            loadDiag(diag)
        }

        loadDiag(diag)
    }

    private fun openSystemPage(): Boolean =
        HotspotEngine.openHotspotSettings { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        }

    private fun loadDiag(diag: TextView) {
        diag.text = "正在检测…"
        Thread {
            val text = runCatching { HotspotEngine.diag() }.getOrElse { "诊断失败：$it" }
            diag.post { diag.text = text }
        }.start()
    }

    private fun simpleWatcher(editText: EditText, block: (EditText) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                block(editText)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
        }
}
