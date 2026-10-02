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

include(":shared")
// The Nix packages build from a subset of the repo (only what they need), so include what's there.
for (module in listOf("server", "loadtest")) if (file(module).exists()) include(":$module")
// The Nix builds (-Psorchat.serverOnly=true) leave out the app, so they don't need the Android SDK.
if (providers.gradleProperty("sorchat.serverOnly").orNull != "true") include(":app")
