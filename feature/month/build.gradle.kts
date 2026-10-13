plugins {
    alias(libs.plugins.notioncal.android.library)
    alias(libs.plugins.notioncal.android.compose)
    alias(libs.plugins.notioncal.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ysdoing.notioncal.feature.month"
}

// 화면 기준 이미지는 저장소에 두고 verifyRoborazziDebug로 비교한다.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    // 화면 모듈은 :core:data(Room)만 읽는다. :core:notion·:core:sync 에 의존하지 않는다(PRD §7).
    implementation(project(":core:data"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.compose.material.icons.core)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    debugImplementation(libs.compose.ui.test.manifest)
}
