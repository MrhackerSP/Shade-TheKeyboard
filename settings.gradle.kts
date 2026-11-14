pluginManagement {
    repositories {
        maven("https://repo1.maven.org/maven2")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://repo1.maven.org/maven2")
        google()
        mavenCentral()
    }
}
rootProject.name = "Shade"
include(":app")
