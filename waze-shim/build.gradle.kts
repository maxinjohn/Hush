
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.Sync
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    file("../local.properties").run {
        if (exists()) {
            load(FileInputStream(this))
        }
    }
}
fun signingProperty(name: String): String? =
    localProperties.getProperty(name)?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }

val releaseKeystoreFile = file("../app/keystore/release.keystore")
val releaseStorePassword = signingProperty("STORE_PASSWORD") ?: signingProperty("KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty("KEY_ALIAS")
val releaseKeyPassword = signingProperty("KEY_PASSWORD")
val hasReleaseSigningConfig =
    releaseKeystoreFile.isFile &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null
val unsignedReleaseBuild = System.getenv("HUSH_UNSIGNED_RELEASE_BUILD") == "true"
val generatedHushBridgeIconResourcesDir = layout.buildDirectory.dir("generated/hushBridgeIcon/res")
val syncHushBridgeIconResources = tasks.register<Sync>("syncHushBridgeIconResources") {
    from(rootProject.file("app/src/main/res")) {
        include("mipmap-*/ic_launcher*.png")
        include("mipmap-anydpi-v26/ic_launcher*.xml")
        include("mipmap-anydpi-v31/ic_launcher*.xml")
        include("values/ic_launcher_background.xml")
        exclude("**/ic_launcher_static*")
    }
    into(generatedHushBridgeIconResourcesDir)
}

android {
    namespace = "app.hush.music.waze"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.spotify.music"
        minSdk = 26
        targetSdk = 37
        versionCode = 163
        versionName = "13.13.1"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        create("release") {
            storeFile = releaseKeystoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    sourceSets {
        getByName("main") {
            res.srcDir(file("build/generated/hushBridgeIcon/res"))
        }
    }
}

// Build Waze shim APKs and compress them into app assets
// Run: ./gradlew :app:copyShimApks
val copyShimApks = tasks.register<Copy>("copyShimApks") {
    description = "Copies Waze shim APKs zip into app assets"
    group = "hush"
    dependsOn(":waze-shim:packageShimApks")
    from(rootProject.file("waze-shim/build/outputs/apk/waze-shims.zip"))
    into(rootProject.file("app/src/mobile/assets"))
    doLast {
        println("Copied waze-shims.zip to app/src/mobile/assets/")
    }
}

tasks.configureEach {
    if (name.contains("Mobile")) {
        dependsOn(copyShimApks)
    }
}

dependencies {
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.appcompat:appcompat:1.6.1")
}

tasks.register<Zip>("packageShimApks") {
    group = "hush"
    description = "Packages the Waze bridge APKs for embedding in Hush."
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("waze-shim-release.apk")
    }
    destinationDirectory.set(layout.buildDirectory.dir("outputs/apk"))
    archiveFileName.set("waze-shims.zip")
}
