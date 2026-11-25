plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.emekli.robot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.emekli.robot"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Kuyruk (kalıcı, SQLite/WAL)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Gönderim ve watchdog
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Sunucu isteği
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Konum
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Debug: bellek sızıntısı tespiti (7/24 çalışma için)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
