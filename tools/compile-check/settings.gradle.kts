// Type-checks the Android app's Kotlin against a Robolectric android-all jar, for machines
// without the Android SDK (e.g. CI or cloud sandboxes). Not a real Android build.
//   ANDROID_ALL_JAR=/path/android-all-15-robolectric-13954326.jar gradle -p tools/compile-check compileKotlin
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins { id("org.jetbrains.kotlin.jvm") version "2.0.21" }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "compile-check"
