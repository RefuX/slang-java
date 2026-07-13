plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.8.0"
    id("com.vanniktech.maven.publish")
}

// group and version come from gradle.properties (single source, shared with the natives
// artifacts). The published artifact is io.github.refux:slang-java (set via
// mavenPublishing.coordinates); keep local jar names in step with it. The Gradle module
// path stays :slang.
base {
    archivesName = "slang-java"
}

// Maven Central publishing (com.vanniktech.maven.publish), tag-driven via
// .github/workflows/release.yml — the same shape as slang-wasm-endive.
// (This block must come after the version assignment: coordinates() captures it eagerly.)
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.refux", "slang-java", project.version.toString())

    pom {
        name = "slang-java"
        description = "Java bindings for the Slang shader compiler via the FFM API: compile " +
            "Slang to SPIR-V, HLSL, GLSL, WGSL, or Metal, use the full reflection API, and " +
            "serve imports from Java file systems - pure Java over the official native " +
            "binaries, no JNI."
        inceptionYear = "2026"
        url = "https://github.com/RefuX/slang-java"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "RefuX"
                name = "James Roome"
                url = "https://github.com/RefuX"
            }
        }
        scm {
            url = "https://github.com/RefuX/slang-java"
            connection = "scm:git:git://github.com/RefuX/slang-java.git"
            developerConnection = "scm:git:ssh://git@github.com/RefuX/slang-java.git"
        }
    }
}

spotless {
    java {
        // Generated sources keep the generator's deterministic formatting so regeneration
        // never mixes formatter churn into the diff.
        targetExclude(
            "src/main/java/io/github/refux/slang/ffi/gen/**",
            "src/main/java/io/github/refux/slang/gen/**")
        palantirJavaFormat("2.96.0")
    }
}

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

// Published javadoc focuses on the idiomatic API (io.github.refux.slang) and the loader's
// configuration surface. The generated ffi.gen / gen layers and the low-level ffi wrappers are
// documented escape hatches (see DESIGN.md), not semver-stable, so they are excluded to keep the
// user-facing docs clean and warning-free.
tasks.javadoc {
    exclude("io/github/refux/slang/ffi/**")
    exclude("io/github/refux/slang/gen/**")
    (options as StandardJavadocDocletOptions).apply {
        docTitle = "slang-java $version"
        windowTitle = "slang-java $version"
        encoding = "UTF-8"
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("quiet", true)
        links("https://docs.oracle.com/en/java/javase/25/docs/api/")
    }
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
