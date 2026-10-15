plugins {
    alias(libs.plugins.notioncal.android.library)
    alias(libs.plugins.notioncal.android.compose)
}

android {
    namespace = "com.ysdoing.notioncal.core.designsystem"
}

dependencies {
    // 화면 틀 색 값(DesignTokens)만 쓴다. 네트워크·DB는 쓰지 않는 UI 전용 모듈(CLAUDE.md 모듈 규칙).
    implementation(project(":core:data"))
}
