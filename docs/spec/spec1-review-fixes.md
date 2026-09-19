# spec1 — 审查问题批量修复 + 开关/进阶双模式（一次性解决）

优先级: **P1 起步，整批一次实施**
状态: **已实施 2026-09-19**（`./gradlew assembleDebug` 通过；真机手工验收项待确认）
来源: 2026-09-19 多 Agent 代码审查（12 项 spec 合并）+ 用户补充需求

## 执行说明

- 按 F0 → P1 → P2 → P3 顺序实施；每项独立可验收，但都算进同一批提交。
- 改代码遵守 AGENTS.md：最小改动、用户可见文案用中文、标识符英文。
- 全部完成后统一跑 `./gradlew assembleDebug`。

---

## F0 — 需求项：默认「开关模式」/ 进阶「自定义名称密码」

**用户体感 bug**：在系统热点设置页已配好名称密码，只想开关热点。
当前 `apSsid`/`apPass` 为空时，`startCmd` 会伪造 `Build.MODEL` + `"12345678"`
（HotspotEngine.kt:217-224），`cmd wifi` 路径开出的热点与系统配置完全不符——
已配对设备连不上，等于一个隐形的错误热点。

**已核实的约束**（修复设计依据）：
- `cmd wifi start-softap` **无参形式不存在**——AOSP
  `WifiShellCommand.buildSoftApConfiguration` 用 `getNextArgRequired()`
  强制要 ssid + 加密类型（main 分支源码已查证）。所以"不传凭据用系统配置"
  这条路走不通。
- 但 root 下可直接读系统 SoftAp 配置：
  `/data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml`（Android 11+，
  apexdata）或 `/data/misc/wifi/WifiConfigStoreSoftAp.xml`（旧版本），
  XML 内含明文 ssid / passphrase。
- 反射路径 `startTethering` 本来就使用系统配置——默认模式下它是主力，
  无需改动。

**修复方案**：

1. `Prefs` 加 `customApConfig: Boolean = false`（设置页 checkbox
   「自定义热点名称/密码（进阶）」，默认不勾）。
2. `SettingsActivity`：SSID/密码两个 EditText 默认隐藏（或置灰），勾选
   进阶框才显示；不勾时引擎完全忽略这两个 pref。
3. `HotspotEngine`：
   - 新增 `readSystemApConfig(): Pair<String,String>?`，用
     `RootShell.exec("cat <path>")` 按上述两个候选路径读 XML，正则解析
     `ssid`/`passphrase`（XML 属性形式 `"ssid"="..."`，注意实体转义
     `&quot;`/`&amp;`）。
   - 凭据来源顺序：**自定义（进阶勾选且非空）> 系统 XML > 跳过变体链**。
     即：拿不到任何凭据时不跑 `start-softap` 变体，直接走兜底设置页——
     不再伪造默认凭据。
   - `ssidOrDefault()`/`"12345678"` 删除；`stopCmd` 变体 2 需要 SSID 时
     同样走该来源，拿不到则该变体跳过。
   - `diag()` 标注凭据来源：自定义 / 系统 / 无（无密码明文，见 P2-9）。
4. 注意点：系统 XML 里 ssid 可能是带引号字符串、passphrase 为空表示
   开放配置——解析时按存在性处理，空 passphrase 只用于加密类型判定，
   不得拿来跑 open 变体（P2-5 的规则仍生效）。

**验收**：
- [ ] 全新安装默认状态下，设置页不显示/不要求 SSID 密码输入即可开关。
- [ ] root + 反射失效的设备上，`cmd wifi` 开出的热点 SSID/密码与系统
      设置页里的配置一致（可用 `dumpsys wifi` 或第二台设备扫码验证）。
- [ ] 读不到系统 XML 时不再生成 `"12345678"` 热点，而是直接兜底开设置页。
- [ ] 勾选进阶并填自定义凭据后，开出的是自定义热点。
- [ ] grep 确认 `Build.MODEL`、`"12345678"` 从凭据生成路径中移除。

---

## P1-1 — 磁贴在 API 26 点击即崩

