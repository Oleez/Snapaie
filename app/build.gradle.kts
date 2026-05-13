plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

@Suppress("DEPRECATION")
val shaE2B = (project.findProperty("snapaie.model.sha256.e2b") as String?)?.trim().orEmpty()
@Suppress("DEPRECATION")
val shaE4B = (project.findProperty("snapaie.model.sha256.e4b") as String?)?.trim().orEmpty()
@Suppress("DEPRECATION")
val billingSub =
    (project.findProperty("snapaie.billing.subscription.id") as String?) ?: "snapaie_pro_monthly"
@Suppress("DEPRECATION")
val billingLifetime =
    (project.findProperty("snapaie.billing.lifetime.id") as String?) ?: "snapaie_pro_lifetime"
@Suppress("DEPRECATION")
val admobAppId =
    (project.findProperty("snapaie.admob.application.id") as String?)
        ?: "ca-app-pub-3940256099942544~3347511713"
@Suppress("DEPRECATION")
val admobBannerUnit =
    (project.findProperty("snapaie.admob.banner.unit.id") as String?)
        ?: "ca-app-pub-3940256099942544/6300978111"

android {
    namespace = "com.snapaie.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snapaie.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables {
            useSupportLibrary = true
        }
        manifestPlaceholders["admobApplicationId"] = admobAppId
        buildConfigField("String", "EXPECTED_MODEL_SHA256_E2B", "\"$shaE2B\"")
        buildConfigField("String", "EXPECTED_MODEL_SHA256_E4B", "\"$shaE4B\"")
        buildConfigField("String", "BILLING_PRODUCT_SUBSCRIPTION", "\"$billingSub\"")
        buildConfigField("String", "BILLING_PRODUCT_LIFETIME", "\"$billingLifetime\"")
        buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID", "\"$admobBannerUnit\"")
    }

    buildTypes {
        release {
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        checkDependencies = true
        abortOnError = false
        checkReleaseBuilds = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    implementation("com.google.accompanist:accompanist-permissions:0.37.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    implementation("com.android.billingclient:billing-ktx:7.1.1")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    jvmToolchain(17)
}
