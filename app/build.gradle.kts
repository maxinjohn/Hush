import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries.android)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

val discordApplicationId =
    (
        localProperties.getProperty("DISCORD_APPLICATION_ID")
            ?: System.getenv("DISCORD_APPLICATION_ID")
            ?: "1165706613961789445"
        ).trim()
val discordApplicationIdLong = discordApplicationId.toLongOrNull() ?: 1165706613961789445L
val discordRedirectScheme = "discord-$discordApplicationId"
val releaseKeystoreFile = file("keystore/release.keystore")
fun signingProperty(name: String): String? =
    localProperties.getProperty(name)?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }
val releaseStorePassword =
    signingProperty("STORE_PASSWORD") ?: signingProperty("KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty("KEY_ALIAS")
val releaseKeyPassword = signingProperty("KEY_PASSWORD")
val hasReleaseSigningConfig =
    releaseKeystoreFile.isFile &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null
val unsignedReleaseBuild = System.getenv("HUSH_UNSIGNED_RELEASE_BUILD") == "true"

android {
    namespace = "app.hush.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.hush.music"
        minSdk = 26
        targetSdk = 37
        versionCode = 155
        versionName = "13.11.5"

        ndk {
            // ABI filters are set per product flavor (arm64, universal, etc.).
            abiFilters.clear()
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        val lastfmApiKey =
            localProperties.getProperty("LASTFM_API_KEY")
                ?: System.getenv("LASTFM_API_KEY")
                ?: ""
        val lastfmSecret =
            localProperties.getProperty("LASTFM_SECRET")
                ?: System.getenv("LASTFM_SECRET")
                ?: ""
        buildConfigField("String", "LASTFM_API_KEY", "\"$lastfmApiKey\"")
        buildConfigField("String", "LASTFM_SECRET", "\"$lastfmSecret\"")

        val togetherBearerToken =
            localProperties.getProperty("TOGETHER_BEARER_TOKEN")
                ?: System.getenv("TOGETHER_BEARER_TOKEN")
                ?: ""
        buildConfigField("String", "TOGETHER_BEARER_TOKEN", "\"$togetherBearerToken\"")

        val canvasBearerToken =
            localProperties.getProperty("CANVAS_BEARER_TOKEN")
                ?: System.getenv("CANVAS_BEARER_TOKEN")
                ?: ""
        buildConfigField("String", "CANVAS_BEARER_TOKEN", "\"$canvasBearerToken\"")

        val extractorBearer =
            localProperties.getProperty("EXTRACTOR_BEARER")
                ?: System.getenv("EXTRACTOR_BEARER")
                ?: ""
        buildConfigField("String", "EXTRACTOR_BEARER", "\"$extractorBearer\"")

        val nightlyBuildHash =
            (
                localProperties.getProperty("NIGHTLY_BUILD_HASH")
                    ?: System.getenv("NIGHTLY_BUILD_HASH")
                    ?: ""
                ).trim()
        buildConfigField("String", "NIGHTLY_BUILD_HASH", "\"$nightlyBuildHash\"")
        buildConfigField("String", "DISTRIBUTION", "\"gms\"")
        buildConfigField("boolean", "UPDATER_AVAILABLE", "true")
    }

    flavorDimensions += listOf("distribution", "device", "abi")
    productFlavors {
        create("gms") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("String", "DISTRIBUTION", "\"gms\"")
            buildConfigField("boolean", "UPDATER_AVAILABLE", "true")
            buildConfigField("String", "DISCORD_APPLICATION_ID", "\"$discordApplicationId\"")
            buildConfigField("long", "DISCORD_APPLICATION_ID_LONG", "${discordApplicationIdLong}L")
            buildConfigField("String", "DISCORD_REDIRECT_SCHEME", "\"$discordRedirectScheme\"")
            manifestPlaceholders["discordRedirectScheme"] = discordRedirectScheme
        }
        create("foss") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"foss\"")
            buildConfigField("boolean", "UPDATER_AVAILABLE", "true")
            buildConfigField("String", "DISCORD_APPLICATION_ID", "\"$discordApplicationId\"")
            buildConfigField("long", "DISCORD_APPLICATION_ID_LONG", "${discordApplicationIdLong}L")
            buildConfigField("String", "DISCORD_REDIRECT_SCHEME", "\"$discordRedirectScheme\"")
            manifestPlaceholders["discordRedirectScheme"] = discordRedirectScheme
        }
        create("mobile") {
            dimension = "device"
            buildConfigField("String", "DEVICE", "\"mobile\"")
        }
        create("tv") {
            dimension = "device"
            buildConfigField("String", "DEVICE", "\"tv\"")
        }
        create("universal") {
            dimension = "abi"
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
            buildConfigField("String", "ARCHITECTURE", "\"universal\"")
        }
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
            buildConfigField("String", "ARCHITECTURE", "\"arm64\"")
        }
        create("armeabi") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
            buildConfigField("String", "ARCHITECTURE", "\"armeabi\"")
        }
        create("x86") {
            dimension = "abi"
            ndk { abiFilters += "x86" }
            buildConfigField("String", "ARCHITECTURE", "\"x86\"")
        }
        create("x86_64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
            buildConfigField("String", "ARCHITECTURE", "\"x86_64\"")
        }
    }

    sourceSets {
        getByName("mobile") {
            assets.directories.add("src/mobile/assets")
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        create("release") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            if (hasReleaseSigningConfig) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Match CI: Gradle does not apply the release keystore. CI and local installs use
            // apksigner after assemble (ilharp/sign-android-release or scripts/resign-release-apk.sh).
            // Signing twice (Gradle release + apksigner) leaves broken v1 JAR signatures.
            signingConfig =
                if (unsignedReleaseBuild || hasReleaseSigningConfig) {
                    null
                } else {
                    signingConfigs.getByName("debug")
                }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = false
        checkDependencies = false
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        jniLibs {
            // Compressed native libs — better sideload compatibility than page-aligned APKs on some OEM installers.
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "**/libandroidx.graphics.path.so",
                "**/libdatastore_shared_counter.so"
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
        }
    }

}

