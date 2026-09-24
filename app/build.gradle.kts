import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
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
val releaseKeystoreFile = run {
    val source = file("keystore/release.keystore")
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
        versionCode = 176
        versionName = "13.14.6"

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
            buildConfigField("boolean", "WAZE_SUPPORTED", "true")
        }
        create("tv") {
            dimension = "device"
            buildConfigField("String", "DEVICE", "\"tv\"")
            buildConfigField("boolean", "WAZE_SUPPORTED", "false")
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

    // Waze shims are mobile-only: bundle them into the mobile flavor's own
    // assets dir so TV/other variants never carry the ~1 MB archive.
    sourceSets {
        getByName("mobile") {
            assets.srcDirs("src/mobile/assets")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
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
        // True so lintVital (part of every release assemble) actually aborts the build on
        // a fatal NewApi issue. With abortOnError=false even fatal issues were only
        // reported, and an above-minSdk call shipped unnoticed in v13.14.0. NewApi is the
        // only fatal-severity rule (see lint.xml); warnings plus the severity errors below
        // do not fail the build.
        abortOnError = true
        checkDependencies = false
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        jniLibs {
            // Compressed native libs — better sideload compatibility than page-aligned APKs on some OEM installers.
            useLegacyPackaging = true
            // The gobackend AAR ships arm32/arm64 slices of libgojni.so only;
            // x86/x86_64 variants just see no SpotiFLAC runtime (same as
            // upstream's per-ABI builds).
            pickFirsts += listOf("**/libgojni.so")
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
    // Resolved during configuration: a task action that calls into this build
    // script cannot be serialized by the configuration cache.
    val unsignedReminder = unsignedReleaseReminderMessage()
    doLast {
        if (unsignedReminder != null) logger.lifecycle(unsignedReminder)
    }
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
    // Resolved during configuration: a task action that calls into this build
    // script cannot be serialized by the configuration cache.
    val unsignedReminder = unsignedReleaseReminderMessage()
    doLast {
        if (unsignedReminder != null) logger.lifecycle(unsignedReminder)
    }
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
    // Resolved during configuration: a task action that calls into this build
    // script cannot be serialized by the configuration cache.
    val unsignedReminder = unsignedReleaseReminderMessage()
    doLast {
        if (unsignedReminder != null) logger.lifecycle(unsignedReminder)
    }
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
    // Resolved during configuration: a task action that calls into this build
    // script cannot be serialized by the configuration cache.
    val unsignedReminder = unsignedReleaseReminderMessage()
    doLast {
        if (unsignedReminder != null) logger.lifecycle(unsignedReminder)
    }
}

/**
 * The "these APKs are unsigned" advisory, or null when there is nothing to say.
 *
 * Null when no release keystore is configured: that build falls back to the
 * debug signing config, so the APKs are not actually unsigned.
 */
fun unsignedReleaseReminderMessage(): String? {
    if (!hasReleaseSigningConfig) return null
    return """
        |
        |Release APKs are UNSIGNED until you run:
        |  bash scripts/build-release.sh [foss|gms] [mobile|tv] [abi]
        |  bash scripts/build-release.sh list
        |
        """.trimMargin()
}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Upstream SpotiFLAC Go runtime (Goja extension engine + signed-session +
    // provider download pipeline), built from the SpotiFLAC-Mobile go_backend
    // via gomobile. Reflection-based access keeps this optional at compile time.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

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
    implementation(project(":lyrics:musixmatch"))
    implementation(project(":lastfm"))
    implementation(project(":canvas"))
    implementation(project(":shazamkit"))
    implementation(project(":spotifycore"))
    implementation(project(":moriextractor"))
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
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-client-content-negotiation:${libs.versions.ktor.get()}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
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
//
// One Copy task per asset dir: Gradle's Copy keeps a single destination, and a
// doLast that mirrored the archive by hand referenced the build script, which
// the configuration cache cannot serialize. Such a task then dies at execution
// with "Cannot read field \$\$implicitReceiver_Project because \$this is null".
// Two declarative copies keep every task value serializable.
val shimApksZip = rootProject.file("waze-shim/build/outputs/apk/waze-shims.zip")

// Only the mobile flavor's asset dir is written. src/main/assets is merged into
// *every* variant, TV included, so producing the archive there made each
// non-mobile merge task consume a file another task produces with no ordering
// relationship - which Gradle rejects as an implicit-dependency validation
// failure (`assembleGmsTvUniversalRelease` failed for exactly that reason).
// Waze is mobile-only (WAZE_SUPPORTED=false on TV) and the runtime reads the
// shims from this archive, so the flavor copy is the one that matters.
val copyShimApksToFlavorAssets = tasks.register<Copy>("copyShimApksToFlavorAssets") {
    // Consuming the Zip task's output requires declaring the producer, or Gradle
    // reports an implicit-dependency validation failure.
    dependsOn(":waze-shim:packageShimApks")
    from(shimApksZip)
    into(rootProject.file("app/src/mobile/assets"))
}

val copyShimApks = tasks.register("copyShimApks") {
    description = "Copies Waze shim APKs zip into app assets"
    group = "hush"
    dependsOn(":waze-shim:packageShimApks")
    dependsOn(copyShimApksToFlavorAssets)
}

tasks.configureEach {
    if (name.contains("Mobile")) {
        dependsOn(copyShimApks)
    }
}

/**
 * Rejects a download that is named with a hand-written container extension, as a task action that
 * carries only its own inputs.
 *
 * It cannot be written as an inline `doLast`. Every value such an action reads - the file list, the
 * function names, the parsing helpers - is a member of the generated build script, so reading one
 * captures the script instance, and the configuration cache cannot serialize one:
 * `Task ':app:verifyDownloadNaming' of type 'org.gradle.api.DefaultTask': cannot serialize Gradle
 * script object references`. CI stores the configuration cache, so the guard that protects every
 * assemble was itself what failed the release build's reproducibility stage. Everything the action
 * needs arrives as a parameter, and the parsing helpers are its own members, so nothing in the
 * serialized action reaches back into the script.
 */
fun downloadNamingVerifier(sources: List<File>): Action<Task> = object : Action<Task> {
    /**
     * The text inside a function call, from its `(` to the `)` that closes it.
     *
     * Quoted strings are skipped so a `)` inside one cannot end the call early, which matters
     * because these call sites pass file paths and titles.
     */
    private fun callBody(
        source: String,
        openParenIndex: Int,
    ): String? {
        var depth = 0
        var inString = false
        var index = openParenIndex
        while (index < source.length) {
            val character = source[index]
            when {
                inString ->
                    when (character) {
                        '\\' -> index++
                        '"' -> inString = false
                    }

                character == '"' -> inString = true
                character == '(' -> depth++
                character == ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openParenIndex + 1, index)
                }
            }
            index++
        }
        return null
    }

    /** Splits a call's arguments at its top-level commas, dropping a trailing comma's gap. */
    private fun callArguments(body: String): List<String> {
        val arguments = mutableListOf<String>()
        var depth = 0
        var inString = false
        var start = 0
        var index = 0
        while (index < body.length) {
            val character = body[index]
            when {
                inString ->
                    when (character) {
                        '\\' -> index++
                        '"' -> inString = false
                    }

                character == '"' -> inString = true
                character == '(' || character == '[' || character == '{' -> depth++
                character == ')' || character == ']' || character == '}' -> depth--
                character == ',' && depth == 0 -> {
                    arguments += body.substring(start, index)
                    start = index + 1
                }
            }
            index++
        }
        arguments += body.substring(start)
        return arguments.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The string literal this identifier is initialised with in [source], if it is one. */
    private fun literalInitialiserOf(
        identifier: String,
        source: String,
    ): String? {
        if (!identifier.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
        val pattern =
            Regex(
                "(?:const\\s+)?val\\s+" + Regex.escape(identifier) +
                    "\\s*(?::\\s*[\\w<>.?]+)?\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\")",
            )
        return pattern.find(source)?.groupValues?.get(1)
    }

    /** The 1-based line [index] falls on, so a failure names the place to look. */
    private fun lineOf(
        source: String,
        index: Int,
    ): Int = source.take(index).count { it == '\n' } + 1

    override fun execute(task: Task) {
        // Functions whose last argument becomes a file's extension.
        val namingFunctions = listOf("claimTargetFile(", "DownloadNaming.fileName(")
        val violations = mutableListOf<String>()
        sources.forEach { file ->
            val source = file.readText()
            namingFunctions.forEach { function ->
                var index = source.indexOf(function)
                while (index >= 0) {
                    // A documentation comment naming the function is not a call site.
                    val lineStart = source.lastIndexOf('\n', (index - 1).coerceAtLeast(0)) + 1
                    val lineEnd = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length
                    val line = source.substring(lineStart, lineEnd).trimStart()
                    val isComment = line.startsWith("*") || line.startsWith("//")
                    val body = if (isComment) null else callBody(source, index + function.length - 1)
                    val extension = body?.let { callArguments(it).lastOrNull() }
                    if (extension != null) {
                        val literal = extension.takeIf { it.startsWith("\"") }
                            ?: literalInitialiserOf(extension, source)
                        if (literal != null) {
                            violations += "${file.path}:${lineOf(source, index)}: " +
                                "$function is given the extension $literal directly. " +
                                "Pass one derived from the file (DownloadNaming.extensionForFile) " +
                                "or the served type (DownloadNaming.extensionForMimeType)."
                        }
                    }
                    index = source.indexOf(function, index + function.length)
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "A download name may not carry a hand-written container extension:\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }
}

/**
 * Rejects a download that is named with a hand-written container extension.
 *
 * v13.14.3 downloaded tracks as `.flac` files whose first bytes were `ftypmp42`. The SpotiFLAC
 * branch passed a `FLAC_EXTENSION` constant it had declared itself, on the assumption that this
 * source's output "is FLAC by definition" - which stops being true the moment a source falls
 * back to a lossy container (measured: a track with no lossless match arrives as MP4/AAC). The
 * file was perfect and every player called it corrupt, and no build step could tell, because a
 * wrong extension is only a string.
 *
 * So the shape is enforced instead: an extension handed to the naming path has to come from
 * `DownloadNaming`, which derives it - from the file's own header, or from the served mime type.
 * A literal, or a constant initialised from one, fails every assemble.
 *
 * Deliberately not a rule about which containers are legal: this is about *provenance*. A new
 * container can be supported without touching this guard, and a wrong guess cannot be added
 * without failing the release build CI produces - which is where the last one should have died.
 */
val verifyDownloadNaming by tasks.registering {
    description = "Fails when a download name is given a hand-written container extension"
    group = "verification"

    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs
        .files(sources)
        .withPropertyName("downloadNamingSources")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)

    // The action is built here, from an already-resolved file list, so what gets stored in the
    // configuration cache is data rather than a reference to this script. Sorted, so a failure
    // reports the same order twice.
    doLast(downloadNamingVerifier(sources.files.sorted()))
}

/**
 * Reports every raw `Modifier.basicMarquee()` call outside [HushMarquee], as a task action that
 * carries only its own inputs.
 *
 * `basicMarquee` defaults to three passes, so a label that is wider than its row scrolls a few
 * times and then stops half way through the text - which reads as a broken label rather than a
 * long one. Hush wraps it in `Modifier.hushMarquee()` (`HushMarquee.kt`), which pins the iteration
 * count and the delays; this guard keeps call sites from drifting back to the bare default, which
 * is a one-word edit that no compiler or lint check can see.
 *
 * Same shape as the download-naming verifier: the action is built from an already-resolved file
 * list and holds no reference to the build script, so the configuration cache can serialize it.
 */
fun marqueeRoutingVerifier(
    sources: List<File>,
    root: File,
): Action<Task> = object : Action<Task> {
    /** The one file allowed to reach the upstream default: the wrapper itself. */
    private val wrapperFileName = "HushMarquee.kt"

    override fun execute(task: Task) {
        val offenders =
            sources
                .asSequence()
                .filter { it.name != wrapperFileName }
                .flatMap { file ->
                    file
                        .readLines()
                        .asSequence()
                        .mapIndexedNotNull { index, line ->
                            // A trailing comment that names the modifier is documentation, not a call.
                            val code = line.substringBefore("//")
                            if (code.contains("basicMarquee(")) {
                                "${file.relativeToOrSelf(root)}" + ":${index + 1}"
                            } else {
                                null
                            }
                        }
                }.toList()

        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("These marquee call sites bypass Hush's own wrapper:")
                    offenders.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Use Modifier.hushMarquee() (app.hush.music.ui.component) instead of Modifier.basicMarquee().")
                    append("A raw default stops after three passes, leaving a long label frozen part way through. ")
                    append("If a genuinely different marquee is needed, change HushMarquee.kt so every label shares it.")
                },
            )
        }
    }
}

/**
 * Rejects a raw `Modifier.basicMarquee()` call outside `HushMarquee.kt`.
 *
 * Measured on a car screen: the title overlay scrolled once across its own width and stopped, so a
 * long track name was unreadable from the second screenful onward - and a phone with animation
 * scales at 0 froze even that single pass. Routing every long label through one wrapper means the
 * pace, the repeats and the "nothing moves when it fits" rule are decided in one place.
 */
val verifyMarqueeRouting by tasks.registering {
    description = "Fails when a marquee call site bypasses Hush's own hushMarquee wrapper"
    group = "verification"

    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs
        .files(sources)
        .withPropertyName("marqueeSources")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)

    doLast(marqueeRoutingVerifier(sources.files.sorted(), projectDir))
}

/**
 * Rejects a loose control in the SpotiFLAC settings rows, as a task action that carries only its own
 * inputs.
 *
 * Three row styles live on that screen and every one had the same disease: several controls for one
 * job. The verification card offered three ways to ask for one check - a per-row "Verify"/"Re-verify"
 * that appeared whenever a row needed one, a merged "Verify & renew all", and a Verify on the engine
 * status line - and the per-row one was the worst of the three, because the button sat on the row
 * that was *already* verified, so it promised work that had been done. The extension source rows did
 * it with a line of loose text buttons and arrows: "Test", then a column of ▲/▼, with "Update"
 * appearing under the description - five tap targets wide, so the Switch, the one control that is
 * about the row's *state* rather than about doing something, read as one of them. The playback
 * priority list had the same pair of arrows for one job - moving a row in a list of two - and the
 * one that did not apply simply vanished, so the row's width changed as the list was reordered.
 *
 * All three now say their state in text and offer one overflow: the card's rows carry Renew and
 * Verify for the batch and one overflow each, the source rows carry the verdict, the Switch and one
 * overflow, and the priority rows carry their 1st/2nd badge and one overflow.
 *
 * That a row offers nothing else is a property of the composition - no test of session state can see
 * it - and it is one `TextButton { Text("Test") }` away from coming back. So it is enforced where the
 * other two source guards are: on every assemble.
 *
 * Enforcement, by surface:
 *  - the card's row loop: no button-like composable but exactly one overflow, no clickable/toggleable
 *    modifier (a clickable label is a control wearing no button), at most two tappable callbacks,
 *    exactly one dropdown item, and the row's only verify wording inside that item;
 *  - the card above its rows: one batch verify call, one renew call, and no single label doing both;
 *  - `SourceRow`: no text button or chip at all, exactly one overflow whose menu holds the test and
 *    reorder actions, and exactly one Switch left in the open;
 *  - `PriorityEngineRow`: no text button or chip, exactly one overflow carrying both reorder
 *    actions, and no switch (nothing on that row is state);
 *  - all three: the button and its menu inside one `Box`, so the menu is anchored to the button.
 *    Measured on the device: the menu opened at x=64..564 of a 1440-wide screen while the button
 *    that had opened it sat at x=1200..1360 - the opposite edge. Compose anchors a `DropdownMenu`
 *    to its parent layout node, and as siblings of a full-width row the two anchored to the row.
 *
 * The anchors are part of the contract rather than a convenience. A guard that cannot find the row
 * loop, the `SourceRow` composable, or a row's overflow, fails rather than passes - "there was
 * nothing to look at" is how a check like this dies quietly. The detector is also run over its own
 * fixtures on every assemble (the row bodies as they were, which must trip it, and the rows on disk,
 * which must not), so a guard whose rules had stopped working could not sail through the build
 * unnoticed.
 */
fun rowControlVerifier(
    sources: List<File>,
    root: File,
): Action<Task> = object : Action<Task> {
    /** The loop that draws one row per source. */
    private val rowAnchor = "sessionRows.forEach"

    /** The composable that draws one row per extension source. */
    private val sourceRowAnchor = "private fun SourceRow("

    /** The composable that draws one row per engine in the playback priority list. */
    private val priorityRowAnchor = "private fun PriorityEngineRow("

    /** The card's title: where the region the batch rules read begins. */
    private val cardAnchor = "Extension verification"

    /** The one control a row may carry. */
    private val overflowIcon = "MoreVert"

    /** The overflow and its single item: a third callback would be a third control. */
    private val maxRowClicks = 2

    private val forbiddenControls = listOf(
        "TextButton", "OutlinedButton", "FilledTonalButton", "ElevatedButton", "Button",
        "IconToggleButton", "FilledIconButton", "FilledTonalIconButton", "OutlinedIconButton",
        "AssistChip", "FilterChip", "InputChip", "SuggestionChip", "ActionChip", "SegmentedButton",
    )

    private val forbiddenGestures = listOf(
        ".clickable", ".combinedClickable", ".toggleable", ".selectable", ".pointerInput",
    )

    /** One double-quoted literal on one line - which is how every string in this card is written. */
    private val literal = Regex("\"[^\"\n]*\"")

    private val verifyStem = "verif"
    private val renewStem = "renew"

    /** The index just past the closing quote of the literal that starts at [start]. */
    private fun literalEnd(
        source: String,
        start: Int,
    ): Int {
        if (source.startsWith("\"\"\"", start)) {
            var index = start + 3
            while (index < source.length && !source.startsWith("\"\"\"", index)) index++
            return (index + 3).coerceAtMost(source.length)
        }
        var index = start + 1
        while (index < source.length && source[index] != '"' && source[index] != '\n') {
            if (source[index] == '\\') index++
            index++
        }
        return (index + 1).coerceAtMost(source.length)
    }

    /**
     * [source] with comments blanked - and literals blanked too when [blankLiterals]. Same length
     * either way, newlines kept, so an index into the result is an index into the original. Both
     * readings are needed: braces and controls are counted with the literals gone, and the wording a
     * user would read can only be found with them in place.
     */
    private fun masked(
        source: String,
        blankLiterals: Boolean,
    ): String {
        val out = source.toCharArray()

        fun blank(from: Int, to: Int) {
            for (index in from until to.coerceAtMost(out.size)) {
                if (out[index] != '\n' && out[index] != '\r') out[index] = ' '
            }
        }

        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    var end = index + 2
                    while (end < source.length && source[end] != '\n') end++
                    blank(index, end)
                    index = end
                }

                source.startsWith("/*", index) -> {
                    var end = index + 2
                    while (end < source.length && !source.startsWith("*/", end)) end++
                    val after = (end + 2).coerceAtMost(source.length)
                    blank(index, after)
                    index = after
                }

                // Stepped over whole, so a `//` inside a literal is never read as a comment.
                source[index] == '"' -> {
                    val after = literalEnd(source, index)
                    if (blankLiterals) blank(index, after)
                    index = after
                }

                else -> index++
            }
        }
        return String(out)
    }

    /**
     * Everything wrong with where a row's menu is anchored. Empty means the menu opens at its button.
     *
     * Compose anchors a `DropdownMenu` to its parent layout node, so a button and a menu that are
     * siblings of a full-width `Row` anchor to the row - and the menu then opens against the far edge
     * of the screen, nowhere near the button that was tapped (that is measured, not hypothetical: the
     * menu opened at x=64..564 while its button sat at x=1200..1360). One `Box` around the two is what
     * makes the button the anchor, and that is what this reads.
     */
    private fun anchorViolations(
        code: String,
        tag: String,
        what: String,
    ): List<String> {
        val menu = code.indexOf("DropdownMenu")
        val button = if (menu < 0) -1 else code.lastIndexOf("IconButton", menu)
        // Without both the pair, it is the missing-control rules that have something to say.
        if (menu < 0 || button < 0) return emptyList()
        val open = boxOpeningBefore(code, button)
        val closes = open?.let { matchingBrace(code, it) }
        if (closes != null && closes >= menu) return emptyList()
        return listOf(
            "[$tag-anchor] $what draws its menu outside the `Box` that holds its overflow, so the " +
                "menu is anchored to the row instead of to the button and opens against the far " +
                "edge of the screen. Put the button and its menu in one `Box`.",
        )
    }

    /** The `{` of the innermost `Box` block that opens before [before], or null when none does. */
    private fun boxOpeningBefore(
        code: String,
        before: Int,
    ): Int? {
        val box = Regex("(?<![A-Za-z0-9_])Box(?![A-Za-z0-9_])")
        var last: Int? = null
        box.findAll(code).forEach { match ->
            if (match.range.first < before) {
                val open = code.indexOf('{', match.range.first)
                if (open > match.range.first) last = open
            }
        }
        return last
    }

    /** The index of the `}` that closes the `{` at [open], or null when the braces never balance. */
    private fun matchingBrace(
        code: String,
        open: Int,
    ): Int? {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    /** The row loop's body, or null when the loop is not there. */
    private fun rowLoop(code: String): IntRange? {
        val start = code.indexOf(rowAnchor)
        if (start < 0) return null
        val open = code.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return (open + 1) until index
                }
            }
        }
        return null
    }

    /** [token] as a whole word, so `Button` is not found in `TextButton`. */
    private fun count(
        code: String,
        token: String,
    ): Int = Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])").findAll(code).count()

    private fun strings(text: String): List<String> = literal.findAll(text).map { it.value }.toList()

    /** Everything wrong with one card source. Empty means one Verify for the batch, one overflow a row. */
    private fun violations(source: String): List<String> {
        val code = masked(source, blankLiterals = true)
        val words = masked(source, blankLiterals = false)
        val loop =
            rowLoop(code) ?: return listOf(
                "[row-loop-missing] `$rowAnchor` was not found, so the guard cannot read the rows it " +
                    "exists to police. If the loop moved or was renamed, move the anchor with it " +
                    "deliberately - this card has already shipped a per-row Verify button once.",
            )

        val found = mutableListOf<String>()
        val rowCode = code.substring(loop.first, loop.last + 1)
        val rowWords = words.substring(loop.first, loop.last + 1)

        forbiddenControls.forEach { control ->
            val seen = count(rowCode, control)
            if (seen > 0) {
                found += "[row-control] a per-source row carries $seen " +
                    (if (seen == 1) control else "${control}s") +
                    ". The row's job is to say where its session stands; the card's Verify is the " +
                    "batch, and one source's check lives behind the row's overflow."
            }
        }
        forbiddenGestures.forEach { gesture ->
            if (rowCode.contains(gesture)) {
                found += "[row-gesture] a per-source row makes something tappable with `$gesture`. " +
                    "A row's only tap target is its overflow - a clickable \"Verify\" label is a " +
                    "per-row Verify control wearing no button."
            }
        }
        val overflows = count(rowCode, "IconButton")
        if (overflows != 1) {
            found += "[row-overflow] a per-source row has $overflows overflow buttons; exactly one " +
                "is allowed."
        }
        val clicks = count(rowCode, "onClick")
        if (clicks > maxRowClicks) {
            found += "[row-clicks] a per-source row has $clicks tappable callbacks; it offers " +
                "exactly $maxRowClicks (the overflow and the single item behind it)."
        }
        if (!rowCode.contains(overflowIcon)) {
            found += "[row-overflow-missing] a per-source row no longer draws its overflow " +
                "($overflowIcon), so the guard cannot confirm the row offers exactly one control."
        }
        found += anchorViolations(rowCode, "row", "a per-source row")
        val items = count(rowCode, "DropdownMenuItem")
        if (items != 1) {
            found += "[row-menu] a per-source row has $items dropdown items; exactly one is " +
                "allowed - the single-source check."
        }
        val wording = strings(rowWords).filter { it.contains(verifyStem, ignoreCase = true) }
        when {
            wording.size > 1 -> found += "[row-wording] a per-source row carries ${wording.size} " +
                "verify labels (${wording.joinToString()}); re-checking one source is one item in " +
                "one overflow, not a control of its own."

            wording.size == 1 -> {
                val labelAt = rowWords.indexOf(wording.first())
                val itemAt = rowCode.indexOf("DropdownMenuItem")
                if (itemAt < 0 || labelAt < itemAt) {
                    found += "[row-wording] the row's verify label ${wording.first()} is drawn before " +
                        "its dropdown item, so it is not inside the overflow - it is a control on the row."
                }
            }
        }

        val cardStart = source.indexOf(cardAnchor)
        if (cardStart < 0) {
            found += "[card-missing] `$cardAnchor` was not found, so the batch controls could not " +
                "be read. Move the anchor with the title if it was reworded."
            return found
        }
        val batchCode = code.substring(cardStart, loop.first)
        val batchWords = words.substring(cardStart, loop.first)
        val batchVerifies = count(batchCode, "verifyAllSources")
        if (batchVerifies != 1) {
            found += "[card-verify-all] the card raises $batchVerifies batch checks; there is one " +
                "Verify, for the sources that need a check."
        }
        val renews = count(batchCode, "renewSessions")
        if (renews != 1) {
            found += "[card-renew] the card has $renews renew actions; there is one Renew, kept " +
                "apart from Verify because the two jobs have different outcomes."
        }
        strings(batchWords)
            .filter { it.contains(verifyStem, ignoreCase = true) && it.contains(renewStem, ignoreCase = true) }
            .forEach { merged ->
                found += "[card-merged] $merged is one control doing two jobs. It was removed because " +
                    "the line above it counts sources needing a check while the same button offered " +
                    "to renew them, and it hid which half had run."
            }

        return found
    }

    /**
     * The index just past the `)` that closes the parameter list declared at [anchor].
     *
     * The body is what follows it, and it has to be found this way rather than by taking the next
     * `{`: a signature's own default values carry braces (`onUpdate: () -> Unit = {}`), so the first
     * brace after the name belongs to a default, not to the function.
     */
    private fun signatureEnd(
        code: String,
        anchor: String,
    ): Int? {
        val start = code.indexOf(anchor)
        if (start < 0) return null
        val open = code.indexOf('(', start)
        if (open < 0) return null
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        }
        return null
    }

    /** The body of the composable declared at [anchor] - everything between its outermost braces. */
    private fun functionBody(
        code: String,
        anchor: String,
    ): IntRange? {
        val after = signatureEnd(code, anchor) ?: return null
        val open = code.indexOf('{', after)
        if (open < 0) return null
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return (open + 1) until index
                }
            }
        }
        return null
    }

    /**
     * Everything wrong with one row that is supposed to carry its actions behind an overflow. Empty
     * means one overflow, no loose action, and every action still reachable.
     *
     * [required] is the part that keeps the fix honest: a row that stopped offering an action would
     * be a worse row than the one this guard was written for, so an action that disappears is
     * reported as loudly as a control that comes back.
     */
    private fun actionRowViolations(
        source: String,
        anchor: String,
        what: String,
        tag: String,
        minMenuItems: Int,
        required: List<String>,
        switches: Int,
    ): List<String> {
        val code = masked(source, blankLiterals = true)
        val body =
            functionBody(code, anchor) ?: return listOf(
                "[$tag-missing] `$anchor` was not found, so the guard cannot read $what. If the " +
                    "composable moved or was renamed, move the anchor with it deliberately.",
            )

        val rowCode = code.substring(body.first, body.last + 1)
        val found = mutableListOf<String>()

        forbiddenControls.forEach { control ->
            val seen = count(rowCode, control)
            if (seen > 0) {
                found += "[$tag-control] $what carries $seen " +
                    (if (seen == 1) control else "${control}s") +
                    ". What it can *do* lives in one overflow; the row itself states what its subject " +
                    "is and where it stands."
            }
        }
        forbiddenGestures.forEach { gesture ->
            if (rowCode.contains(gesture)) {
                found += "[$tag-gesture] $what makes something tappable with `$gesture`; its only tap " +
                    "targets are the controls that are meant to be there."
            }
        }
        val overflows = count(rowCode, "IconButton")
        if (overflows != 1) {
            found += "[$tag-overflow] $what has $overflows overflow buttons; exactly one is allowed."
        }
        if (!rowCode.contains(overflowIcon)) {
            found += "[$tag-overflow-missing] $what no longer draws its overflow ($overflowIcon), so " +
                "the guard cannot confirm it offers one."
        }
        found += anchorViolations(rowCode, tag, what)
        val switchesSeen = count(rowCode, "Switch")
        if (switchesSeen != switches) {
            found += "[$tag-switch] $what has $switchesSeen switches; $switches is the shape of this " +
                "row - what stays in the open is its state, not its actions."
        }
        val items = count(rowCode, "DropdownMenuItem")
        if (items < minMenuItems) {
            found += "[$tag-menu] $what offers $items menu items; the actions belong in its overflow " +
                "- taking a control away is not the fix."
        }
        required.forEach { action ->
            if (!rowCode.contains(action)) {
                found += "[$tag-menu] $what no longer reaches `$action`; its actions belong in the " +
                    "overflow, not in the bin."
            }
        }

        return found
    }

    /** The extension source rows, as [actionRowViolations] sees them. */
    private fun sourceRowViolations(source: String): List<String> =
        actionRowViolations(
            source = source,
            anchor = sourceRowAnchor,
            what = "an extension source row",
            tag = "source-row",
            minMenuItems = 3,
            required = listOf("onTest", "onMoveUp", "onMoveDown"),
            switches = 1,
        )

    /** The playback priority rows, as [actionRowViolations] sees them. */
    private fun priorityRowViolations(source: String): List<String> =
        actionRowViolations(
            source = source,
            anchor = priorityRowAnchor,
            what = "a playback priority row",
            tag = "priority-row",
            minMenuItems = 2,
            required = listOf("onMoveUp", "onMoveDown"),
            switches = 0,
        )

    /** A priority row as it is declared, with [body] where its controls go. */
    private fun priorityRowFixture(body: String): String =
        """
        private fun PriorityEngineRow(
            position: Int,
            name: String,
            canMoveUp: Boolean,
            canMoveDown: Boolean,
            onMoveUp: () -> Unit,
            onMoveDown: () -> Unit,
        ) {
        $body
        }
        """.trimIndent()

    /** A source row as it is declared, with [body] where its controls go. */
    private fun sourceRowFixture(body: String): String =
        """
        private fun SourceRow(
            sourceWithState: SourceWithState,
            canMoveUp: Boolean,
            canMoveDown: Boolean,
            onToggleEnabled: (Boolean) -> Unit,
            onMoveUp: () -> Unit,
            onMoveDown: () -> Unit,
            onTest: () -> Unit,
            onUpdate: () -> Unit = {},
        ) {
        $body
        }
        """.trimIndent()

    /**
     * Runs the detector over the rows as they were, so its rules are proven on every assemble rather
     * than only on the day they were written.
     */
    private fun selfCheck(): List<String> {
        val problems = mutableListOf<String>()

        // Each shape the regression can come back in, and the tag that has to answer for it.
        fun mustTrip(
            what: String,
            source: String,
            tag: String,
            check: (String) -> List<String> = ::violations,
        ) {
            if (check(source).none { it.startsWith(tag) }) {
                problems +=
                    "[guard-self-check] $what no longer trips the guard, so the guard would not catch " +
                        "it coming back."
            }
        }

        fun mustPass(
            what: String,
            source: String,
            check: (String) -> List<String> = ::violations,
        ) {
            val reported = check(source)
            if (reported.isNotEmpty()) {
                problems +=
                    "[guard-self-check] $what is reported as a violation: ${reported.joinToString()}"
            }
        }

        mustTrip(
            "the per-row Verify button this card shipped before the change",
            card(
                """
                if (sessionRow.needsCheck) {
                    androidx.compose.material3.TextButton(
                        onClick = { startVerification(source.id) },
                    ) {
                        Text(if (verified) "Re-verify" else "Verify")
                    }
                }
                """.trimIndent(),
            ),
            "[row-control]",
        )
        mustTrip(
            "a clickable \"Verify\" label on a row",
            card("Text(text = \"Verify\", modifier = Modifier.clickable { startVerification(source.id) })"),
            "[row-gesture]",
        )
        mustTrip(
            "a row whose one control was taken away",
            card("Column { Text(text = source.displayName) }"),
            "[row-overflow-missing]",
        )
        mustTrip(
            "one control doing both jobs",
            card(shippedRow, batch = mergedBatch),
            "[card-merged]",
        )
        mustTrip(
            "a row loop that cannot be read",
            "Text(text = \"$cardAnchor\", style = MaterialTheme.typography.titleSmall)",
            "[row-loop-missing]",
        )

        mustPass("the row as it is drawn now", card(shippedRow))
        mustPass(
            "a comment that mentions Verify",
            card("// This row says \"Verify\" but draws nothing of the sort.\n" + shippedRow),
        )

        mustTrip(
            "the loose Test button and arrows this source row shipped before the change",
            sourceRowFixture(
                """
                androidx.compose.material3.TextButton(onClick = onTest) {
                    Text("Test")
                }
                Switch(checked = sourceWithState.enabled, onCheckedChange = onToggleEnabled)
                androidx.compose.foundation.layout.Column {
                    if (canMoveUp) {
                        androidx.compose.material3.IconButton(onClick = onMoveUp) { Text("▲") }
                    }
                    if (canMoveDown) {
                        androidx.compose.material3.IconButton(onClick = onMoveDown) { Text("▼") }
                    }
                }
                """.trimIndent(),
            ),
            "[source-row-control]",
            ::sourceRowViolations,
        )
        mustTrip(
            "an extension source row whose overflow was taken away",
            sourceRowFixture(
                """
                Switch(checked = sourceWithState.enabled, onCheckedChange = onToggleEnabled)
                """.trimIndent(),
            ),
            "[source-row-overflow-missing]",
            ::sourceRowViolations,
        )
        // The bug this rule was written for: the menu's parent was the row, not the button, so it
        // opened against the far edge of the screen - x=64..564 while its button sat at 1200..1360.
        mustTrip(
            "the button and its menu as siblings of the row, which anchored the menu to the row",
            sourceRowFixture(
                """
                Switch(checked = sourceWithState.enabled, onCheckedChange = onToggleEnabled)
                IconButton(onClick = { rowMenuOpen = true }) {
                    Icon(imageVector = Icons.Rounded.$overflowIcon, contentDescription = "More options")
                }
                DropdownMenu(expanded = rowMenuOpen, onDismissRequest = { rowMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("Test this source") }, onClick = onTest)
                    DropdownMenuItem(text = { Text("Move up") }, enabled = canMoveUp, onClick = onMoveUp)
                    DropdownMenuItem(text = { Text("Move down") }, enabled = canMoveDown, onClick = onMoveDown)
                }
                """.trimIndent(),
            ),
            "[source-row-anchor]",
            ::sourceRowViolations,
        )
        mustPass(
            "a source row whose button and menu share one `Box`",
            sourceRowFixture(
                """
                Switch(checked = sourceWithState.enabled, onCheckedChange = onToggleEnabled)
                Box {
                    IconButton(onClick = { rowMenuOpen = true }) {
                        Icon(imageVector = Icons.Rounded.$overflowIcon, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = rowMenuOpen, onDismissRequest = { rowMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("Test this source") }, onClick = onTest)
                        DropdownMenuItem(text = { Text("Move up") }, enabled = canMoveUp, onClick = onMoveUp)
                        DropdownMenuItem(text = { Text("Move down") }, enabled = canMoveDown, onClick = onMoveDown)
                    }
                }
                """.trimIndent(),
            ),
            ::sourceRowViolations,
        )
        mustTrip(
            "the arrow pair this priority row shipped before the change",
            priorityRowFixture(
                """
                Text(text = name, style = MaterialTheme.typography.bodyLarge)
                if (canMoveUp) {
                    androidx.compose.material3.IconButton(onClick = onMoveUp) {
                        Text("▲", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (canMoveDown) {
                    androidx.compose.material3.IconButton(onClick = onMoveDown) {
                        Text("▼", style = MaterialTheme.typography.bodySmall)
                    }
                }
                """.trimIndent(),
            ),
            "[priority-row-overflow]",
            ::priorityRowViolations,
        )
        mustTrip(
            "a priority row whose overflow was taken away",
            priorityRowFixture("Text(text = name, style = MaterialTheme.typography.bodyLarge)"),
            "[priority-row-overflow-missing]",
            ::priorityRowViolations,
        )
        return problems
    }

    override fun execute(task: Task) {
        val contents = sources.map { file -> file to file.readText() }
        val problems = mutableListOf<String>()
        problems += scan(contents, rowAnchor, "the verification card's rows", ::violations)
        problems += scan(contents, sourceRowAnchor, "the extension source rows", ::sourceRowViolations)
        problems += scan(contents, priorityRowAnchor, "the playback priority rows", ::priorityRowViolations)
        problems += selfCheck()

        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("A SpotiFLAC settings row may not offer a loose action next to its one overflow:")
                    problems.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine(
                        "The verification card's rows state a session and carry one overflow; the " +
                            "extension rows carry their verdict, their switch and one overflow.",
                    )
                    append("Put an action behind the overflow rather than in the row, and leave the row's state in its text.")
                },
            )
        }
    }

    /** Runs [check] over every source that contains [anchor], and fails when nothing does. */
    private fun scan(
        contents: List<Pair<File, String>>,
        anchor: String,
        what: String,
        check: (String) -> List<String>,
    ): List<String> {
        val matching = contents.filter { (_, source) -> source.contains(anchor) }
        val problems = mutableListOf<String>()
        if (matching.isEmpty()) {
            problems +=
                "no source under src/main/kotlin contains `$anchor` ($what), so the guard cannot read " +
                    "what it exists to police."
        }
        if (matching.size > 1) {
            problems +=
                "more than one source contains `$anchor` ($what): " +
                    matching.joinToString { (file, _) -> file.relativeToOrSelf(root).path }
        }
        matching.forEach { (file, source) ->
            check(source).forEach { problems += "${file.relativeToOrSelf(root).path}: $it" }
        }
        return problems
    }

    /** A card skeleton with the two batch controls, so the batch rules have something to read. */
    private fun card(
        rows: String,
        batch: String = shippedBatch,
    ): String =
        """
        Text(text = "$cardAnchor", style = MaterialTheme.typography.titleSmall)
        Text(text = "Each source keeps its own gateway session.")
        $batch
        $rowAnchor { (source, sessionRow) ->
        $rows
        }
        """.trimIndent()

    /** The two batch controls as they are drawn now. */
    private val shippedBatch =
        """
        Row {
            Text(text = summary)
            TextButton(onClick = { renewSessions() }) { Text("Renew") }
            TextButton(onClick = { verifyAllSources(needing) }) { Text("Verify") }
        }
        """.trimIndent()

    /** The merged control that was removed: one button renewing first and then raising the checks. */
    private val mergedBatch =
        """
        TextButton(onClick = { fixAllSessions() }) {
            Text(if (isRenewingSessions) "Working…" else "Verify & renew all")
        }
        """.trimIndent()

    /** The row as it is drawn now: status only, plus one overflow hiding one check. */
    private val shippedRow =
        """
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = source.displayName)
                Text(text = sessionRow.text)
            }
            if (sessionRow.checkable) {
                var rowMenuOpen by remember(source.id) { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { rowMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.Rounded.$overflowIcon,
                            contentDescription = "More options for ${'$'}{source.displayName}",
                        )
                    }
                    DropdownMenu(expanded = rowMenuOpen, onDismissRequest = { rowMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Verify this source") },
                            onClick = {
                                rowMenuOpen = false
                                verifySource(source.id)
                            },
                        )
                    }
                }
            }
        }
        """.trimIndent()
}

