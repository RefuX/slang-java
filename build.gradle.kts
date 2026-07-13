// Root build: puts the publishing plugin on the build classpath so the natives subprojects
// (configured centrally in natives/build.gradle.kts) can apply and configure it by type.
plugins {
    id("com.vanniktech.maven.publish") apply false
}
