plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    // Namespace (Kotlin/R-class package) stays distinct from :app's; applicationId below is what
    // matters for Wear OS pairing and can safely differ from it.
    namespace = "com.bugzapperlabs.mycasts.wear"
    compileSdk = 36

    defaultConfig {
        // Must match :app's applicationId exactly (issue #276): the Wear OS Data Layer API only
        // syncs data between a phone app and a watch app that share the same package name -- two
        // differently-packaged apps are treated as unrelated by Play Services even when both
        // devices see each other as connected nodes, so this isn't a style choice, it's required
        // for WearSyncClient/DataClient to deliver anything at all. This is also why real Wear OS
        // companion apps (the phone and watch halves of the same product) always share one
        // package name rather than being namespaced like ":wear"'s own Kotlin code is.
        applicationId = "com.bugzapperlabs.mycasts"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Mirrors :app's convention (issue #258) so a debug watch build installs alongside a
            // release-signed one instead of colliding on install.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    // play-services-wearable and kotlinx-coroutines-play-services come transitively from :core's
    // api dependency (PlayServicesWearSyncClient lives there) -- no need to redeclare.
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    debugImplementation(libs.androidx.ui.tooling)
}
