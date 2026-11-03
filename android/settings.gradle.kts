pluginManagement {
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

        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "DesignPrototypeWorkshopAndroid"
include(":app")

include(":core-imaging")
include(":core-vector")
include(":core-pipeline")
include(":core-export")
