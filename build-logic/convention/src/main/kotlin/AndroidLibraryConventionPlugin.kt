import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        allowModulesWithoutTests()
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroidCommon()
            lint.configureLint()
            // 라이브러리 lint도 앱과 같은 타깃으로 검사한다.
            lint.targetSdk = TARGET_SDK
            testOptions.targetSdk = TARGET_SDK
        }
    }
}
