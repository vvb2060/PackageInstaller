pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.11.1"
        id("com.android.library") version "8.11.1"
        id("org.jetbrains.kotlin.android") version "2.2.0"
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.settings") version "8.11.1"
}

android {
    compileSdk = 36
    minSdk = 28
    targetSdk = 36
    buildToolsVersion = "36.0.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")

include(":app", ":stub")
rootProject.name = "PackageInstaller"
