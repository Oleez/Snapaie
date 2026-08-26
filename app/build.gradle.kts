plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

@Suppress("DEPRECATION")
val billingLifetime =
    (project.findProperty("snapaie.billing.lifetime.id") as String?) ?: "snapaie_pro_lifetime"
@Suppress("DEPRECATION")
val modelManifestUrl =
    (project.findProperty("snapaie.model.manifest.url") as String?)?.trim().orEmpty()

android {
    namespace = "com.snapaie.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.snapaie.android"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("String", "BILLING_PRODUCT_LIFETIME", "\"$billingLifetime\"")
        buildConfigField("String", "MODEL_MANIFEST_URL", "\"$modelManifestUrl\"")
    }

    buildTypes {
        release {
            // LiteRT-LM and PDFBox ship native libraries for four ABIs, which is 88 MB of
            // the debug APK. x86 builds exist for emulators, and emulators cannot run the
            // model anyway, so release keeps only the two ABIs real phones use. (A Play
            // App Bundle would split these automatically; this is for direct APKs.)
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.accompanist.permissions)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)

    implementation(libs.litertlm.android)
    implementation(libs.pdfbox.android)

    implementation(libs.billing.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

kotlin {
    jvmToolchain(17)
}
