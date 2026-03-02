plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.thinkoff.clawwatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thinkoff.clawwatch"
        minSdk = 30  // Wear OS 3 = API 30
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // Package the nullclaw binary from assets
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    // Wear OS
    implementation(libs.wear)
    implementation(libs.wear.input)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)

    // Material (FAB)
    implementation("com.google.android.material:material:1.12.0")

    // AppCompat
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Vosk STT (offline speech recognition)
    implementation(libs.vosk.android)
}
