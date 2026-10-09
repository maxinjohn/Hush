/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * Macrobenchmark module: generates the app's baseline profile and measures it.
 *
 * This module holds no app code. It is a `com.android.test` module that drives the real
 * application on a device through [BaselineProfileGenerator], which is what produces
 * `app/src/main/baseline-prof.txt`; [StartupBenchmark] then measures the same build with the
 * profile required and with it absent, so the difference it makes is visible in one run.
 *
 * The flavours below mirror `:app` exactly. A test module has to resolve the same variant of its
 * target as the one it was built for, and `:app` declares three flavour dimensions, so the same
 * three dimensions and the same values have to exist here or the plugin cannot pair a
 * `fossMobileUniversal` benchmark build with the app it measures.
 */
plugins {
    // No version: the Android Gradle plugin is already on this build's classpath, put there by the
    // root project, and there is no versioned marker for `com.android.test` to resolve.
    id("com.android.test")
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "app.hush.music.benchmark"
    compileSdk = 37

    defaultConfig {
        // Matches `:app`.
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The module under test. Everything here runs against :app's benchmark variant.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    flavorDimensions += listOf("distribution", "device", "abi")
    productFlavors {
        create("gms") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
        create("mobile") { dimension = "device" }
        create("tv") { dimension = "device" }
        create("universal") { dimension = "abi" }
        create("arm64") { dimension = "abi" }
        create("armeabi") { dimension = "abi" }
        create("x86") { dimension = "abi" }
        create("x86_64") { dimension = "abi" }
    }

    buildTypes {
        // The profile generator and the benchmarks run against the non-debuggable benchmark
        // variant of :app, so they need a matching build type here. (AGP makes a test module's own
        // APK debuggable whatever this says, which is fine: what a benchmark run needs is the
        // `androidx.benchmark.enabledRules` argument, see [StartupBudget].)
        create("benchmark") {
            isDebuggable = false
            // Which profile requirement a run uses is chosen per test in [StartupBenchmark] and
            // [StartupBudget].
            matchingFallbacks += listOf("release", "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit)
}
