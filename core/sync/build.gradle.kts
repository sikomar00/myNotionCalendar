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
    implementation(libs.room.ktx)
    api(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.datastore.preferences)
}
