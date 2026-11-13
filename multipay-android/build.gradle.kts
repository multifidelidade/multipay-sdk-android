plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "com.multifidelidade.multipay.android"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":multipay-core"))
    implementation("androidx.browser:browser:1.8.0")
}

afterEvaluate {
    publishing {
        publications { create<MavenPublication>("release") { from(components["release"]); artifactId = "multipay-android"; groupId = "app.multifidelidade"; version = "0.1.0" } }
    }
}
