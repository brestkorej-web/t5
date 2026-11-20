plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.brestkorej.t5"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.brestkorej.t5"
        minSdk = 24
        targetSdk = 34
        versionCode = run
        versionName = "1.0.$run"
    }
    signingConfigs {
        create("t5") {
            storeFile = file("../t5.keystore")
            storePassword = "t5t5t5"
            keyAlias = "t5"
            keyPassword = "t5t5t5"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("t5") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
