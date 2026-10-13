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
include(":core:data")
include(":core:notion")
include(":core:sync")
include(":feature:month")
include(":feature:quicksheet")
include(":widget")
