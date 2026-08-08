import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// Single source of truth for the app version. Scheme is MAJOR.MINOR.PATCH where PATCH runs 0→100,
// then MINOR rolls forward (…1.1.100 → 1.2.0…) all the way to 1.100.0 before MAJOR turns over to
// 2.0.0. versionCode is DERIVED from the name so it always increases monotonically with the version
// — that is exactly what the over-the-air updater compares (a higher published versionCode triggers
// the in-app update). To cut a release, bump `appVersionName` only; the code follows automatically.
val appVersionName = "1.1.19"
val appVersionCode = appVersionName.split(".").let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    // minor and patch are each capped at 100 by the scheme, so the 1_000-wide buckets never collide.
    major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "com.designprototype.workshop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.designprototype.workshop"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // Instrumented tests. There are none of the usual kind here and this is not the start of a
        // UI-test suite: the JVM tests cover the logic on purpose (see `testOptions` below). What
        // needs a handset is the handful of questions only a real speech service can answer — which
        // languages it will admit to being able to download, and what it says when asked in
        // different ways. Those answers cannot be reasoned out from the docs; they have to be
        // measured, and measured on the fleet's actual phone.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Default to the production backend through CloudFront over HTTPS. CloudFront is dual-stack
        // (publishes a native IPv6 / AAAA record), so it connects on IPv6-only mobile networks
        // (e.g. Jio/Airtel) where the IPv4-only EC2 origin — whether addressed by literal IP or its
        // AWS hostname — fails (no IPv4 route, and no AAAA to use). HTTPS also clears the web app's
        // mixed-content block. Emulator/local devs override this with
        // apiBaseUrl=http://10.0.2.2:8000/api/ in local.properties.
        val apiBaseUrl = localProperties.getProperty(
            "apiBaseUrl",
            "https://d2b34i3e92al6i.cloudfront.net/api/"
        )
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"614092441670-3e5k15srupq9mfpg3aktqfkjvkavu0g3.apps.googleusercontent.com\"")
        buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", "\"614092441670-5rckig6t1al6plbfll8irn9prcmp446t.apps.googleusercontent.com\"")
        buildConfigField("String", "MAPTILER_API_KEY", "\"OJJYFRqCD2HD2k3BbXGF\"")

        /**
         * NO `ndk { abiFilters }` HERE, AND THAT ABSENCE IS LOAD-BEARING — see `buildTypes.release`.
         *
         * THE DEFECT THIS COMMENT PREVENTS, because the merge of two lanes produced it once already:
         * the recogniser lane wrote the ARM filter into `defaultConfig` and the sizing lane wrote it
         * into `release` behind a `releaseAllAbis` escape hatch. Both survived a clean automatic
         * merge — different regions of the file, no textual conflict — and the combination is
         * silently WRONG in two ways.
         *
         *  1. AGP UNIONS the two sets; a build-type `abiFilters` cannot subtract from
         *     `defaultConfig`'s. So with `releaseAllAbis=true` the release block adds nothing and the
         *     `defaultConfig` pair still applies: the escape hatch stops widening anything, while
         *     still printing the lifecycle line that says it did — a flag that lies in the console.
         *
         *     MEASURED, on two real `:app:packageRelease` runs differing only in this block, with
         *     `releaseAllAbis=true` set in both and the ABIs read out of each APK's own central
         *     directory with `zipfile`:
         *
         *         both blocks present   ->  [arm64-v8a, armeabi-v7a]         26,211,648 bytes
         *         this block removed    ->  [arm64-v8a, armeabi-v7a,
         *                                    x86, x86_64]                    49,439,024 bytes
         *
         *     (That difference, 23,227,376 bytes, is also the filter's own saving, arrived at from
         *     the other direction than `docs/R8-MEASUREMENT.md` did and agreeing with it exactly.)
         *
         *     READ IT OFF THE PACKAGED APK AND NOWHERE EARLIER. `:app:mergeReleaseNativeLibs` and
         *     `:app:stripReleaseDebugSymbols` both emit all four ABIs whatever this block says —
         *     `abiFilters` is applied at PACKAGING time. An intermediate directory is not evidence
         *     here, and checking one is how this measurement was nearly got wrong.
         *  2. `defaultConfig` also narrows DEBUG, which takes away the x86_64 emulator — the only
         *     machine a contributor without a handset has, on a project whose CI runs no
         *     instrumented tests at all.
         */
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    /**
     * R8 on the RELEASE build, and the reason is the handset rather than the metric.
     *
     * There was no `buildTypes` block here at all, so shrinking was off for every build and the
     * whole dependency set — Compose, media3, ExoPlayer, Coil, Retrofit, OkHttp, Play Services
     * credentials — shipped whole, including every class no screen in this application ever
     * touches. This APK is installed over mobile data in a district town, onto whatever handset was
     * cheapest that year, and then carried into a village. Megabytes here are not a vanity figure:
     * they are the download that fails at one bar and the storage a designer has to clear to accept
     * an update.
     *
     * WHY THIS IS SAFE TO TURN ON, which is the question that kept it off until it was checked
     * rather than assumed. The libraries that are reached REFLECTIVELY — and therefore the ones R8
     * cannot see the use of — all ship their own consumer rules inside their artifacts, which R8
     * applies automatically. Verified by unzipping them out of the Gradle cache rather than trusted:
     * `kotlinx-serialization-core` carries `META-INF/proguard/kotlinx-serialization-common.pro`
     * (and the `META-INF/com.android.tools/proguard/` copy), `retrofit-2.11.0` carries
     * `META-INF/proguard/retrofit2.pro`, `okhttp-4.12.0` carries `META-INF/proguard/okhttp3.pro`.
     * `proguard-rules.pro` therefore holds only what is specific to this app: our own
     * `@Serializable` wire types and the two Retrofit interfaces reached through a dynamic proxy.
     *
     * WHAT IS STILL NOT PROVEN, stated because a shrunk build fails in a way that a green build
     * hides. R8's failure mode is not a compile error — it is a `SerializationException` or a
     * `NoSuchMethodError` at the first sync, on a build that installed and ran fine on a desk. This
     * machine has no device and no emulator (`adb` is not installed), so nothing here exercises the
     * shrunk APK at runtime. What IS checked is: the release build completes, the mapping is
     * emitted, and `r8-removed.txt` is read to confirm nothing load-bearing was dropped. Before this
     * ships, a release build must be run on real hardware through the offline loop — the same
     * hardware gap the handover already records against the offline claim itself.
     *
     * `isShrinkResources` needs `isMinifyEnabled`; enabling it alone is an error rather than a
     * smaller APK.
     */
    buildTypes {
        release {
            /**
             * OPT-IN DEBUG SIGNING, SO A SHRUNK BUILD CAN BE PUT ON A HANDSET WITHOUT MAKING AN
             * UNDISTRIBUTABLE APK THE DEFAULT.
             *
             * The problem it solves is real: an unsigned APK cannot be installed, and a shrunk build
             * that is never run on a device is exactly the thing that must not be trusted, because
             * R8's failure mode is not a compile error — it is a `SerializationException` or a
             * `NoSuchMethodError` at the first sync, on a build that assembled perfectly. Verifying
             * it requires a device, and a device requires a signature.
             *
             * THE DEBUG KEYSTORE SHIPS WITH EVERY ANDROID SDK ON EARTH, so an APK signed with it is
             * not distributable — anyone can produce an update for it. Wiring it in unconditionally
             * (which an earlier revision of this block did) means the first person to cut a release
             * from a clean checkout produces exactly that, and nothing in the build tells them.
             *
             * So it is now OFF unless a developer asks for it in their own gitignored
             * `local.properties`:
             *
             *     debugSignRelease=true
             *
             * With the flag absent — the state of a clean checkout and of CI — the release build is
             * unsigned, which fails loudly at install time rather than quietly at publish time.
             *
             * Before this app is published, add a real keystore whose credentials come from
             * `local.properties` or the CI secret store, never from a file in the repository.
             */
            if (localProperties.getProperty("debugSignRelease", "false").toBoolean()) {
                signingConfig = signingConfigs.getByName("debug")
                logger.lifecycle(
                    "release: signing with the DEBUG keystore (debugSignRelease=true). " +
                        "For on-device testing only — this APK is not distributable."
                )
            }
            /**
             * SHIP ONLY THE TWO ABIs A FIELD HANDSET ACTUALLY HAS.
             *
             * There was no ABI configuration in this file at all, and "no filter" means "package
             * every ABI every dependency offers" — four of them. That was 20,044 wasted bytes while
             * the only native code here was `libandroidx.graphics.path.so`. It stops being a rounding
             * error the moment the bundled ML Kit text recogniser lands, because its model is one
             * native library published four times over (bytes read out of the AAR, not estimated):
             *
             *     arm64-v8a   11,064,544      x86      11,561,048
             *     armeabi-v7a  6,781,940      x86_64   11,626,128
             *
             * x86 AND x86_64 ARE EMULATOR ARCHITECTURES. No handset this application is carried into
             * a village on runs one — the test device is a Galaxy M32, which is arm64 — so those two
             * rows are bytes shipped to devices that cannot be the target. Filtering them out is
             * MEASURED, by two real `assembleRelease` runs differing only in this block, at
             * **23,227,376 bytes — 22.15 MB — off the release APK** (49,307,952 → 26,080,576). See
             * `docs/R8-MEASUREMENT.md` for the full table and the breakdown of where the rest went.
             *
             * AND R8 CANNOT TOUCH ONE BYTE OF THEM. `minSdk = 26` makes AGP write
             * `extractNativeLibs="false"` into the merged manifest (read out of the manifest, not
             * recalled from the documentation), which requires native libraries to be STORED rather
             * than deflated — every `lib/` entry in the built APK reads STORED, and its size in the
             * APK equals its size on disk. R8 shrinks Java/Kotlin classes and has no opinion about a
             * `.so`. Filtering at package time is the only lever that exists.
             *
             * NOT AN ABI SPLIT AND NOT AN APP BUNDLE, because this application is side-loaded and
             * there is no store in the chain to pick a variant per device: `GET /api/app/download` is
             * one redirect to one object (`backend/app/api/routes/app_release.py`), the handset's own
             * updater fetches that same single file (`WorkshopRepository.downloadApk`), and the
             * prompt it answers has no "Later" (`MainActivity`, "required update: cannot be
             * dismissed"). A split would put whichever APK the publisher happened to hold on that one
             * URL, and every handset of the other ABI would answer INSTALL_FAILED_NO_MATCHING_ABIS to
             * a dialog it cannot dismiss. One universal APK that installs everywhere is the
             * requirement; this makes it as small as that requirement allows.
             *
             * `armeabi-v7a` IS KEPT DELIBERATELY, and it is measured at 6,809,547 bytes — 6.49 MB —
             * of this APK, so it is the largest single saving still on the table. `minSdk = 26` means
             * this app targets handsets back to 2017, when 32-bit-only devices were still being sold
             * in this market, and the failure mode of guessing wrong is not a slow app — it is an
             * install that refuses, on a phone whose update dialog cannot be dismissed, in a village.
             * There is no device inventory here to say the risk is zero, so it is not assumed to be.
             * With a roster of what the designers actually carry, this is a one-line change.
             *
             * THE EMULATOR, because dropping x86 drops it and this project has no device CI. `debug`
             * below is left unfiltered on purpose, so day-to-day work on an x86_64 emulator is
             * untouched. For the one case that needs more — smoke-testing a SHRUNK release build with
             * no handset in reach, which is exactly what the R8 section of this file says must not be
             * skipped — a developer opts in through their own gitignored `local.properties`, and the
             * build says out loud what it did:
             *
             *     releaseAllAbis=true
             */
            if (localProperties.getProperty("releaseAllAbis", "false").toBoolean()) {
                logger.lifecycle(
                    "release: packaging ALL FOUR ABIs (releaseAllAbis=true) — about 22 MB of x86 and " +
                        "x86_64 native libraries no field handset can load. For emulator testing " +
                        "only; do not publish this APK."
                )
            } else {
                ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                // The AGP-supplied baseline, which already carries the Android platform and Compose
                // keeps. `-optimize` is deliberately NOT used: it is the aggressive variant, and the
                // extra few per cent is not worth a second variable in a change that cannot be
                // exercised on hardware here.
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Left unshrunk on purpose. The debug build is what the JVM unit tests and any
            // day-to-day install run against, and shrinking it would make a stack trace during
            // development point at an obfuscated name for no benefit.
            //
            // AND DELIBERATELY LEFT WITHOUT `abiFilters`, which is the other half of the release
            // block's narrowing. There is no device CI on this project, so the emulator is how a
            // developer with no handset runs anything at all — and a standard AVD is x86_64. The
            // debug APK is never downloaded over a mobile connection by anybody, so the ABIs it
            // carries cost nothing that matters. Narrowing both build types would have saved no
            // field byte and taken the only machine some contributors have.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    /**
     * A JVM unit-test source set, because until now this module had NONE.
     *
     * That absence is why every defect fixed alongside this line could ship: the report writer, the
     * wire DTOs, the sync payload builder and the permission table are all pure Kotlin with no
     * Android in them, and all four were nevertheless verifiable only by installing the app on a
     * handset and looking at the file that came out. `canRunDesignWorkshops` in particular cannot be
     * checked by reading it — a rank ladder and a set agree on six roles out of seven — and the
     * report's RICH_TEXT hole was invisible on screen because the editor renders the prose the file
     * omits. Tests here run on the desktop JVM in seconds and gate exactly those four surfaces.
     *
     * `isReturnDefaultValues` keeps a stray android.jar stub (android.util.Log, mostly) from
     * throwing "not mocked" and failing a test that has nothing to do with Android — the code under
     * test is deliberately the part that has no framework in it.
     */
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Instrumented-test only: nothing here reaches the shipped APK, so it costs no download size.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    // In-app video/audio playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    /**
     * On-device text recognition, so an identity card can be read with NO CONNECTION.
     *
     * ── BUNDLED, NOT THE PLAY SERVICES ONE, AND THE DIFFERENCE IS THE WHOLE FEATURE ────────────
     *
     * `com.google.mlkit:text-recognition` carries the model inside the APK.
     * `com.google.android.gms:play-services-mlkit-text-recognition` is a 0.07 MB shim that DOWNLOADS
     * the model from Play Services on first use — and first use is a designer in a courtyard that has
     * had no signal for two days, where it fails silently as "the card would not read". Picking the
     * small one would be picking a reader that is absent exactly where it is needed.
     *
     * ── LATIN ONLY. `text-recognition-devanagari` IS DELIBERATELY ABSENT ───────────────────────
     *
     * Not on size — on whether its output could ever be used. The extraction consults ASCII `'0'..'9'`
     * and nothing else, at three independent layers (`IdentityCardText.scanDigitRuns`,
     * `ArtisanIdentity.aadhaarError`, and the server's `_AADHAAR_RUN`), because a Devanagari
     * "१२३४५६७८९०१२" stored beside "123456789012" is one artisan recorded as two people in the column
     * whose only job is deduplication. A Devanagari model would recognise glyphs this pipeline then
     * throws away — 2,015,832 measured bytes of artifact and a second inference pass on a mid-range
     * handset, for no change in outcome. `IdentityCardRecognizer.kt` has the full argument and the
     * condition under which it should be revisited.
     *
     * ── THE COST, MEASURED ────────────────────────────────────────────────────────────────────
     *
     * Real `assembleRelease` figures in bytes, before and after, are in
     * `docs/DECISION-identity-card-ocr-on-android.md`. R8 cannot touch any of it: R8 shrinks Java and
     * Kotlin, and this is a native library.
     */
    implementation("com.google.mlkit:text-recognition:16.0.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // JUnit 4 rather than 5: AGP's `testDebugUnitTest` runs JUnit 4 out of the box, and a JUnit 5
    // platform here would need a third-party Gradle plugin to be fetched before a single assertion
    // could run — a dependency on the network in the one repository whose entire premise is working
    // without one.
    testImplementation("junit:junit:4.13.2")
}
