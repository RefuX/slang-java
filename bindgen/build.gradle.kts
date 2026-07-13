plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(
            (findProperty("javaLanguageVersion") as String?)?.toInt() ?: 25)
    }
}

application {
    mainClass = "io.github.refux.slang.bindgen.Codegen"
}

// Codegen reads/writes repo-relative paths (api/slang-api.json, slang/src/main/java).
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