**位置**：`HotspotTileService.kt:26`
**问题**：`isLocked`/`unlockAndRun(Runnable)` 为 API 27 方法，minSdk=26，
无版本守卫 → Android 8.0 点磁贴 `NoSuchMethodError`，主交互路径死。

**修复**：
```kotlin
override fun onClick() {
    if (Build.VERSION.SDK_INT >= 27 && isLocked) unlockAndRun { act() } else act()
}
```
**验收**：守卫存在；assembleDebug 通过；lint NewApi 不再报（若跑 lint）。

---

## P1-2 — Root 可用性把"不确定"缓存成 false

**位置**：`RootShell.kt:18-27`
**问题**：Magisk 首次授权弹窗期间 `su id` 超时 → `exitCode=-1` → `false`
被永久缓存；用户授权后仍被判无 root，直到进程重启。`forgetCache()` 是
死代码无调用方。

**修复**：
1. 仅确定性结果入缓存：`if (r.exitCode != -1) rootCache = ok`。
2. `SettingsActivity` 加「重新检测 Root」（诊断区旁），调用
   `forgetCache()` + `available()` 并刷新显示。
**验收**：exitCode=-1 不写缓存；`forgetCache` 有真实调用点；超时后
授权成功、不重启进程即可恢复 root 路径。

---

## P1-3 — SSID/密码未转义拼进 `su -c`

**位置**：`HotspotEngine.kt:222-238`（`startCmd`/`stopCmd` 全部插值点）
**问题**：双引号零转义插值，`"`、`$`、`` ` ``、`\` 破坏命令语法；
合法 WPA 密码（如 `pa$$"w0rd`）即可触发。注入面有限（仅本机用户可写
pref）但正确性真实受损。

**修复**：加 `private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"`，
所有插值点（含 F0 新增的系统凭据路径）统一走 `shq`。
**验收**：含引号/$ 的凭据产出的命令字符串正确单引号包裹；grep 无
`\"$ssid\"`/`\"$pass\"` 残留。

---

## P2-4 — 无并发守卫，双击产生竞争线程

**位置**：`HotspotEngine.kt:83-137`
**问题**：`toggle`/`turnOn`/`turnOff` 裸 Thread 无互斥；两条线程读同一
过期 state，可互相撤销或堆叠 su 进程；MainActivity 入口完全无保护。

**修复**：`private val busy = AtomicBoolean(false)`，三个入口
`if (!busy.compareAndSet(false, true)) { done(cb, false, "操作进行中…"); return }`，
`done()` 内 `busy.set(false)`（注意 done 经 `main.post` 异步——在
`main.post` 块里复位，保证回调先执行）。
**验收**：连点 ≥3 次只执行一次，其余收到「进行中」提示；无并行 su 会话。

---

## P2-5 — 变体 4 静默创建开放热点并永久缓存

**位置**：`HotspotEngine.kt:100-106, 230`
**问题**：wpa2/wpa3 全失败时兜底 `open`，成功报「热点已开启（cmd wifi）」
且缓存 `startVariant=4`，用户以为有密码实际是开放网络。

**修复**（配合 F0 的凭据来源）：open 变体仅在凭据来源为「系统 XML 且
系统本身就是 open 配置」时允许；自定义/读不到时不跑 open。成功消息
区分：`"热点已开启（开放网络，无密码）"`。open 不写入 `startVariant` 缓存。
**验收**：加密变体全败时不再静默开 open；open 成功时消息含「无密码」；
`startVariant` 不会被写成 open 变体号。

---

## P2-6 — `rootApState()` 在非 AOSP dumpsys 格式上误报 false

**位置**：`HotspotEngine.kt:73-78`
**问题**：`grep -c ROLE_SOFTAP_TETHERED` 返回 0 即判 false；报
`ROLE_SOFTAP_LOCAL_ONLY`/OEM 令牌的 ROM 会把"开着"读成"关"，
`toggle` 永远无法关、`awaitState` 成功后仍判失败。

**修复**：加第二信号交叉验证（`dumpsys wifi | grep -iE 'softap|ap state'`
或 `ip addr show ap0`）；输出中出现可识别 SoftAp 段才把 0 解释为 false，
格式不认识返回 `null`；顺带检查 grep 退出码。
**验收**：存在"格式可识别才给 false"分支；不认识 → `null` → 磁贴显示
「状态未知」。

