plugins {
    alias(libs.plugins.notioncal.android.library)
    alias(libs.plugins.notioncal.android.compose)
    alias(libs.plugins.notioncal.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ysdoing.notioncal.feature.diagnostics"
}

// 화면 기준 이미지는 저장소에 두고 verifyRoborazziDebug로 비교한다.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    // 진단 화면도 :core:data만 읽는다. 다음 주기 시각 등 :core:sync 정보는 :core:data 인터페이스로 받는다(PRD §7).
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx) // FileProvider
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    debugImplementation(libs.compose.ui.test.manifest)
}
