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
        // CI run number, so each build installs as an update over the last.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    // Optional permanent key from CI secrets; otherwise the (CI-cached) debug key is used.
    System.getenv("SIGNING_KEYSTORE")?.let { ks ->
        signingConfigs.getByName("debug") {
            storeFile = file(ks)
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = System.getenv("SIGNING_ALIAS") ?: "flash"
            keyPassword = storePassword
        }
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
