// Gradle root for the Android wrapper only (D-15). The ESPHome side of the
// repo does not use Gradle; this file exists so CI can run `gradle :app:...`.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "van-supervisor"
include(":app")
