import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

// PRD §4: compileSdk/targetSdk 37, minSdk 31
internal const val COMPILE_SDK = 37
internal const val TARGET_SDK = 37
internal const val MIN_SDK = 31

internal fun CommonExtension.configureAndroidCommon() {
    compileSdk = COMPILE_SDK
    defaultConfig.minSdk = MIN_SDK
    compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    compileOptions.targetCompatibility = JavaVersion.VERSION_17
    testOptions.unitTests.isIncludeAndroidResources = true
}

// CommonExtension에는 lint가 없어서 앱·라이브러리 플러그인에서 따로 호출한다.
internal fun Lint.configureLint() {
    abortOnError = true
}

// Hilt가 테스트 소스를 생성해 테스트가 아직 없는 모듈에서도 "테스트 미발견" 실패가 난다. Phase 0 골격 단계용.
internal fun Project.allowModulesWithoutTests() {
    tasks.withType<Test>().configureEach { failOnNoDiscoveredTests.set(false) }
}
