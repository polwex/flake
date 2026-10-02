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
    }
}

rootProject.name = "sorchat"

include(":shared", ":server")
// The Nix build of the server (-Psorchat.serverOnly=true) leaves out the app, so it doesn't need the Android SDK.
if (providers.gradleProperty("sorchat.serverOnly").orNull != "true") include(":app")
