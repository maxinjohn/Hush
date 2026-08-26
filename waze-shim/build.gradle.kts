
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.Sync
import java.io.FileInputStream
import java.util.Base64
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

val releaseKeystoreFile = run {
    val source = file("../app/keystore/release.keystore")
    if (!source.isFile) {
        source
    } else {
        val encoded = source.readText(Charsets.US_ASCII).trim()
        val looksBase64 =
            encoded.length >= 64 &&
                encoded.length % 4 == 0 &&
                encoded.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        if (!looksBase64) {
            source
        } else {
            runCatching {
                val decodedBytes = Base64.getDecoder().decode(encoded)
                val decoded = layout.buildDirectory.file("signing/release.keystore").get().asFile
                if (!decoded.exists() || !decoded.readBytes().contentEquals(decodedBytes)) {
                    decoded.parentFile.mkdirs()
                    decoded.writeBytes(decodedBytes)
                }
                decoded
            }.getOrElse { source }
        }
    }
}
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
        versionCode = 167
        versionName = "13.13.6"
    }

    flavorDimensions += "bridge"
    productFlavors {
        create("spotify") {
            dimension = "bridge"
            applicationId = "com.spotify.music"
        }
        create("youtubeMusic") {
            dimension = "bridge"
            applicationId = "com.google.android.apps.youtube.music"
        }
        create("deezer") {
            dimension = "bridge"
            applicationId = "deezer.android.app"
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        create("release") {
            if (hasReleaseSigningConfig) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Always use the same signing key as the main Hush app.
            // The shim masquerades as com.spotify.music / com.google.android.apps.youtube.music / deezer.android.app
            // to integrate with Waze, but it must share the Hush app's signing key so ADB installs
            // and updates don't fail with "signature mismatch".
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
    getByName("spotify") {
        res.srcDir(file("build/generated/hushBridgeIcon/res"))
    }
    getByName("youtubeMusic") {
        res.srcDir(file("build/generated/hushBridgeIcon/res"))
    }
    getByName("deezer") {
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
    testImplementation("junit:junit:4.13.2")
}

tasks.register<Zip>("packageShimApks") {
    group = "hush"
    description = "Packages the Waze bridge APKs for embedding in Hush."
    dependsOn("assembleSpotifyRelease", "assembleYoutubeMusicRelease", "assembleDeezerRelease")
    from(layout.buildDirectory.dir("outputs/apk/spotify/release")) {
        include("waze-shim-spotify-release.apk")
    }
    from(layout.buildDirectory.dir("outputs/apk/youtubeMusic/release")) {
        include("waze-shim-youtubeMusic-release.apk")
    }
    from(layout.buildDirectory.dir("outputs/apk/deezer/release")) {
        include("waze-shim-deezer-release.apk")
    }
    destinationDirectory.set(layout.buildDirectory.dir("outputs/apk"))
    archiveFileName.set("waze-shims.zip")
}

// Ensure generated icons are available before resource processing for all flavors
tasks.withType<com.android.build.gradle.tasks.MergeResources> {
    dependsOn(syncHushBridgeIconResources)
}
tasks.withType<com.android.build.gradle.tasks.ProcessApplicationManifest> {
    dependsOn(syncHushBridgeIconResources)
}
tasks.matching { it.name.startsWith("process") && it.name.contains("NavigationResources") }.configureEach {
    dependsOn(syncHushBridgeIconResources)
}
tasks.matching { it.name.startsWith("generate") && it.name.contains("Resources") }.configureEach {
    dependsOn(syncHushBridgeIconResources)
}
tasks.matching { it.name.startsWith("map") && it.name.contains("SourceSetPaths") }.configureEach {
    dependsOn(syncHushBridgeIconResources)
}
tasks.matching { it.name.startsWith("extractDeepLinks") }.configureEach {
    dependsOn(syncHushBridgeIconResources)
}
