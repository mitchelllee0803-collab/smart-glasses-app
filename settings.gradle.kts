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
        // Tesseract4Android (used by the reused offline engine) is published on JitPack only.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.adaptech-cz.*") }
        }
    }
}

rootProject.name = "SmartGlasses"
include(":app", ":engine", ":llm")
