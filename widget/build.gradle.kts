plugins {
    alias(libs.plugins.notioncal.android.library)
    alias(libs.plugins.notioncal.android.compose)
    alias(libs.plugins.notioncal.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ysdoing.notioncal.widget"
}

// 위젯 기준 이미지는 저장소에 두고 verifyRoborazziDebug로 비교한다.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.glance.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.rule)
}

// PRD §7-5: 위젯에서 네트워크를 못 부르게 :core:notion·:core:sync 의존을 빌드 단계에서 막는다.
val forbidden = setOf(":core:notion", ":core:sync")
configurations.configureEach {
    withDependencies {
        filterIsInstance<ProjectDependency>().firstOrNull { it.path in forbidden }?.let {
            throw GradleException(":widget 은 ${it.path} 에 의존할 수 없습니다 (PRD §7-5)")
        }
    }
}
