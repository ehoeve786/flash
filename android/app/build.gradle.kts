plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ca.hoeve.flash"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.hoeve.flash"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Ship the same Firehose programmer the web flasher uses.
    sourceSets["main"].assets.srcDir("../../src/QDL")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
}
