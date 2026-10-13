plugins {
    alias(libs.plugins.notioncal.android.library)
    alias(libs.plugins.notioncal.hilt)
}

android {
    namespace = "com.ysdoing.notioncal.core.sync"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:notion"))
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.work.testing)
}
