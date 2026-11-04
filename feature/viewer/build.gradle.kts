plugins {
    alias(libs.plugins.notioncal.android.library)
    alias(libs.plugins.notioncal.android.compose)
    alias(libs.plugins.notioncal.hilt)
}

android {
    namespace = "com.ysdoing.notioncal.feature.viewer"
}

dependencies {
    // 뷰어('더 보기' 전체 화면, VR-02)도 :core:data(Room)만 읽는다. 노션은 부르지 않는다(PRD §7). 행을 고치는 시트(ED-01)는 :feature:quicksheet의 것이다.
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:quicksheet"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.compose.material.icons.core)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
