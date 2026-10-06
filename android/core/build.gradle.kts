plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.conscrypt.uber)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bctls)
    implementation(libs.gson)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.zxing.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The UI API rejects state-changing requests without an Origin header, and
    // java.net blocks setting Origin unless this is enabled.
    jvmArgs("-Dsun.net.http.allowRestrictedHeaders=true")
    // The spike test needs the freshly built Go binary.
    environment("LANYARD_BIN", System.getenv("LANYARD_BIN") ?: "")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
