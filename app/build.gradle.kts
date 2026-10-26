import com.cretorix.buildlogic.VerifyAdMobReleaseConfiguration

plugins {
    alias(libs.plugins.android.application)
}

val admobAppId = providers.gradleProperty("ADMOB_APP_ID")
val admobUnlockBannerId = providers.gradleProperty("ADMOB_UNLOCK_BANNER_ID")
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testUnlockBannerId = "ca-app-pub-3940256099942544/9214589741"

android {
    namespace = "com.cretorix.securepasswordmanager"
    buildFeatures {
        buildConfig = true
    }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cretorix.securepasswordmanager"
        minSdk = 31
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"

        manifestPlaceholders["admobAppId"] = testAdmobAppId
        buildConfigField("String", "ADMOB_UNLOCK_BANNER_ID", "\"$testUnlockBannerId\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("String", "ADMOB_UNLOCK_BANNER_ID", "\"$testUnlockBannerId\"")
        }
        release {
            manifestPlaceholders["admobAppId"] = admobAppId.orElse(testAdmobAppId).get()
            buildConfigField(
                "String",
                "ADMOB_UNLOCK_BANNER_ID",
                "\"${admobUnlockBannerId.orElse(testUnlockBannerId).get()}\""
            )
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val verifyReleaseAdMobConfiguration =
    tasks.register<VerifyAdMobReleaseConfiguration>("verifyReleaseAdMobConfiguration") {
        admobAppId.set(providers.gradleProperty("ADMOB_APP_ID").orElse(""))
        admobUnlockBannerId.set(
            providers.gradleProperty("ADMOB_UNLOCK_BANNER_ID").orElse("")
        )
}

tasks.configureEach {
    if (name == "bundleRelease" || name == "assembleRelease" || name == "packageRelease") {
        dependsOn(verifyReleaseAdMobConfiguration)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.mlkit.text)
    implementation(libs.bouncycastle)
    implementation(libs.apache.poi)
    implementation(libs.play.billing)
    implementation(libs.google.mobile.ads)
    implementation(libs.google.ump)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.opencv)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}