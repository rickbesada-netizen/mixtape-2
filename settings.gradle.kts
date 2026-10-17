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
        // NewPipeExtractor (YouTube + SoundCloud extraction) and its nanojson dependency
        maven("https://jitpack.io")
    }
}

rootProject.name = "Mixtape"
include(":app")
