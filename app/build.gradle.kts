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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(project(":jyndklib"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}