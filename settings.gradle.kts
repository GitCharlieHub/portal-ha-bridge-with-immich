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
        maven { url = uri("https://repo.eclipse.org/content/repositories/paho-releases/") }
        maven { url = uri("https://jitpack.io") }  // RootEncoder / RTSP-Server
    }
}

rootProject.name = "portal-ha-bridge"
include(":app")
