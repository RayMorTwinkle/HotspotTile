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
        // CI 经 -PversionName/-PversionCode 注入 tag 对应版本；本地构建用默认值
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "1.0.0"
    }

    signingConfigs {
        create("release") {
            // keystore 不入库：CI 从 GitHub Secrets 还原到临时路径后经环境变量传入
            val store = System.getenv("KEYSTORE_FILE")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 无 keystore 环境时保持未签名——此类 APK 不可安装，仅 CI（有 secrets）产出正式包
            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}
