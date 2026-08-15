plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.notiforwarder.mili"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.notiforwarder.mili"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // اطلاعات بله (Bale)
        buildConfigField("String", "BALE_BOT_TOKEN", "\"${project.findProperty("baleBotToken") ?: ""}\"")
        buildConfigField("String", "BALE_USER_ID", "\"${project.findProperty("baleUserId") ?: ""}\"")
        buildConfigField("String", "BALE_CHANNEL_ID", "\"${project.findProperty("baleChannelId") ?: ""}\"")

        // اطلاعات روبیکا (Rubika)
        buildConfigField("String", "RUBIKA_BOT_TOKEN", "\"${project.findProperty("rubikaBotToken") ?: ""}\"")
        buildConfigField("String", "RUBIKA_USER_ID", "\"${project.findProperty("rubikaUserId") ?: ""}\"")
        buildConfigField("String", "RUBIKA_CHANNEL_ID", "\"${project.findProperty("rubikaChannelId") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    // برای ارسال HTTP نیازی به کتاب‌خانه‌ی اضافی نیست
}
