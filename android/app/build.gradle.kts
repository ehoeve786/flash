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

    // Permanent key from the SIGNING_KEY / SIGNING_PASSWORD CI secrets.
    signingConfigs {
        System.getenv("SIGNING_KEYSTORE")?.let { ks ->
            create("release") {
                storeFile = file(ks)
                storePassword = checkNotNull(System.getenv("SIGNING_PASSWORD")) { "SIGNING_PASSWORD secret not set" }
                keyAlias = "flash"
                keyPassword = storePassword // PKCS12: key and store share one password
            }
        }
    }

    buildTypes {
        // Ship release (non-debuggable: a debug APK lets `adb run-as` read the app's private ADB key).
        release { signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug") }
    }

    lint { checkReleaseBuilds = false }

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
