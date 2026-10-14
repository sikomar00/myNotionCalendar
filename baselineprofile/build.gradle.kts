plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

// PF-01·S25-05: Baseline Profile 생성과 Macrobenchmark 측정. 실기기(S25)에서 돈다.
android {
    namespace = "com.ysdoing.notioncal.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.uiautomator)
}
