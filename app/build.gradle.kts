import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// manifest.json is what Karoo reads to install and update the app, so it is the one place the
// version is set: bump latestVersion and latestVersionCode there and the build follows.
@Suppress("UNCHECKED_CAST")
val karooManifest = JsonSlurper().parse(file("manifest.json")) as Map<String, Any>
val manifestPackage = karooManifest["packageName"] as String
val manifestVersionName = karooManifest["latestVersion"] as String
val manifestVersionCode = karooManifest["latestVersionCode"] as Int
// manifest.json is published next to the APK it points at
val manifestUrl = (karooManifest["latestApkUrl"] as String).substringBeforeLast('/') + "/manifest.json"

// Release signing comes from the environment only; see docs/submission.md.
val signingEnv = listOf(
    "INTERVALSYNC_KEYSTORE_FILE",
    "INTERVALSYNC_KEYSTORE_PASSWORD",
    "INTERVALSYNC_KEY_ALIAS",
    "INTERVALSYNC_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
val missingSigningEnv = signingEnv.filterValues { it == null }.keys

android {
    namespace = "icu.intervalsync.karoo"
    compileSdk = 34

    defaultConfig {
        applicationId = "icu.intervalsync.karoo"
        minSdk = 23
        targetSdk = 34
        versionCode = manifestVersionCode
        versionName = manifestVersionName
        manifestPlaceholders["karooManifestUrl"] = manifestUrl

        // -Pintervalsync.apiBaseUrl=http://<host>:8787 points a build at tools/mock_server.py
        val apiBaseUrl = providers.gradleProperty("intervalsync.apiBaseUrl").getOrElse("https://intervalsync.icu")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    signingConfigs {
        if (missingSigningEnv.isEmpty()) {
            create("release") {
                storeFile = file(signingEnv.getValue("INTERVALSYNC_KEYSTORE_FILE")!!)
                storePassword = signingEnv.getValue("INTERVALSYNC_KEYSTORE_PASSWORD")
                keyAlias = signingEnv.getValue("INTERVALSYNC_KEY_ALIAS")
                keyPassword = signingEnv.getValue("INTERVALSYNC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Left unset without the signing environment; checkReleaseSigning then stops the build
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
}

val checkReleaseSigning by tasks.registering {
    description = "Fails a release build that has no signing key, rather than leaving it unsigned or debug-signed"
    val missing = missingSigningEnv
    val keystore = signingEnv["INTERVALSYNC_KEYSTORE_FILE"]?.let { file(it) }
    doLast {
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured: set ${missing.joinToString()}. " +
                    "Release builds are never signed with the debug key; see docs/submission.md.",
            )
        }
        if (keystore?.isFile != true) {
            throw GradleException("Release signing: INTERVALSYNC_KEYSTORE_FILE points at $keystore, which is not a file.")
        }
    }
}

val checkKarooManifest by tasks.registering {
    description = "Checks app/manifest.json against the build it describes"
    val packageName = manifestPackage
    val applicationId = android.defaultConfig.applicationId
    val versionCode = manifestVersionCode
    doLast {
        if (packageName != applicationId) {
            throw GradleException("manifest.json packageName is $packageName but the applicationId is $applicationId.")
        }
        if (versionCode < 1) throw GradleException("manifest.json latestVersionCode must be 1 or more.")
    }
}

tasks.named("preBuild") { dependsOn(checkKarooManifest) }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }

dependencies {
    implementation(project(":core"))

    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material)
}
