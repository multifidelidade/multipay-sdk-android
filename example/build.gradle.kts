plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "app.multifidelidade.multipaydemo"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.multifidelidade.multipaydemo"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":multipay-android"))
}
