pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        // Maven Central publishing, same shape as the sibling slang-wasm-endive project.
        id("com.vanniktech.maven.publish") version "0.37.0"
    }
}

plugins {
    // Auto-provisions the requested JDK toolchain via the foojay Disco API when not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "slang-java"

include("slang")
include("natives")
include("bindgen")
