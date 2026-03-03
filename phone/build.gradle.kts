plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.thinkoff.clawwatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thinkoff.clawwatch"   // same as watch — Play Store pairing
        minSdk = 26                                  // Android 8.0+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    // Wearable Data Layer — sync config to watch
    implementation(libs.play.services.wearable)
    // Encrypted key storage
    implementation(libs.security.crypto)
    // Navigation
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    // Coroutines Task extension for Wearable .await()
    implementation(libs.kotlinx.coroutines.play.services)
}
