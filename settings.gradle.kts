rootProject.name = "minecraft-link"

pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the Java 8 toolchain (Azul Zulu, which has macOS arm64 builds) when none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The protocol library lives in its own repository next to this one; Gradle builds it from source
// and the mod jar bundles it (see build.gradle.kts).
// -PlinkJavaDir=<path> points at another checkout than ../link-java (for example a clean one while ../link-java has uncommitted work).
includeBuild(providers.gradleProperty("linkJavaDir").getOrElse("../link-java")) { name = "link-java" } // build.gradle.kts asks for it by this name
