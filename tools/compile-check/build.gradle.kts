plugins { id("org.jetbrains.kotlin.jvm") }

kotlin {
    sourceSets["main"].kotlin.srcDirs("../../core/src/main/kotlin", "../../app/src/main/java")
    compilerOptions { allWarningsAsErrors.set(false) }
}

dependencies {
    compileOnly(files(System.getenv("ANDROID_ALL_JAR") ?: error("set ANDROID_ALL_JAR")))
}
