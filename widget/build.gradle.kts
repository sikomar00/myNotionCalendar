plugins {
    alias(libs.plugins.notioncal.android.library)
}

android {
    namespace = "com.ysdoing.notioncal.widget"
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    testImplementation(libs.junit4)
    testImplementation(libs.glance.testing)
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
