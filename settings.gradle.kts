@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        val agp = "9.4.1"
        id("com.android.application") version agp
        id("com.android.library") version agp
        id("com.android.settings") version agp
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("com.android.settings")
}

android {
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    minSdk {
        version = release(30)
    }
    targetSdk {
        version = release(37)
    }
    buildToolsVersion = "37.0.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")

include(":app", ":stub")
rootProject.name = "PackageInstaller"
