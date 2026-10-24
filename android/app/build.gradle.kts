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

    // Permanent key from the SIGNING_KEY CI secret (the keystore itself is the secret); else a throwaway debug key.
    System.getenv("SIGNING_KEYSTORE")?.let { ks ->
        signingConfigs.getByName("debug") {
            storeFile = file(ks)
            storePassword = "flash-release"
            keyAlias = "flash"
            keyPassword = "flash-release"
        }
    }

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
