import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mobilepdf.editor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobilepdf.editor"
        minSdk = 24          // Android 7.0: covers practically every low-end phone still in use
        targetSdk = 35
        versionCode = 9
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release key: keystore.properties + keystore/release.jks (both kept out of git; back them up —
    // without them no update can ever be installed over this app). Falls back to the debug key if absent.
    val keyProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // No native code of our own (PdfRenderer is in the OS, PdfBox is pure Java); the one small
            // Compose native lib ships only for the ABIs real phones use. Debug keeps all ABIs for emulators.
            // -Pemulator: also include x86 so the release build can be tried on an emulator
            if (!project.hasProperty("emulator")) ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            isMinifyEnabled = true       // R8: shrinks PdfBox + icons dramatically
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            // Bouncy Castle data files (post-quantum parameter tables etc., ~4 MB): only certificate
            // crypto would use them; password encryption goes through the platform JCE.
            "org/bouncycastle/**",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended") // R8 strips the unused ones
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // PDF editing (merge/split/protect/annotate/compress). Rendering uses the OS PdfRenderer instead.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // OCR: ML Kit text recognition, "unbundled" (the model comes through Google Play services, so the APK
    // grows by ~0.3 MB instead of ~4 MB). Runs on the device; nothing is uploaded.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // CamScanner-style scanning: ML Kit Document Scanner (edge detection, crop, filters, clean-up), also
    // delivered through Play services
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0") // real multi-touch (pinch) in tests
}
