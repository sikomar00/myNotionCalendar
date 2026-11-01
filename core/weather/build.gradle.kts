plugins {
    alias(libs.plugins.notioncal.android.library)
    alias(libs.plugins.notioncal.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ysdoing.notioncal.core.weather"
}

dependencies {
    // WX-01 날씨 제공처(Open-Meteo)를 이 모듈 안에 가둔다. 데이터 모듈에 의존하지 않고, :core:sync(WeatherWorker)만 쓴다.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
