plugins {
    alias(libs.plugins.notioncal.android.library)
    alias(libs.plugins.notioncal.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ysdoing.notioncal.core.data"
}

// PF-09: 스키마 JSON을 저장소에 남겨 마이그레이션 테스트의 기준으로 쓴다.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.tink.android)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}