androidComponents {
    onVariants { variant ->
        val distribution =
            variant.productFlavors.firstOrNull { it.first == "distribution" }?.second ?: "gms"
        val device = variant.productFlavors.firstOrNull { it.first == "device" }?.second ?: "mobile"
        val abi = variant.productFlavors.firstOrNull { it.first == "abi" }?.second ?: "universal"
        val buildType = variant.buildType?.lowercase().orEmpty().ifBlank { "release" }
        val apkFileName = "hush-$distribution-$device-$abi-$buildType.apk"
        variant.outputs.forEach { output ->
            output.outputFileName.set(apkFileName)
        }
    }
}

tasks.register("assembleFossMobileReleaseApks") {
    group = "build"
    description = "Build all FOSS mobile release ABIs (unsigned; run scripts/build-release.sh to sign)."
    dependsOn(
        "assembleFossMobileUniversalRelease",
        "assembleFossMobileArm64Release",
        "assembleFossMobileArmeabiRelease",
        "assembleFossMobileX86Release",
        "assembleFossMobileX86_64Release",
    )
    doLast { logUnsignedReleaseReminder() }
}

tasks.register("assembleGmsMobileReleaseApks") {
    group = "build"
    description = "Build all GMS mobile release ABIs (unsigned; run scripts/build-release.sh to sign)."
    dependsOn(
        "assembleGmsMobileUniversalRelease",
        "assembleGmsMobileArm64Release",
        "assembleGmsMobileArmeabiRelease",
        "assembleGmsMobileX86Release",
        "assembleGmsMobileX86_64Release",
    )
    doLast { logUnsignedReleaseReminder() }
}

tasks.register("assembleGmsTvReleaseApks") {
    group = "build"
    description = "Build all GMS TV release ABIs (unsigned; run scripts/build-release.sh to sign)."
    dependsOn(
        "assembleGmsTvUniversalRelease",
        "assembleGmsTvArm64Release",
        "assembleGmsTvArmeabiRelease",
        "assembleGmsTvX86Release",
        "assembleGmsTvX86_64Release",
    )
    doLast { logUnsignedReleaseReminder() }
}

