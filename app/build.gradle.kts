plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ray.hotspot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ray.hotspot"
        minSdk = 26
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
