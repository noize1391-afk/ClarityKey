plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.claritykey.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claritykey.keyboard"
        minSdk = 28
        targetSdk = 35
        // Keep this monotonically above the previous 5.4.0-beta.3 build so the
        // renamed public beta can be installed as an update on test devices.
        versionCode = 54005
        versionName = "0.5.3-beta"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("CLARITYKEY_KEYSTORE_PATH")
            val storePass = System.getenv("CLARITYKEY_KEYSTORE_PASSWORD")
            val alias = System.getenv("CLARITYKEY_KEY_ALIAS")
            val aliasPass = System.getenv("CLARITYKEY_KEY_PASSWORD")
            val ksFile = ksPath?.let(::file)
            if (ksFile?.exists() == true && !storePass.isNullOrBlank() &&
                !alias.isNullOrBlank() && !aliasPass.isNullOrBlank()
            ) {
                storeFile = ksFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = aliasPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val relCfg = signingConfigs.findByName("release")
            if (relCfg?.storeFile?.exists() == true) {
                signingConfig = relCfg
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.autofill:autofill:1.1.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