tasks.register("assembleFossTvReleaseApks") {
    group = "build"
    description = "Build all FOSS TV release ABIs (unsigned; run scripts/build-release.sh to sign)."
    dependsOn(
        "assembleFossTvUniversalRelease",
        "assembleFossTvArm64Release",
        "assembleFossTvArmeabiRelease",
        "assembleFossTvX86Release",
        "assembleFossTvX86_64Release",
    )
    doLast { logUnsignedReleaseReminder() }
}

fun logUnsignedReleaseReminder() {
    if (!hasReleaseSigningConfig) return
    logger.lifecycle(
        """
        |
        |Release APKs are UNSIGNED until you run:
        |  bash scripts/build-release.sh [foss|gms] [mobile|tv] [abi]
        |  bash scripts/build-release.sh list
        |
        """.trimMargin(),
    )
}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.navigation)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)
    implementation(libs.work.runtime)
    implementation("androidx.browser:browser:1.10.0")

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    compileOnly("androidx.compose.ui:ui-tooling-preview:${libs.versions.compose.get()}")
    debugImplementation("androidx.compose.ui:ui-tooling-preview:${libs.versions.compose.get()}")
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.reorderable)

    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.material3)
    implementation(libs.palette)
    implementation(libs.androidsvg)
    implementation(libs.aboutlibraries.core)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)
    implementation(libs.markwon.image)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.simple.ext)

    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    implementation(libs.shimmer)

    // Glance Widget support
    implementation("androidx.glance:glance:1.1.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation(libs.media3)
    implementation("androidx.media3:media3-exoplayer-hls:${libs.versions.media3.get()}")
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation("androidx.media3:media3-ui:${libs.versions.media3.get()}")
    implementation("androidx.media3:media3-ui-compose:${libs.versions.media3.get()}")
    add("gmsImplementation", libs.media3.cast)
    add("gmsImplementation", libs.mediarouter)
    implementation(libs.squigglyslider)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    implementation(libs.re2j)
    annotationProcessor(libs.kotlin.metadata.jvm)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)

    implementation(project(":core"))
    implementation(project(":lyrics:kugou"))
    implementation(project(":lyrics:lrclib"))
    implementation(project(":lyrics:simpmusic"))
    implementation(project(":lyrics:paxsenix"))
    implementation(project(":lyrics:betterlyrics"))
    implementation(project(":lyrics:unison"))
    implementation(project(":lyrics:youlyplus"))
    implementation(project(":lastfm"))
    implementation(project(":canvas"))
    implementation(project(":shazamkit"))
    implementation(project(":spotifycore"))
    implementation(project(":moriextractor"))
    implementation(project(":jiosaavn"))
    implementation("com.materialkolor:material-kolor:5.0.0-alpha07")

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.timber)
    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    implementation(libs.translator)
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0-rc01")
    implementation(libs.accompanist.lyrics.ui)
    implementation(libs.accompanist.lyrics.core)

    implementation("org.json:json:20240303")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn"
        )
        // Suppress warnings
        suppressWarnings.set(true)
    }
}

configurations.configureEach {
    resolutionStrategy.force(
        "androidx.compose.runtime:runtime:${libs.versions.compose.get()}",
        "androidx.compose.foundation:foundation:${libs.versions.compose.get()}",
        "androidx.compose.ui:ui:${libs.versions.compose.get()}",
        "androidx.compose.ui:ui-util:${libs.versions.compose.get()}",
        "androidx.compose.ui:ui-tooling:${libs.versions.compose.get()}",
        "androidx.compose.animation:animation-graphics:${libs.versions.compose.get()}",
        "org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlinMetadata.get()}",
    )
}

// R8 can start before Hilt's Java codegen on a cold build; force ordering.
afterEvaluate {
    tasks.matching { task -> task.name.startsWith("minify") && task.name.endsWith("WithR8") }.configureEach {
        val variantName = name.removePrefix("minify").removeSuffix("WithR8")
        tasks.findByName("hiltJavaCompile$variantName")?.let { dependsOn(it) }
        tasks.findByName("transform${variantName}ClassesWithAsm")?.let { dependsOn(it) }
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
