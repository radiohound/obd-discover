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

        // THE BUILD MARKER, DERIVED RATHER THAN TYPED.
        //
        // It used to be a hand-edited constant, and it did exactly what a hand-edited
        // constant does: it went stale. A whole day of builds shipped announcing the
        // commit before them, which made "did the new build install?" -- the only
        // question this field exists to answer -- unanswerable, and answerable WRONGLY,
        // which is worse than having no marker at all.
        //
        // Derived from the commit, so it cannot disagree with the code. Falls back to the
        // version name where git is unavailable, e.g. a source download.
        // Read at configure time, so a build made BEFORE its commit lands would otherwise
        // announce the previous commit while carrying newer code -- which is the same
        // wrong answer the hand-edited constant gave, arrived at from the other side.
        // "+dirty" says the build contains changes no commit describes.
        val head = providers.exec {
            commandLine("git", "log", "-1", "--format=%cd-%h", "--date=format:%Y-%m-%d")
        }.standardOutput.asText.map { it.trim() }.orElse("").get()
        val dirty = providers.exec {
            commandLine("git", "status", "--porcelain")
        }.standardOutput.asText.map { it.trim() }.orElse("").get().isNotEmpty()
        val tag = (head.ifEmpty { "v$versionName" }) + if (dirty) "+dirty" else ""
        buildConfigField("String", "BUILD_TAG", "\"$tag\"")
    }

    buildFeatures { compose = true; buildConfig = true }

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

// The third-party notices the app shows under "sources & licence", generated from the
// single copy at the repo root.
//
// THERE USED TO BE TWO, AND THEY HAD ALREADY DRIFTED. LICENSE carried a copy and
// assets/ATTRIBUTION.txt carried another; they were 15% similar, and only one of them
// mentioned SAE J1979. The notices that must travel with the APK are exactly the ones
// nobody re-reads, so the fix is to make disagreement impossible rather than to check.
//
// LICENSE is now pure MIT, which also settles GitHub reporting the repo as NOASSERTION:
// its detector matches the whole file, and 65 lines of appended notices put it under the
// threshold, so an automated reader concluded there was no licence at all.
val notices = tasks.register<Copy>("copyNotices") {
    from(rootProject.file("THIRD-PARTY-NOTICES.md"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "ATTRIBUTION.txt" }
}

// Compiles vehicles/**/*.json into one shipped asset.
//
// A BUILD STEP RATHER THAN A COMMITTED FILE, for two reasons. One file per vehicle means
// two people adding two cars touch disjoint paths and their pull requests never conflict;
// a shared blob would conflict on every PR. And the merge is where a record is validated,
// so a file carrying more than VIN positions 1-8 -- positions 9-17 include the serial --
// fails the build instead of reaching an APK.
//
// The asset is the identifying subset, not the full map: 210 bytes per vehicle against
// 14 KB for the whole record, so the full maps stay in the repo for humans to read.
val mergeVehicles = tasks.register<Exec>("mergeVehicles") {
    val out = layout.projectDirectory.file("src/main/assets/vin_patterns.json")
    inputs.dir(rootProject.file("vehicles"))
    inputs.file(rootProject.file("tools/merge_vehicles.py"))
    outputs.file(out)
    commandLine("python3", rootProject.file("tools/merge_vehicles.py").absolutePath,
                out.asFile.absolutePath)
}

// Every variant's asset merge waits on it, so a plain `assembleDebug` regenerates it.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(mergeVehicles, notices) }

// ReadmeClaimsTest reads these at runtime, so Gradle has to know they are test inputs.
// Without this the task stays UP-TO-DATE when only the README changes -- the test passes on
// a stale result and the whole point of it is lost. Verified by breaking a number on purpose
// and watching it NOT fail.
tasks.withType<Test>().configureEach {
    dependsOn(mergeVehicles, notices)
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
