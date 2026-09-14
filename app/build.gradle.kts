plugins {
    id("com.android.application")
}

android {
    namespace = "com.seaeast.e108gnss"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.seaeast.e108gnss"
        minSdk = 26
        targetSdk = 33
        versionCode = 9
        versionName = "0.4.2-55002613"
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
