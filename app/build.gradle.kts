plugins {
    alias(libs.plugins.notioncal.android.application)
    alias(libs.plugins.notioncal.android.compose)
    alias(libs.plugins.notioncal.hilt)
}

android {
    namespace = "com.ysdoing.notioncal"

    defaultConfig {
        applicationId = "com.ysdoing.notioncal"
        versionCode = 2
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:sync"))
    implementation(project(":feature:month"))
    implementation(project(":feature:quicksheet"))
    implementation(project(":widget"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.work.runtime.ktx)
    implementation(libs.compose.material.icons.core)

    testImplementation(libs.junit4)
}
