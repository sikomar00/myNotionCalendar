import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        allowModulesWithoutTests()
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            configureAndroidCommon()
            lint.configureLint()
            // 앱 lint 한 번으로 모든 모듈을 검사한다.
            lint.checkDependencies = true
            defaultConfig.targetSdk = TARGET_SDK
        }
    }
}
