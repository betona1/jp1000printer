plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "com.betona.printdriver"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file("release-keystore.jks")
            storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String? ?: "libroprintdriver"
            keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String? ?: "release"
            keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String? ?: "libroprintdriver"
        }
    }

    defaultConfig {
        applicationId = "com.betona.printdriver"
        minSdk = 24
        targetSdk = 26
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "device"
    productFlavors {
        create("standard") {
            dimension = "device"
            // JY-P1000 (Android 11) — default package
        }
        create("a40") {
            dimension = "device"
            // A40i (Android 7) — com.android prefix to bypass BackgroundManagerService whitelist
            applicationId = "com.android.printdriver"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint {
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.2"
    }
}

dependencies {

    implementation(project(":jyndklib"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation("androidx.compose.material:material-icons-extended")

    // NanoHTTPD — embedded web server for management interface
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}