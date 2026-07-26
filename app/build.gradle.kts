import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningEnvironment = mapOf(
    "storeFile" to "ANDROID_RELEASE_STORE_FILE",
    "storePassword" to "ANDROID_RELEASE_STORE_PASSWORD",
    "keyAlias" to "ANDROID_RELEASE_KEY_ALIAS",
    "keyPassword" to "ANDROID_RELEASE_KEY_PASSWORD",
)
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningValues = releaseSigningKeys.associateWith { key ->
    providers.environmentVariable(releaseSigningEnvironment.getValue(key)).orNull
        ?: releaseSigningProperties.getProperty(key)
}
val releaseSigningRequested = releaseSigningPropertiesFile.isFile ||
    releaseSigningValues.values.any { !it.isNullOrBlank() }
val missingReleaseSigningKeys = releaseSigningKeys.filter {
    releaseSigningValues.getValue(it).isNullOrBlank()
}
require(!releaseSigningRequested || missingReleaseSigningKeys.isEmpty()) {
    "Release signing is partially configured; missing: ${missingReleaseSigningKeys.joinToString()}"
}
val releaseSigningConfigured = releaseSigningRequested && missingReleaseSigningKeys.isEmpty()

android {
    namespace = "com.screentranslation.app"
    // androidx.core 1.19.0+ requires compiling against API 37 or newer.
    // targetSdk stays at 36: this changes what APIs are compilable, not runtime behaviour.
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.screentranslation.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Package only the Xiaomi 15 Pro target ABI.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(
                    checkNotNull(releaseSigningValues.getValue("storeFile")),
                )
                storePassword = releaseSigningValues.getValue("storePassword")
                keyAlias = releaseSigningValues.getValue("keyAlias")
                keyPassword = releaseSigningValues.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")

    // Bundled OCR models: available immediately without Google Play model delivery.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    // Translation models are downloaded on demand, then run on device.
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
}

tasks.register("printVersionInfo") {
    group = "help"
    description = "Prints machine-readable Android version values for release automation."
    doLast {
        println("versionName=${android.defaultConfig.versionName}")
        println("versionCode=${android.defaultConfig.versionCode}")
    }
}
