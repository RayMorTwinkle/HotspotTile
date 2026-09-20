# AGENTS.md — HotspotTile 项目导览（供 AI Agent 阅读）

## 项目定位
把被平板厂商隐藏的「WiFi 热点 / WLAN 共享」开关还给用户：桌面图标一键直达系统热点设置页；
控制中心「WiFi热点」磁贴直接开关热点（Android ≤15 免 Root 反射直控；Root 增强路径）。

## 技术栈与构建
- 纯 Kotlin + Android Framework API，**零第三方依赖**（无 AndroidX / 无 Compose）。
- compileSdk 36 / minSdk 26 / targetSdk 34，包名 `com.ray.hotspot`。
- 构建：`./gradlew assembleDebug`（需 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools）。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 代码地图（app/src/main/java/com/ray/hotspot/）
| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 桌面入口：按 Prefs.launcherClick 分发（page/toggle/settings），执行后 finish；Android 13+ 首次启动调用 requestAddTileService 引导加磁贴 |
| `SettingsActivity.kt` | 应用设置页（布局 res/layout/activity_settings.xml）；处理 QS_TILE_PREFERENCES（磁贴长按）；所有偏好即时保存 |
| `HotspotTileService.kt` | 控制中心磁贴：onClick 按 Prefs.tileClick 执行切换或打开设置页；onStartListening 刷新状态与副标题 |
| `HotspotEngine.kt` | ★ 核心开关策略引擎：①反射 ConnectivityManager.startTethering/stopTethering（≤15 免 Root，真共享）②Root 自定义命令 ③cmd wifi start/stop-softap 多变体（T508N 实测语法为变体 0）④兜底打开系统热点页。状态读取：isWifiApEnabled → getWifiApState(反射) → root dumpsys（ROLE_SOFTAP_TETHERED/LOCAL_ONLY + SoftAp 段识别）。热点凭据：进阶自定义 > root 读 WifiConfigStoreSoftAp.xml > 报错进设置页，不伪造 |
| `RootShell.kt` | su 执行器（可用性探测缓存——仅缓存确定性结果、输出转储到文件避免管道死锁、超时清理） |
| `Prefs.kt` | SharedPreferences 集中读写（行为/进阶开关/SSID/密码/自定义命令/变体缓存与失败计数） |

## 关键约束（改代码前必读）
- **最小改动**：改动范围必须与 issue 诉求严格对应，禁止顺手重构与格式化无关代码。
- 反射调用是隐藏 API：改动 HotspotEngine 的反射逻辑前，先理解 `awaitState()` 轮询验证的设计——任何开关动作都以「状态真的翻转」为准，不信任调用本身的成功。
- `cmd wifi start-softap` 的各变体语法是为不同 ROM 探测设计的（变体顺序缓存于 Prefs.startVariant），不要随意删除变体。
- 用户可见文案（Toast/设置页）用中文；代码标识符用英文。
- 磁贴 label 是「WiFi热点」（strings.xml），引用点是 `AndroidManifest.xml` 的 service label 与 `MainActivity.kt` 的 requestAddTileService，变更需同步文档。
- **targetSdk=34 勿升**：(a) hidden-API 灰名单按 targetSdk 分级，升 35/36 会失去 `WifiManager.getWifiApState` 等反射可用性；(b) 避开 Android 15 强制 edge-to-edge 对 `Theme.DeviceDefault.Settings` 布局的破坏。
- 热点凭据不伪造：`cmd wifi` 路径拿不到凭据（进阶未填 + 系统 XML 读不到）时必须报错并引导用户去设置页，不得恢复 Build.MODEL/默认密码兜底。
- 已知限制（有意取舍）：MainActivity toggle 模式进程可被系统杀死在中途；su 超时只杀壳进程、子命令可能仍执行完（Android 无进程组 kill）；构建仅支持 POSIX（无 gradlew.bat）。

## 测试
本项目无仪器化测试（UI 与系统服务强耦合），验证方式：`./gradlew assembleDebug` 编译通过 +
按 README 手工验证（设置页「测试开启/关闭」按钮）。发布前对照 `docs/verification-checklist.md` 过真机清单。

## 发布
- 触发：Actions → Release workflow（version 可留空自动 patch+1）；release agent 无参触发是硬契约。
- 版本号流程：**先把 `app/build.gradle.kts` 的 `versionName`/`versionCode` 默认值 bump 到目标版本**（F-Droid 源码构建依赖入库值），再触发 workflow；CI 的 `-P` 参数按 tag 覆盖。
- 签名：keystore 不入库，经 Secrets（RELEASE_KEYSTORE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD）还原；证书指纹公布于 README 安装节。
