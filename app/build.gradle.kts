import java.util.Properties

plugins {
    alias(libs.plugins.notioncal.android.application)
    alias(libs.plugins.notioncal.android.compose)
    alias(libs.plugins.notioncal.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

// PF-04: Sentry DSN은 저장소에 넣지 않는다. local.properties의 sentry.dsn이 없으면 수집을 끈 채로 빌드된다.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val sentryDsn: String = localProperties.getProperty("sentry.dsn").orEmpty().trim()

android {
    namespace = "com.ysdoing.notioncal"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.ysdoing.notioncal"
        versionCode = 6
        versionName = "0.5.0"
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // 정식 서명 키는 아직 없다(PRD §13). 개인 설치·성능 측정(benchmarkRelease가 이 설정을 이어받음)용으로 debug 키를 쓴다.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:sync"))
    implementation(project(":feature:dday"))
    implementation(project(":feature:diagnostics"))
    implementation(project(":feature:habit"))
    implementation(project(":feature:ledger"))
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
    implementation(libs.sentry.android.core)
    implementation(libs.profileinstaller) // PF-01: Baseline Profile 설치
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit4)
    // EX-01 알림 예약·발화 테스트(Robolectric AlarmManager·NotificationManager, Room·DataStore).
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.datastore.preferences)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
