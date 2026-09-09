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
| `HotspotEngine.kt` | ★ 核心开关策略引擎：①反射 ConnectivityManager.startTethering/stopTethering（≤15 免 Root，真共享）②Root 自定义命令 ③cmd wifi start/stop-softap 多变体（T508N 实测语法为变体 0）④兜底打开系统热点页。状态读取：isWifiApEnabled → getWifiApState(反射) → root dumpsys ROLE_SOFTAP_TETHERED |
| `RootShell.kt` | su 执行器（可用性探测缓存、输出转储到文件避免管道死锁、超时清理） |
| `Prefs.kt` | SharedPreferences 集中读写（档位/SSID/密码/自定义命令/重试计数） |

## 关键约束（改代码前必读）
- **最小改动**：改动范围必须与 issue 诉求严格对应，禁止顺手重构与格式化无关代码。
- 反射调用是隐藏 API：改动 HotspotEngine 的反射逻辑前，先理解 `awaitState()` 轮询验证的设计——任何开关动作都以「状态真的翻转」为准，不信任调用本身的成功。
- `cmd wifi start-softap` 的各变体语法是为不同 ROM 探测设计的（变体顺序缓存于 Prefs.startVariant），不要随意删除变体。
- 用户可见文案（Toast/设置页）用中文；代码标识符用英文。
- 磁贴 label 是「WiFi热点」（strings.xml），变更需同步 SettingsActivity 与文档。

## 测试
本项目无仪器化测试（UI 与系统服务强耦合），验证方式：`./gradlew assembleDebug` 编译通过 +
按 README 手工验证（设置页「测试开启/关闭」按钮）。
