pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NotionCal"

include(":app")
include(":baselineprofile")
include(":core:data")
include(":core:designsystem")
include(":core:notion")
include(":core:sync")
include(":feature:diagnostics")
include(":feature:habit")
include(":feature:ledger")
include(":feature:month")
include(":feature:quicksheet")
include(":widget")