/**
 * Rejects a loose control in the SpotiFLAC settings rows.
 *
 * Measured on the device: the verification card showed a "Verify"/"Re-verify" on every row that
 * needed a check, a merged "Verify & renew all" above them, and a Verify on the engine status line -
 * three controls for one job, one of them promising work that a healthy row had already done. The
 * extension source rows showed "Test", a column of ▲/▼, and an "Update" under the description - so
 * the row's one piece of state, its Switch, sat in a line of five actions and read as the sixth.
 *
 * Both now state their condition in text and offer one overflow. The card's rows take their check
 * from the batch above them, from the same state in the same order, and the source rows take theirs
 * from the overflow that also reorders them.
 */
val verifyRowControls =
    tasks.register("verifyRowControls") {
        description =
            "Fails when a SpotiFLAC settings row offers a loose action instead of its one overflow"
        group = "verification"

        val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
        inputs
            .files(sources)
            .withPropertyName("rowControlSources")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)

        doLast(rowControlVerifier(sources.files.sorted(), projectDir))
    }

// Every assemble, so the guard runs on the debug builds used locally and on the release
// builds CI signs - the same reach the NewApi lint guard has.
tasks.matching { it.name.startsWith("assemble") }.configureEach {
    dependsOn(verifyDownloadNaming)
    dependsOn(verifyMarqueeRouting)
    dependsOn(verifyRowControls)
}
