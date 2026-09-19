// Kotlin 编译由 AGP 9 内置提供，无需单独的 kotlin-android 插件
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ray.hotspot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ray.hotspot"
        minSdk = 26
        // targetSdk=34 是刻意锁定，勿升：(a) hidden-API 灰名单按 targetSdk 分级，
        // 升高会失去 WifiManager.getWifiApState 等反射可用性；(b) 避开
        // Android 15 强制 edge-to-edge 对 Theme.DeviceDefault.Settings 的破坏
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}
