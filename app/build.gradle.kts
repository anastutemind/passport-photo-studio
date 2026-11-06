import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The upload key lives outside the repo: keystore.properties (gitignored) or the
// PASSPORT_KEYSTORE_* environment variables on a build machine.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val storePath = secret("storeFile", "PASSPORT_KEYSTORE_FILE")
val hasKey = storePath != null && file(storePath).exists()

android {
    namespace = "com.aps.passportphoto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aps.passportphoto"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Only ship arm64 native libs (ONNX Runtime) - covers essentially every phone on minSdk 29
        // and keeps the APK small instead of bundling x86/armeabi variants.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (hasKey) {
            create("upload") {
                storeFile = file(storePath!!)
                storePassword = secret("storePassword", "PASSPORT_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "PASSPORT_KEY_ALIAS")
                keyPassword = secret("keyPassword", "PASSPORT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKey) signingConfig = signingConfigs.getByName("upload")
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
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin")
        // Google Play requires 16 KB page-size support (deadline Feb 2027): keep native
        // libraries uncompressed and page-aligned in the bundle.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.print:print:1.0.0")

    // On-device ML (face alignment + background removal)
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // On-device alpha matting (MODNet via ONNX Runtime); model downloaded on first use
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
