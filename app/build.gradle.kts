import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load signing properties from local.properties (not checked into git)
val localProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.vesper.flipper"
    compileSdk = 35
    // NOTE: compileSdk/targetSdk intentionally stay at 35. SDK 36 is blocked
    // until Android SDK Platform 36 is installable in this offline environment.

    defaultConfig {
        applicationId = "com.vesper.flipper"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release signing — only activated when keystore properties are set.
    // To enable: add these to local.properties (never commit this file):
    //   RELEASE_STORE_FILE=../keystore/vesper-release.jks
    //   RELEASE_STORE_PASSWORD=your_store_password
    //   RELEASE_KEY_ALIAS=vesper
    //   RELEASE_KEY_PASSWORD=your_key_password
    val hasSigningConfig = localProps.getProperty("RELEASE_STORE_FILE") != null

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("RELEASE_STORE_FILE"))
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    } else {
        // Fail-open: release assemblies still succeed, but an unsigned release
        // APK must never be shipped silently.
        project.logger.warn(
            "Vesper: release signing is not configured; release builds will be UNSIGNED. " +
                "Set RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and " +
                "RELEASE_KEY_PASSWORD in local.properties to sign releases.",
        )
    }

    buildTypes {
        debug {
            // Debug builds: no minification, fast iteration.
            // Same applicationId as release on purpose: installing a new debug
            // build upgrades the existing app (settings, key, chat history)
            // instead of creating a second, empty copy.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    testOptions {
        unitTests {
            // JVM tests exercise Android APIs; return safe defaults instead of
            // throwing "not mocked" at runtime.
            isReturnDefaultValues = true
        }
    }

    lint {
        // Lint runs in CI but does not fail the build on existing lint debt.
        // The report is uploaded as an artifact; flip abortOnError to true once
        // the checked-in baseline is clean.
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    packaging {
        resources {
            // Avoid duplicate license/notice files contributed by multiple
            // dependencies when merging APK resources.
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/DEPENDENCIES",
            )
        }
    }

    sourceSets {
        // Room exports schema JSON files here for migration review/testing.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose BOM & UI
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Coil (image loading + video frame thumbnails)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Accompanist

    // USB Serial
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")

    // Security / Encrypted SharedPrefs
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Protobuf (Flipper device protocol)
    implementation("com.google.protobuf:protobuf-java:3.25.5")

    // Diff utils
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

    // Unit tests (JVM)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}
