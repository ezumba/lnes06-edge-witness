plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    signingConfigs {
        create("release") {
            storeFile = file("C:/Users/ezumb/exergynet-release.jks")
            storePassword = System.getenv("EXERGYNET_STORE_PASS") ?: ""
            keyAlias = "exergynet"
            keyPassword = System.getenv("EXERGYNET_KEY_PASS") ?: ""
        }
    }

    namespace = "com.exergynet.myapplication"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.exergynet.myapplication"
        minSdk = 29
        targetSdk = 36
        versionCode = 25
        versionName = "1.6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // (B) Wire the release signingConfig so `gradlew assembleRelease` can
            // produce a *signed* APK from the CLI. Requires the keystore passwords
            // in the environment: EXERGYNET_STORE_PASS and EXERGYNET_KEY_PASS.
            // Falls back gracefully: if the store password env var is unset, leave
            // the build unsigned rather than failing configuration (lets debug
            // builds and CI lint run without secrets present).
            signingConfig = if (System.getenv("EXERGYNET_STORE_PASS").isNullOrEmpty()) {
                null
            } else {
                signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    val room_version = "2.8.4"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // ML Kit on-device barcode/QR scanner (Google Code Scanner — no custom camera UI)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // ZXing core — offline QR ENCODING (node-id → PNG); works with no network
    implementation("com.google.zxing:core:3.5.3")

    // LNES-12 Sovereign WebSocket Relay (GLOBAL call fallback). OkHttp WebSocket —
    // no WebRTC, no Firebase. okio ships transitively with okhttp 4.x.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}