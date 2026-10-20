// The Android app needs the Android SDK. Set OFFGRID_CORE_ONLY=1 to build and test just the
// mesh core on any machine with a JDK (no Android SDK or Google Maven needed).
val coreOnly = System.getenv("OFFGRID_CORE_ONLY") != null

pluginManagement {
    repositories {
        if (System.getenv("OFFGRID_CORE_ONLY") == null) google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!coreOnly) google()
        mavenCentral()
    }
}
rootProject.name = "OffGridChat"
include(":core")
if (!coreOnly) include(":app")