---

## P2-7 — exported `MainActivity` 的 `mode` extra 无来源校验

**位置**：`AndroidManifest.xml:16` + `MainActivity.kt:26`
**问题**：任意 App 可 `--es mode toggle` 驱动反射/root 路径。
**修复**：仅当 `intent.action` 为内部自定义 action（与 `shortcuts.xml`
对齐的 `com.ray.hotspot.action.OPEN_PAGE`）时才读 `mode`；否则用
`Prefs.launcherClick`。
**验收**：外部 action 带 `mode=toggle` → 走默认行为而非 toggle；
shortcut「打开热点页」仍正常。

---

## P2-8 — su 超时留 root 孤儿进程 + 三处小卫生

**位置**：`RootShell.kt:41-43, 33-53`
**问题**：`destroyForcibly()` 只杀 su 壳，`-c` 子命令以 root 继续跑
（可与后续变体竞争）；stdin 是开放管道（`read` 型命令卡到超时）；
`sh_*` 临时文件崩溃残留；`err: null` 无信息。

**修复**：destroy 后 `p.waitFor(200, MS)`；`pb.redirectInput(File("/dev/null"))`；
`init()` 清扫 `cacheDir` 的 `sh_*`；`t.message ?: t.toString()`；KDoc 注明
"Android 无进程组 kill，超时后子命令可能仍执行完"。
**验收**：`exec("sleep 60",1000)` 超时后 su 消失；`exec("read x",2000)`
~2s 返回；强杀后重进无 `sh_*` 残留。

---

## P2-9 — 密码两处明文暴露

**位置**：`activity_settings.xml:72-78`、`HotspotEngine.kt:278`
**问题**：密码框 `textVisiblePassword` 明文回显 + `SettingsActivity` 必须
exported（可被任意 App 拉起肩窥）；`diag()` 明文打印密码进诊断文本。

**修复**：inputType 改 `textPassword` + 「显示密码」checkbox 切换；
`diag()` 密码替换 `****`；`Prefs.apPass` 加注释说明明文存储是有意取舍
（MODE_PRIVATE + allowBackup=false + 零依赖）。
**验收**：设置页默认圆点；diag 无真实密码；磁贴长按仍拉起设置页。

---

## P2-10 — AGP alpha → stable

**位置**：`gradle/libs.versions.toml:2` `agp = "9.2.0-alpha07"`
**修复**：改 `9.2.0`（stable 已于 2026-04 发布；wrapper gradle-9.5.1 ≥ 要求
的 9.4.1，无需动）。顺手在 `app/build.gradle.kts` 加注释：无独立 Kotlin
插件是 AGP 9 内置行为。
**验收**：stable 版本号 + assembleDebug 通过 + apk 正常产出。

---

## P2-11 — 文档漂移 + 重复 TAG + targetSdk 理由未记录

**问题**：
1. `README.md:17`「单 Activity 双 Service」写反（实际 2 Activity + 1 Service）；
2. `README.md:53` 缓存描述夸大（只缓存 cmd wifi 变体序号）；
3. `AGENTS.md` 磁贴 label 同步对象写错（实际 `AndroidManifest.xml:44` +
   `MainActivity.kt:61`，非 SettingsActivity）；
4. `targetSdk=34` 是承重墙未文档化：(a) 灰名单反射按 targetSdk 分级；
   (b) 避开 Android 15 强制 edge-to-edge 破坏 Settings 布局；
5. `MainActivity.kt:19` 与 `RootShell.kt:9` 都抄 `TAG="HotspotEngine"`。

**修复**：README 两处改准；AGENTS.md 同步对象改 manifest+MainActivity；
`build.gradle.kts` targetSdk 行加注释 + AGENTS.md 关键约束加「targetSdk
勿升」；两个 TAG 改类名对应值。
**验收**：grep 无「单 Activity 双 Service」；TAG 三处各异；targetSdk 有注释。

---

## P3 批次（逐条小修，全部计入本批）

