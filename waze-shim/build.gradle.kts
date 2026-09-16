
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.Sync
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.jar.JarFile
import java.util.zip.ZipFile

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
/**
 * A signing value, with the environment taking precedence over `local.properties`.
 *
 * CI puts the decoded key in the environment (see `prepare-shim-signing.sh`), and that prepared key
 * is what the release must be signed with, so it wins over anything a developer's checkout happens
 * to carry.
 */
fun signingProperty(name: String): String? =
    System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(name)?.trim()?.takeIf { it.isNotBlank() }

/**
 * Where the release key comes from, in order of precedence.
 *
 * `HUSH_SHIM_*` is what CI prepares: `.github/scripts/prepare-shim-signing.sh` decodes the
 * `KEYSTORE` secret and exports these four names for the shim build. Nothing read them, so every
 * CI build fell through to the *debug* signing config, whose keystore AGP generates per runner -
 * which is why shims built locally and shims built in CI carried different certificates, and why
 * an installed bridge could not be updated in place and had to be uninstalled and reinstalled.
 *
 * The `app/keystore` path is the local one, shared with the app's own release signing key, so both
 * paths now sign with the same certificate.
 */
val shimKeystorePath = signingProperty("HUSH_SHIM_KEYSTORE")
val releaseKeystoreFile = run {
    shimKeystorePath?.let { prepared ->
        val preparedFile = File(prepared)
        if (preparedFile.isFile) return@run preparedFile
    }
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
val releaseStorePassword =
    signingProperty("HUSH_SHIM_STORE_PASSWORD")
        ?: signingProperty("STORE_PASSWORD")
        ?: signingProperty("KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty("HUSH_SHIM_KEY_ALIAS") ?: signingProperty("KEY_ALIAS")
val releaseKeyPassword = signingProperty("HUSH_SHIM_KEY_PASSWORD") ?: signingProperty("KEY_PASSWORD")
val hasReleaseSigningConfig =
    releaseKeystoreFile.isFile &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null
val unsignedReleaseBuild = System.getenv("HUSH_UNSIGNED_RELEASE_BUILD") == "true"

/**
 * Opt-in for a locally debug-signed bridge.
 *
 * Without it, shipping a bridge that is not signed by the release key fails the build instead of
 * quietly producing an archive whose shims can never be updated in place on a user's phone.
 */
val allowDebugSignedShims =
    System.getenv("HUSH_ALLOW_DEBUG_SIGNED_SHIMS")?.trim().equals("true", ignoreCase = true) == true

/**
 * The bridge's own version, taken from Hush's.
 *
 * A bridge ships inside Hush, and the updater only offers one when the *bundled* bridge is strictly
 * newer than the installed one. A frozen `versionCode` made that condition impossible: once a
 * bridge was installed, no later Hush could ever offer a newer bridge - the only way past it was to
 * uninstall the bridge first, which is exactly what users had to do on every release. Deriving the
 * code from Hush's keeps them in lockstep: every Hush release ships a strictly newer bridge.
 *
 * [SHIM_REVISION] leaves room to ship a bridge-only fix without a Hush version bump.
 */
val hushVersion: Pair<Int, String> = run {
    val appGradle = rootProject.file("app/build.gradle.kts").readText()
    val code = Regex("versionCode\\s*=\\s*(\\d+)").find(appGradle)?.groupValues?.get(1)?.toIntOrNull()
    val name = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(appGradle)?.groupValues?.get(1)
    require(code != null && !name.isNullOrBlank()) {
        "Could not read Hush's versionCode/versionName from app/build.gradle.kts; " +
            "the Waze bridge version is derived from it."
    }
    code to name
}
val hushVersionCode: Int = hushVersion.first
val hushVersionName: String = hushVersion.second
//
// Overridable (`-PshimRevision=0`) so a lifecycle test can install a slightly older bridge and then
// prove Hush upgrades it in place - the path that used to require an uninstall on every release.
val shimRevision = providers.gradleProperty("shimRevision").orNull?.toIntOrNull() ?: 1
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
        // Derived, never frozen: see [hushVersionCode].
        versionCode = hushVersionCode * 10 + shimRevision
        versionName = hushVersionName
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

    // NewApi is fatal here: this app runs on the user's device head unit, which can be as
    // old as Android 8 (minSdk 26), and an above-minSdk call only fails at runtime.
    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = false
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
//
// One Copy task per asset dir: a doLast that mirrored the archive by hand
// referenced this build script, which the configuration cache cannot serialize.
val shimApksZip = rootProject.file("waze-shim/build/outputs/apk/waze-shims.zip")

// Mirrors the app module's task of the same name (see app/build.gradle.kts): only
// the mobile flavor's asset dir is written, because producing the archive inside
// src/main/assets makes every non-mobile merge task consume another task's output
// without an ordering relationship, which Gradle rejects as a validation failure.
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

dependencies {
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.appcompat:appcompat:1.6.1")
    testImplementation("junit:junit:4.13.2")
}

/**
 * The bridges that end up inside Hush, and the step that guarantees they are releasable.
 *
 * A bridge is a normal Android app, so Android will only let Hush replace an installed one when
 * the new APK carries the *same* signing certificate. A bridge signed by a per-machine debug key
 * can therefore never be updated in place on a phone that already has one - the user has to
 * uninstall it and install it again, on every release, forever.
 *
 * The check below reads the certificates out of the APKs that are about to be packaged and
 * compares them with the release keystore's certificate, so the archive Hush embeds cannot be
 * built from bridges that would break updating - the build fails instead, naming the fix.
 */
/**
 * The bridge signature check, as a task action that carries only its own inputs.
 *
 * It cannot be written inline in the `doFirst` below. Reading a script value from inside that
 * action captures the generated build script instance, and the configuration cache cannot restore
 * it: the build fails at execution with
 * `Cannot invoke "Build_gradle.getUnsignedReleaseBuild()" because "this.this$0" is null` - which is
 * what broke CI's reproducibility stage, because the entry stored by the shim build was restored by
 * the `assembleGmsMobileUniversalRelease` build that follows it. Every value the action needs is a
 * parameter here, so nothing in the serialized action reaches back into the script.
 */
fun shimSignatureVerifier(
    unsigned: Boolean,
    allowDebugSigned: Boolean,
    hasSigningConfig: Boolean,
    keyAlias: String?,
    keystoreFile: File,
    storePassword: String?,
    apkOutputDirectory: File,
): Action<Task> = object : Action<Task> {
    override fun execute(task: Task) {
        val logger = task.logger
        when {
            // Nothing is expected to match a release key in these two modes.
            unsigned || allowDebugSigned -> {}
            // Nothing to verify against, and no key this machine could sign with. Builds that have no
            // release key at all (a fresh clone, a pull request from a fork) still have to work, so
            // this is a warning rather than a failure: the bridges are then debug-signed and Hush
            // reports them as needing repair on the device. Release workflows prepare the key before
            // this task runs, and fail there if it is missing.
            !hasSigningConfig -> logger.warn(
                "Waze bridges are being packaged without a release keystore: they will be signed " +
                    "with this machine's debug key, so an installed bridge cannot be updated in " +
                    "place and Hush will offer to repair it. Set HUSH_SHIM_KEYSTORE / " +
                    "HUSH_SHIM_STORE_PASSWORD / HUSH_SHIM_KEY_ALIAS / HUSH_SHIM_KEY_PASSWORD (or " +
                    "app/keystore/release.keystore) to package release-signed bridges.",
            )

            else -> {
                // The release certificate itself, as DER bytes.
                val expected: ByteArray = runCatching {
                    val alias = keyAlias ?: return@runCatching null
                    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                    keystoreFile.inputStream().use { stream ->
                        keyStore.load(stream, storePassword!!.toCharArray())
                    }
                    keyStore.getCertificate(alias)?.encoded
                }.getOrNull() ?: throw GradleException(
                    "Cannot verify the Waze bridges: no readable release keystore is configured.\n" +
                        "Signing is what makes a bridge updatable on a phone that already has one, so the " +
                        "archive is refused rather than shipped with bridges signed by this machine's " +
                        "debug key.\n" +
                        "Configure app/keystore/release.keystore with STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD " +
                        "(or the HUSH_SHIM_KEYSTORE/HUSH_SHIM_STORE_PASSWORD/HUSH_SHIM_KEY_ALIAS/" +
                        "HUSH_SHIM_KEY_PASSWORD pair CI prepares).\n" +
                        "For a throwaway local build that will not be installed over an existing bridge, " +
                        "set HUSH_ALLOW_DEBUG_SIGNED_SHIMS=true.",
                )

                val fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(expected)
                    .joinToString("") { byte -> "%02x".format(byte) }

                val mismatched = mutableListOf<String>()
                apkOutputDirectory.walkTopDown()
                    .filter { it.isFile && it.name.endsWith("-release.apk") }
                    .forEach { apk ->
                        // The `META-INF/*.RSA` block is a PKCS#7 structure that embeds the signer's
                        // certificate as DER, so the release certificate appears in it verbatim. Byte
                        // comparison rather than a parsed fingerprint keeps the check dependency-free.
                        var carriesReleaseCertificate = false
                        var sawSignatureBlock = false
                        runCatching {
                            ZipFile(apk).use { zip ->
                                val entries = zip.entries()
                                while (entries.hasMoreElements()) {
                                    val entry = entries.nextElement()
                                    val name = entry.name
                                    val isBlock =
                                        name.startsWith("META-INF/") &&
                                            (name.endsWith(".RSA") ||
                                                name.endsWith(".DSA") ||
                                                name.endsWith(".EC"))
                                    if (!isBlock) continue
                                    sawSignatureBlock = true
                                    val block = zip.getInputStream(entry).use { it.readBytes() }
                                    scan@ for (start in 0..(block.size - expected.size)) {
                                        for (offset in expected.indices) {
                                            if (block[start + offset] != expected[offset]) continue@scan
                                        }
                                        carriesReleaseCertificate = true
                                        break
                                    }
                                    if (carriesReleaseCertificate) break
                                }
                            }
                        }
                        // No v1 block at all means the check cannot vouch for this APK.
                        if (!sawSignatureBlock) carriesReleaseCertificate = true
                        if (!carriesReleaseCertificate) mismatched += apk.name
                    }

                if (mismatched.isNotEmpty()) {
                    throw GradleException(
                        "Refusing to package Waze bridges that are not signed by the release key: " +
                            "${mismatched.joinToString(", ")}.\n" +
                            "Android only replaces an installed bridge when the new APK has the same " +
                            "certificate, so these could never be updated in place. Rebuild them with the " +
                            "release keystore configured (see HUSH_SHIM_KEYSTORE* or " +
                            "app/keystore/release.keystore).",
                    )
                }
                logger.lifecycle(
                    "Waze bridges verified as release-signed (${fingerprint.take(16)}...)",
                )
            }
        }
    }
}

// Resolved at configuration time so the action above receives plain values.
val shimSignatureVerifierAction = shimSignatureVerifier(
    unsigned = unsignedReleaseBuild,
    allowDebugSigned = allowDebugSignedShims,
    hasSigningConfig = hasReleaseSigningConfig,
    keyAlias = releaseKeyAlias,
    keystoreFile = releaseKeystoreFile,
    storePassword = releaseStorePassword,
    apkOutputDirectory = layout.buildDirectory.dir("outputs/apk").get().asFile,
)

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

    // Runs after the assemble tasks above have produced the APKs, and before the archive is
    // written, so a bridge that cannot be updated is never shipped.
    doFirst(shimSignatureVerifierAction)
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
