pluginManagement {
    repositories {
        google { setUrl("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { setUrl("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
    }
}

rootProject.name = "解锁守护"
include(":app")
