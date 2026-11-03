plugins {
    alias(libs.plugins.notioncal.android.library)
    alias(libs.plugins.notioncal.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ysdoing.notioncal.core.sync"
}

dependencies {
    api(project(":core:data"))
    api(project(":core:notion"))
    // WX-01 WeatherWorker만 날씨 제공처를 부른다.
    implementation(project(":core:weather"))
    implementation(libs.room.runtime)
    // M5 표지 내려받기(토큰 없는 별도 클라이언트). :core:notion은 OkHttp를 api로 내놓지 않는다.
    implementation(libs.okhttp)
    implementation(libs.room.ktx)
    api(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.datastore.preferences)
}
