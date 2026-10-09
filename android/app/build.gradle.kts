import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.tuscani712.lanyard"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.tuscani712.lanyard"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.1.0-beta.15"
    }

    // The release key is never in the repository: point LANYARD_KEYSTORE_PROPS at a
    // properties file (storeFile, storePassword, keyAlias, keyPassword). Without it
    // the release build is left unsigned.
    val keystoreProps = System.getenv("LANYARD_KEYSTORE_PROPS")
        ?.let { file(it) }?.takeIf { it.isFile }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs merged resources to inflate the Compose test host.
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main") {
            // THIRD_PARTY.md is copied here by copyLicenses below.
            assets.srcDir(layout.buildDirectory.dir("generated/licenses"))
        }
    }

    packaging {
        resources {
            // BouncyCastle ships multi-release jars whose per-version OSGI
            // manifests collide when merged.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.gson)
    implementation(libs.zxing.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

// Bundle the repository's third-party notices as an asset for the About screen.
val copyLicenses by tasks.registering(Copy::class) {
    from(rootProject.file("../THIRD_PARTY.md"))
    into(layout.buildDirectory.dir("generated/licenses"))
}

tasks.named("preBuild") {
    dependsOn(copyLicenses)
}
