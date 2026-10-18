plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "lofit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "lofit.app"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".test"
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.drawerlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
