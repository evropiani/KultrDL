import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    // FTP/FTPS and SFTP; Bouncy Castle gives JSch the X25519 and Ed25519 that Android's JCE lacks.
    implementation(libs.commons.net)
    implementation(libs.jsch)
    implementation(libs.bouncycastle)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.ftpserver.core)
    // Lets the test SSH server use Ed25519 keys.
    testImplementation(libs.eddsa)
}

tasks.test {
    useJUnit()
    // The test servers write files named "Björk"; the JVM needs a UTF-8 locale for that.
    environment("LC_ALL", "C.UTF-8")
    // LiveSourcesTest runs only with LIVE_SOURCES=1; make the switch an input so it isn't skipped as up to date.
    inputs.property("liveSources", System.getenv("LIVE_SOURCES") ?: "")
    if (System.getenv("LIVE_SOURCES") == "1") testLogging { showStandardStreams = true }
}
