import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Ayar ekranı şifresi repoda tutulmaz: gitignore'lu local.properties'ten okunur,
// uygulamaya yalnızca tuzlu SHA-256 özeti girer.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val settingsPin: String = localProps.getProperty("SETTINGS_PIN")
    ?: throw GradleException("local.properties içinde SETTINGS_PIN tanımlı değil")
val pinSalt = "emekli-robot-v1:"
val pinHash: String = MessageDigest.getInstance("SHA-256")
    .digest((pinSalt + settingsPin).toByteArray())
    .joinToString("") { "%02x".format(it) }

android {
    // APK dosya adı: emekli.apk (app/build/outputs/apk/debug/emekli.apk)
    applicationVariants.all {
        outputs.all { (this as BaseVariantOutputImpl).outputFileName = "emekli.apk" }
    }
    namespace = "com.emekli.robot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.emekli.robot"
        minSdk = 29
        targetSdk = 34
        versionCode = 105 // major*100 + minor*10 + patch (1.0.0 = 100)
        versionName = "1.0.5"

        buildConfigField("String", "SETTINGS_PIN_SALT", "\"$pinSalt\"")
        buildConfigField("String", "SETTINGS_PIN_HASH", "\"$pinHash\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation("com.google.android.material:material:1.12.0")

    // Kuyruk (kalıcı, SQLite/WAL)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Gönderim ve watchdog
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Sunucu isteği
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // API anahtarı için şifreli depo
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Konum

    // Arayüz
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Debug: bellek sızıntısı tespiti (7/24 çalışma için)

    // Birim testleri (saf mantık: hareket, termal, zaman, gönderim gövdesi)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