| # | 位置 | 修复 |
|---|---|---|
| P3-1 | `HotspotEngine.kt:144-151` | `awaitState` 内状态探测用较短 timeout（≤4s）或按剩余 deadline 扣减；最坏 toggle ≤ ~1min |
| P3-2 | `HotspotEngine.kt:85` | null→turnOn 是刻意取舍，加注释说明即可 |
| P3-3 | `HotspotEngine.kt:184-202` | `runCatching` 的 `onFailure` 加 `Log.w`，Android 16 排障可见拒绝原因 |
| P3-4 | `Prefs`/`HotspotEngine.kt:214` | 变体连续失败计数，N 次（建议 3）后重置 `startVariant`/`stopVariant` 为 -1 |
| P3-5 | `MainActivity.kt:55-64` | `requestAddTileService` 回调收到失败/拒绝时 `Prefs.tilePrompted = false` |
| P3-6 | `MainActivity.kt:32-38` | toggle 发起时立即 toast「正在切换热点…」（参照 SettingsActivity.kt:120） |
| P3-7 | `MainActivity.kt:46-51`、`SettingsActivity.kt:135-140` | `openHotspotSettings` 返回 false 时 toast「无法打开系统热点设置页」 |
| P3-8 | `MainActivity.kt:32-38` | toggle 中途进程可被杀——AGENTS.md 或注释记录为已知限制 |
| P3-9 | `HotspotEngine.kt:278` | `takeIf { it >= 0 }` 改 `it in 0..4`，与 `orderedVariants` 一致 |
| P3-10 | `HotspotTileService.kt:59-66` | API 26-28 无 subtitle——注释记录取舍即可 |
| P3-11 | `gradle.properties:2-3` | `useAndroidX`/`kotlin.code.style` 删除或注释为无效配置 |
| P3-12 | `.gitignore` | 补 `*.keystore`、`*.jks`、`keystore.properties`、`*.apk`、`.kotlin/`、`.cxx/` |
| P3-13 | `SettingsActivity.kt:120,122` | Toast 的 `this` 统一改 `applicationContext` |
| P3-14 | 仓库 | `gradlew.bat` 缺失——AGENTS.md 记录「仅 POSIX 构建」即可，不补文件 |

## 总体验收

- [ ] 每项验收清单逐条通过或标注「不改，原因」。
- [x] `./gradlew assembleDebug` 编译通过（2026-09-19，AGP 9.2.0 stable + Gradle 9.5.1）。
- [ ] 手工回归（按 README）：磁贴开关、启动器 page/toggle、设置页测试
      开/关、磁贴长按进设置页。
- [ ] F0 回归：默认模式不再需要输入名称密码；进阶模式自定义凭据生效。
- [x] git diff 范围内无顺手重构、无无关格式化（AGENTS.md 最小改动约束）。

## 实施记录（2026-09-19）

整批已落地，`assembleDebug` 通过。与 spec 草稿的实现差异：

- **回调结构**：`(ok, msg)` 升级为 `ToggleResult(ok, msg, fallback)`，
  `fallback ∈ {NONE, SYSTEM_PAGE, APP_SETTINGS}`——缺凭据时调用方打开
  **本 App 设置页**（FALLBACK_APP_SETTINGS），普通失败开系统热点页，
  busy 拒绝不开任何页面。三个调用方（TileService/MainActivity/
  SettingsActivity）均已适配。
- **进阶凭据完整性**：customApConfig 勾选但 ssid 为空或密码 <8 位 →
  视为无凭据（报错进设置页），不会静默开开放热点。
- **关闭路径不需要凭据**：`stop-softap` 变体 0/1 无参可跑；仅变体 2
  需 SSID，读不到凭据时自动收窄到 0..1。
- **open 变体**：仅当凭据来源为「系统 XML 且系统配置本身是 open」时
  可达（range 收窄为 4..4），成功消息明示「开放网络，无密码」，且不写
  `startVariant` 缓存。
- **P3-5 附带**：`requestAddTileService` 抛异常路径同样复位
  `tilePrompted`。
- 待真机验证：API 26 磁贴、Magisk 授权超时→授权→重检、系统 XML 实际
  解析（不同 Android 版本 `<SoftAp>` 元素格式）、OEM dumpsys 格式。
