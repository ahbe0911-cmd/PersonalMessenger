pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PersonalMessenger"
include(":app")

include(":peyvanik-preview")
project(":peyvanik-preview").projectDir = file("peyvanik-v2/android")
