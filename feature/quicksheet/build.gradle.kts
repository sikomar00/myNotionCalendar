plugins {
    alias(libs.plugins.notioncal.android.library)
    alias(libs.plugins.notioncal.android.compose)
}

android {
    namespace = "com.ysdoing.notioncal.feature.quicksheet"
}

dependencies {
    implementation(project(":core:data"))

    testImplementation(libs.junit4)
}
