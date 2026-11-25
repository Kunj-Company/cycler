import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing reads keystore.properties at the project root (git-ignored; see
// keystore.properties.example). Without it, release builds are left unsigned.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.cycler.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cycler.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.1.12"
        // Show the "buy me a coffee" card and About link. Build without it with -PsupportPrompt=false.
        buildConfigField("boolean", "SUPPORT_PROMPT", (project.findProperty("supportPrompt") ?: "true").toString())
        // Only the 64-bit ARM build of the map engine (real phones, Apple-silicon emulators).
        // Remove this line if you need other CPU types.
        ndk { abiFilters += "arm64-v8a" }
    }
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
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("roborazzi.test.record", "true") }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Maps: MapLibre + free OpenStreetMap-based tiles (no API key)
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
    // Health Connect: heart rate and weight written by Samsung Health and other apps
    implementation("androidx.health.connect:connect-client:1.1.0")
    // Guava arrives only transitively (Health Connect); lift it above the vulnerable 31.x line.
    constraints {
        implementation("com.google.guava:guava:33.8.0-android") {
            because("transitive Guava 31.1 has known CVEs (CVE-2023-2976, CVE-2020-8908)")
        }
    }

    // Screenshot tests: render every screen to app/build/shots without a phone.
    // Run with: ./gradlew testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
