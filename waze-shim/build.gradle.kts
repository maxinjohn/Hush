import org.gradle.api.tasks.bundling.Zip

plugins {
    id("com.android.application")
}

val shimKeystoreFile = System.getenv("HUSH_SHIM_KEYSTORE")?.let(::file)
val shimStorePassword = System.getenv("HUSH_SHIM_STORE_PASSWORD")
val shimKeyAlias = System.getenv("HUSH_SHIM_KEY_ALIAS")
val shimKeyPassword = System.getenv("HUSH_SHIM_KEY_PASSWORD")
val hasReleaseSigningConfig =
    shimKeystoreFile?.isFile == true &&
        !shimStorePassword.isNullOrBlank() &&
        !shimKeyAlias.isNullOrBlank() &&
        !shimKeyPassword.isNullOrBlank()
val unsignedReleaseBuild = System.getenv("HUSH_UNSIGNED_RELEASE_BUILD") == "true"

android {
    namespace = "app.hush.music.waze"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.spotify.music"
        minSdk = 26
        targetSdk = 37
        versionCode = 152
        versionName = "13.11.2"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        create("release") {
            storeFile = shimKeystoreFile
            storePassword = shimStorePassword
            keyAlias = shimKeyAlias
            keyPassword = shimKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // CI supplies a persistent release key so installed bridges can be updated.
            signingConfig =
                when {
                    unsignedReleaseBuild -> null
                    hasReleaseSigningConfig -> signingConfigs.getByName("release")
                    else -> signingConfigs.getByName("debug")
                }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    flavorDimensions += "target"
    productFlavors {
        create("spotify") {
            dimension = "target"
            applicationId = "com.spotify.music"
        }
        create("youtubeMusic") {
            dimension = "target"
            applicationId = "com.google.android.apps.youtube.music"
        }
    }
}

dependencies {
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

tasks.register<Zip>("packageShimApks") {
    group = "hush"
    description = "Packages the Waze bridge APKs for embedding in Hush."
    dependsOn("assembleSpotifyRelease", "assembleYoutubeMusicRelease")
    from(layout.buildDirectory.dir("outputs/apk/spotify/release")) {
        include("waze-shim-spotify-release.apk")
    }
    from(layout.buildDirectory.dir("outputs/apk/youtubeMusic/release")) {
        include("waze-shim-youtubeMusic-release.apk")
    }
    destinationDirectory.set(layout.buildDirectory.dir("outputs/apk"))
    archiveFileName.set("waze-shims.zip")
}
