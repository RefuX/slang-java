plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.8.0"
}

spotless {
    java {
        // Generated sources keep the generator's deterministic formatting so regeneration
        // never mixes formatter churn into the diff.
        targetExclude(
            "src/main/java/io/github/refux/slang/ffi/gen/**",
            "src/main/java/io/github/refux/slang/*Gen.java")
        palantirJavaFormat("2.96.0")
    }
}

group = "io.github.refux"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        // DESIGN.md §10: JDK 25 (current LTS) baseline — the binding uses nothing newer than
        // the JDK 22 FFM API, so the baseline follows the newest LTS rather than the newest
        // feature release. Override with -PjavaLanguageVersion=26 to try a newer toolchain.
        languageVersion = JavaLanguageVersion.of(
            (findProperty("javaLanguageVersion") as String?)?.toInt() ?: 25)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // FFM restricted methods (library loading, reinterpret) — JEP 472.
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    // Route a Slang library directory to the loader (DESIGN.md §7 resolution order #1):
    //   ./gradlew :slang:test -PslangNativesDir=natives/build/payload/<platform>/lib
    // or SLANG_JAVA_LIBRARY_PATH in the environment (e.g. a local slang build's lib dir).
    val nativesDir = providers.gradleProperty("slangNativesDir")
        .orElse(providers.environmentVariable("SLANG_JAVA_LIBRARY_PATH"))
    if (nativesDir.isPresent) {
        systemProperty(
            "io.github.refux.slang.libraryPath",
            rootProject.layout.projectDirectory.file(nativesDir.get()).asFile.absolutePath)
    }

    // -PslangDebug runs the suite with leak tracing and thread-confinement asserts enabled
    // (DESIGN.md §16 M3), e.g.: ./gradlew :slang:test -PslangDebug -PslangNativesDir=...
    if (providers.gradleProperty("slangDebug").isPresent) {
        systemProperty("io.github.refux.slang.debug", "true")
    }

    // The M0 exit criterion is *printed* build tags — keep test stdout visible in CI logs.
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
