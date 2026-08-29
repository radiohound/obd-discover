import java.util.Properties

// Release signing, if configured. keystore.properties is git-ignored and holds the
// password; the keystore itself lives outside the repo entirely.
//
// WHY THIS MATTERS BEYOND TIDINESS. Android accepts an update only when it carries the
// same signature as the install it replaces. Every build so far used this machine's
// debug key, which is per-machine and per-Android-Studio-install -- so a build from any
// other computer would fail to install over it, and the only fix available to a user is
// uninstalling, which deletes the app's external files directory and every capture on
// their phone. That has to be settled before anyone downloads a build, not after.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.redundo.obddiscover"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.redundo.obddiscover"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falls back to unsigned when keystore.properties is absent, rather than
            // silently producing a debug-signed release -- which would install fine and
            // then break every future update.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }
}

// ReadmeClaimsTest reads these at runtime, so Gradle has to know they are test inputs.
// Without this the task stays UP-TO-DATE when only the README changes -- the test passes on
// a stale result and the whole point of it is lost. Verified by breaking a number on purpose
// and watching it NOT fail.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("README.md"))
    inputs.dir(layout.projectDirectory.dir("src/main/assets"))
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android ships org.json as a stub that throws in JVM unit tests; the real one has to be
    // on the test classpath for anything that parses the bundled assets.
    testImplementation("org.json:json:20240303")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
