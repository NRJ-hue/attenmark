plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.attendmark.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.attendmark.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            val host = (project.findProperty("emulatorHost") as? String)?.takeIf { it.isNotBlank() } ?: "cloud"
            buildConfigField("String", "EMULATOR_HOST", "\"$host\"")
            val defaultBackend = if (host == "127.0.0.1") "http://127.0.0.1:3000" else if (host == "10.0.2.2") "http://10.0.2.2:3000" else "https://attenmark.onrender.com"
            val backendUrl = (project.findProperty("backendUrl") as? String)?.takeIf { it.isNotBlank() } ?: defaultBackend
            buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "EMULATOR_HOST", "\"\"")
            val backendUrl = (project.findProperty("backendUrl") as? String)?.takeIf { it.isNotBlank() } ?: "https://attenmark.onrender.com"
            buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Allow packaging of TFLite model files
    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit Face Detection (for liveness challenges) & Barcode Scanning (for QR)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.barcode.scanning)

    // TensorFlow Lite (for face embedding/matching)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Gson
    implementation(libs.gson)
    
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.functions)

    // Guava (Required for CameraX ListenableFuture)
    implementation(libs.guava)

    // Location
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
