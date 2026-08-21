pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle provision the Java 17 toolchain itself when the JDK running the build is a
// different version (Android Studio ships its own JBR). Without this, a sync fails with
// "Cannot find a Java installation ... matching: {languageVersion=17}".
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "snapaie"
include(":app")
