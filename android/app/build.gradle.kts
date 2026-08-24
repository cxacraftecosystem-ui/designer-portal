import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * THE RELEASE SIGNING KEY, AND WHY IT IS NOT IN THIS REPOSITORY.
 *
 * Until 2026-08-23 there was no release key at all: `buildTypes.release` could only borrow the DEBUG
 * keystore behind an opt-in flag, and the block there says why that is not distributable — the debug
 * key ships with every Android SDK on earth, so anybody can produce an update this app would accept
 * as genuine. With nothing published yet that cost nothing; the moment a build goes on the website
 * and into the update check it costs everything, and it is not undoable, because moving to a real key
 * afterwards makes every installed copy refuse the next update.
 *
 * So the key is real, it lives OUTSIDE the working tree, and its location and password arrive
 * through `local.properties` (gitignored) or through the environment for CI. Four properties:
 *
 *     releaseKeystore=C:/path/to/designrepo-release.jks
 *     releaseKeystorePassword=...
 *     releaseKeyAlias=designrepo
 *     releaseKeyPassword=...            # optional; defaults to the store password
 *
 * or ANDROID_RELEASE_KEYSTORE / _PASSWORD / _KEY_ALIAS / _KEY_PASSWORD in the environment.
 *
 * ABSENT IS A VALID STATE AND MUST STAY ONE. A clean checkout and CI have none of this, and the
 * release build there stays unsigned exactly as before — which fails loudly at install time instead
 * of quietly producing something that looks shippable. A missing key must never silently fall back
 * to the debug one.
 */
private fun signingProperty(props: Properties, propertyName: String, environmentName: String): String? =
    (props.getProperty(propertyName) ?: System.getenv(environmentName))?.trim()?.takeIf { it.isNotEmpty() }

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
// 2026-08-23: set to the FIRST PUBLISHED version. Nothing had ever been published — the API's own
// answer was "No Android build has been published yet, so there is nothing to download" — so there
// was no versionCode to beat and the counter starts here rather than continuing a number that only
// ever existed in this file. 0.0.1 derives versionCode 1, which is the lowest possible value and
// therefore leaves the entire range above it free; the failure mode this scheme guards against is a
// version published too HIGH, which blocks every later one.
//
// CONSEQUENCE, because it is a downgrade in this file even though it is not one in the field: a
// handset carrying a locally built 1.1.19 (versionCode 1,001,019) cannot install this over the top —
// Android refuses a downgrade — and the in-app updater will not offer it either. Uninstall first on
// any such device. No FIELD device is affected, because no build was ever published to one.
val appVersionName = "0.0.1"
val appVersionCode = appVersionName.split(".").let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    // minor and patch are each capped at 100 by the scheme, so the 1_000-wide buckets never collide.
    major * 1_000_000 + minor * 1_000 + patch
}

val releaseKeystorePath = signingProperty(localProperties, "releaseKeystore", "ANDROID_RELEASE_KEYSTORE")
val releaseKeystorePassword = signingProperty(localProperties, "releaseKeystorePassword", "ANDROID_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty(localProperties, "releaseKeyAlias", "ANDROID_RELEASE_KEY_ALIAS")
// Defaults to the store password, which is how `keytool` is almost always driven and what this
// project's key actually uses. Kept separately settable because a keystore CAN hold a key under a
// different password, and discovering that at the signing step is a confusing place to find out.
val releaseKeyPassword = signingProperty(localProperties, "releaseKeyPassword", "ANDROID_RELEASE_KEY_PASSWORD")
    ?: releaseKeystorePassword

// Resolved here rather than inside the signing config so that "the key is configured" and "the file
// is actually there" are one question with one answer. A property pointing at a keystore that does
// not exist used to be indistinguishable from no property at all, and the build simply produced an
// unsigned APK — the failure this whole arrangement exists to make loud.
val releaseKeystoreFile = releaseKeystorePath?.let { path ->
    // An absolute path is what a key kept outside the repository needs; a relative one is resolved
    // against the `android/` directory, which is what `../designrepo-release.jks` in a developer's
    // local.properties means to the person who wrote it.
    // `File`, IMPORTED, not `java.io.File` written out. In the Gradle Kotlin DSL `java` is already
    // taken — it is the JavaPluginExtension accessor on Project — so the fully qualified form parses
    // as that extension followed by a `.io` property and fails with "Unresolved reference: io".
    File(path).let { candidate -> if (candidate.isAbsolute) candidate else rootProject.file(path) }
}
val hasReleaseSigningKey =
    releaseKeystoreFile != null &&
        releaseKeystoreFile.isFile &&
        releaseKeystorePassword != null &&
        releaseKeyAlias != null
if (releaseKeystorePath != null && !hasReleaseSigningKey) {
    // Named loudly rather than left as a silent unsigned build: somebody set the property, so they
    // intended a signed release and would otherwise get an APK that cannot be installed at all.
    logger.warn(
        "release signing: `releaseKeystore` is set to '${releaseKeystorePath}' but the key is not " +
            "usable (file present: ${releaseKeystoreFile?.isFile == true}, password set: " +
            "${releaseKeystorePassword != null}, alias set: ${releaseKeyAlias != null}). " +
            "The release build will be UNSIGNED."
    )
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
        //
        // THIS LITERAL WAS THE SIBLING PRODUCT'S API UNTIL 2026-08-23. It read
        // `d2b34i3e92al6i.cloudfront.net`, which fronts the FIELD REPOSITORY's backend, and a native
        // HTTP client does not do CORS — so unlike the browser, which was accidentally protected by
        // that box refusing this portal's origin, the handset's calls were answered by the other
        // product. Anything design-workshop-shaped 404'd; anything the two products share by name
        // reached the wrong database.
        //
        // The comment that stood here argued for leaving it alone, and its argument was internally
        // sound: `docs/ENVIRONMENT.md` named a different distribution, every corroborating file
        // agreed with the literal below, and changing one client without the other breaks a working
        // pair. What it missed is that BOTH clients were wrong together, which is exactly what a
        // fork inherits — the value was copied out of the repository this one was split from, so the
        // count of files agreeing measured how thoroughly it was copied and not whether it was true.
        //
        // Settled by measurement, not by a console: `d2b34i3e92al6i` answers **404** for
        // `/api/design-workshops` — the same status as a route that was never defined — while
        // `d3ekigkotd1xa2` answers 401, and `d3ekigkotd1xa2`'s CORS allow-list changed the minute
        // this repository's own `deploy-backend.yml` ran. The full evidence is in
        // `docs/ENVIRONMENT.md` under the CloudFront row's resolution note.
        //
        // EVERY APK ALREADY ON A PHONE STILL HAS THE OLD HOST COMPILED IN. This line fixes new
        // builds only; existing installs need a re-issued APK.
        //
        // `docs/tools/check-docs.mjs` (`checkAndroidApiHost`) ties this literal to that document in
        // both directions, so this line and the docs move together or the docs run goes red.
        val apiBaseUrl = localProperties.getProperty(
            "apiBaseUrl",
            "https://d3ekigkotd1xa2.cloudfront.net/api/"
        )
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"614092441670-t718gqk8d00iihh3732t39ppm4tram5e.apps.googleusercontent.com\"")
        buildConfigField("String", "GOOGLE_ANDROID_CLIENT_ID", "\"614092441670-p6kfpnqqitg4n8dtc3klj815jcaa2h94.apps.googleusercontent.com\"")
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
    /**
     * Declared before `buildTypes` on purpose: the release build type below looks this config up by
     * name, and a config created afterwards is not there to be found.
     *
     * Created ONLY when a real key resolved. An empty-but-present "release" signing config is worse
     * than none — Gradle accepts it and produces an APK signed with nothing, which is the exact
     * outcome the block in `buildTypes.release` spends twenty lines warning about.
     */
    signingConfigs {
        if (hasReleaseSigningKey) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Both signature schemes. v1 (the JAR signature) is what API 26–23 era installers
                // read; v2/v3 are what everything since Nougat prefers and what allows key rotation
                // later. minSdk here is 26, so v1 is not strictly required — it is left on because
                // dropping it buys nothing and an APK that a sideloader refuses to install is a
                // support conversation nobody wants to have in a field workshop.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

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
             * THAT KEYSTORE NOW EXISTS (2026-08-23) and is read from outside the repository — see
             * `hasReleaseSigningKey` near the top of this file. What remains below is the fallback
             * for a machine that does not have it.
             */
            if (hasReleaseSigningKey) {
                signingConfig = signingConfigs.getByName("release")
                logger.lifecycle(
                    "release: signing with the RELEASE key (${releaseKeystoreFile?.name}, " +
                        "alias ${releaseKeyAlias}). This APK is distributable."
                )
            } else if (localProperties.getProperty("debugSignRelease", "false").toBoolean()) {
                /*
                  THE ORDER OF THESE TWO BRANCHES IS LOAD-BEARING, and it is the reverse of the order
                  they were written in. `debugSignRelease=true` is a flag a developer sets once in
                  their gitignored local.properties and then forgets for months — it was already set
                  on the machine that cut the first release. If debug signing were checked first, the
                  presence of a real key would be silently ignored and the build that went to the
                  website and to every handset would be the undistributable one, with a cheerful log
                  line saying so among four hundred others. The real key wins, always.
                */
                signingConfig = signingConfigs.getByName("debug")
                logger.lifecycle(
                    "release: signing with the DEBUG keystore (debugSignRelease=true, and no release " +
                        "key is configured). For on-device testing only — this APK is not distributable."
                )
            } else {
                logger.lifecycle(
                    "release: UNSIGNED — no release key configured and debugSignRelease is off. " +
                        "The APK will build and will not install."
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

    /**
     * THE OFFLINE SPEECH ENGINE — sherpa-onnx, vendored as a file because it is on no repository.
     *
     * ── WHERE IT COMES FROM, AND WHY IT IS A FILE IN `app/libs` ───────────────────────────────
     *
     * `sherpa-onnx-static-link-onnxruntime-1.13.5.aar`, 37,749,854 bytes, SHA-256
     * `508b79be1aeef3cbb92b8d4325b9b1dad0fa9a4eb1991de0d3d1826b8a09c358`, downloaded from the
     * `k2-fsa/sherpa-onnx` GitHub release `v1.13.5`. `docs/ASR-RUNTIME-MEASUREMENT.md` §1 proved
     * through the Gradle resolver — not through a web search — that no spelling of these
     * coordinates resolves from `google()` or `mavenCentral()`: six coordinates, six live 404s.
     * The `flatDir` that reaches this file is declared in `settings.gradle.kts`, which explains why
     * it has to live there.
     *
     * ── WHY THE STATIC-LINK VARIANT AND NOT THE DEFAULT ONE ───────────────────────────────────
     *
     * Recommendation 2 of that document, measured on eight real packaged APKs and NOT re-derived
     * here: the ARM pair costs **+39,811,828 bytes** with this AAR against **+53,308,196** with
     * `sherpa-onnx-1.13.5.aar`. The difference is 13,496,368 bytes and it is free — the static-link
     * build has `libonnxruntime.so` linked into `libsherpa-onnx-jni.so` instead of beside it.
     *
     * ── AND THIS CONTRADICTS `docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md`, WHICH IS THE FINDING ────
     *
     * That document designed the engine as an **opt-in download**: fetch a zip of `.so` files into
     * `filesDir`, verify each against a pinned digest, then load them. Its §8 step 2 says to load
     * "over `DwAsrArtifact.libraries` in list order". **That cannot work with this binding, and the
     * reason is a property of Android rather than of the design.** Every entry class in
     * `com.k2fsa.sherpa.onnx` carries a static initialiser calling
     * `System.loadLibrary("sherpa-onnx-jni")`, and `System.loadLibrary` resolves through
     * `ClassLoader.findLibrary`, which searches only the APK's own native-library directories. A
     * `.so` sitting in `filesDir` is invisible to it: `System.load(absolutePath)` loads the file
     * into the process but records it under its PATH, so the later `loadLibrary` still throws
     * `UnsatisfiedLinkError` before any of our code runs. Measured on the handset, not reasoned
     * about — `DwAsrEngineProbeTest` prints the classloader's own search path.
     *
     * So the engine is IN THIS APK, at the cost that document weighed, and the download half of
     * `DwAsrRuntime.kt` remains unreachable (`DW_ASR_ARTIFACTS` is still empty, deliberately: no
     * server serves an engine zip and inventing a URL to fill the row is the one thing that file's
     * constructors exist to prevent). Whoever wants the opt-in shape back must first answer a
     * question nobody has: how a downloaded `.so` reaches this binding at all.
     */
    implementation(":sherpa-onnx-static-link-onnxruntime-1.13.5@aar")

    /**
     * READING a QR code — off the camera, and off a screenshot somebody was sent.
     *
     * ── THIS IS THE CHOICE `docs/DECISION-qr-scanning-on-android.md` MADE, ARRIVING LATE ────────
     *
     * That document decided `com.google.zxing:core` on 2026-08-08 and then recorded, honestly and
     * at length, that NOTHING was built: the argument was carried one step further by the code —
     * "if a typed code is a shorter path to the same record, the camera is not worth 0.58 MB
     * either" — and both read surfaces shipped with a typed box and no scanner at all. Its own
     * review trigger is "any barcode or QR dependency appearing in `android/app/build.gradle.kts`",
     * which is this line, so the document has been updated rather than left to rot a third time.
     *
     * WHAT REOPENED IT is not a new measurement. It is a requirement: every QR surface is to offer
     * BOTH the camera and an image the designer already has, because a screenshot or a forwarded
     * photograph is very often the only thing they hold. A typed box cannot satisfy that at all —
     * the whole point of the picked-image path is that there is nobody standing in front of the
     * card to read twenty characters off it.
     *
     * ── WHY ZXING AND NOT ML KIT, WHICH IS ALREADY IN THIS BUILD ───────────────────────────────
     *
     * `com.google.mlkit:text-recognition` ships here for the identity-card reader, so
     * `com.google.mlkit:barcode-scanning` would arrive from a vendor already present. It is still
     * the wrong choice, for the two reasons the decision document gives and one it could not:
     *
     *  * SIZE. 9.44 MB against 0.58 MB, for a symbol that is being held still under a lens. The
     *    unbundled 0.50 MB variant is disqualified outright — it downloads its model on first use,
     *    and first use is a courtyard that has had no signal for two days.
     *  * PURE JAVA, WHICH IS THE ONE THIS REPOSITORY GAINS MOST FROM. ML Kit cannot run in a JVM
     *    unit test — `IdentityCardRecognizer`'s own header states that every accuracy claim about it
     *    is a hardware claim nobody on this machine can make. ZXing runs on the test classpath, so
     *    `DwQrDecodeTest` decodes symbols produced by THIS APP'S OWN `DwQrEncode` and asserts the
     *    round trip. The printer and the reader are checked against each other on every build
     *    instead of on a handset nobody has.
     *
     * THE ACCEPTED REGRESSION, STATED: ML Kit reads a bent, angled or glared code off a live frame
     * better than ZXing does. That trade is the document's and is unchanged — the typed box stays
     * on every surface, a photograph can be retaken and re-read, and the decode ladder in
     * `DwQrDecode` re-tries at higher resolution before giving up.
     *
     * ── AND THERE IS NOW A LIVE FRAME, WHICH MOVES THAT REGRESSION RATHER THAN SETTLING IT ─────
     *
     * The clause above was written when the only camera path was a shutter press, and the CameraX
     * block below has added a live one. Re-read on 2026-08-24 rather than recalled: ML Kit's bundled
     * barcode reader is **9,898,786 bytes** (`com.google.mlkit:barcode-scanning:17.3.0`) against
     * this line's **607,650** — sixteen times the size, for a symbol that a designer is holding
     * inside a reticle. The unbundled `play-services-mlkit-barcode-scanning:18.3.1` is **519,271
     * bytes** and is still disqualified for the reason that has never changed: it fetches its model
     * on first use, and first use is a courtyard that has had no signal for two days, where the
     * failure reads as a broken camera.
     *
     * TWO THINGS THE LIVE PATH DOES THAT MAKE THE TRADE BETTER, NOT WORSE. The frame is CROPPED to
     * the reticle before ZXing sees it (`dwQrCropInBuffer`), so the binarizer never looks at the
     * courtyard — which is `DW_QR_SAMPLE_LADDER`'s own insight applied at capture instead of after.
     * And a miss costs 33 ms rather than a retake, so thirty attempts a second replace three per
     * shutter press. The one thing that has NOT changed is the only reason this line is here at all:
     * ZXing is pure Java, so `DwQrLiveFrameTest` runs the shipping live decoder on the desktop over
     * symbols this app's own `DwQrEncode` produced. Choosing ML Kit forfeits the only accuracy
     * evidence a repository with no handset can produce.
     */
    implementation("com.google.zxing:core:3.5.3")

    /**
     * THE LENS — a live preview, bound to the BACK camera by this application rather than by
     * whatever the system camera app last opened.
     *
     * ── WHY THIS ARRIVED, AND IT IS NOT THE MEASUREMENT THE DECISION DOCUMENT ASKED FOR ────────
     *
     * `docs/DECISION-qr-scanning-on-android.md` lists "the arrival of CameraX or any live-preview
     * scanning, which is the one capability deliberately not built here" as a REVIEW TRIGGER, and
     * this block fires it by name. It also fires three more of its clauses at once: a further change
     * to the QR dependency area, a new mount of the scanning control, and a change to every scanner
     * header — `data/DwQrDecode.kt`, `ui/designworkshop/WorkshopCodesScreen.kt` and
     * `ui/RecordCodeLookup.kt` each asserted "no CameraX / no live preview" as a DECISION, and all
     * three have been corrected in the same change as this line rather than left to contradict it.
     *
     * WHAT REOPENED IT IS A DEFECT THE STILL PATH CANNOT FIX. `ActivityResultContracts.TakePicture()`
     * hands off to the system camera app, which reopens whatever lens it last used; the lens cannot
     * be forced through that contract at all, so designers were met by the FRONT camera and there was
     * no flag to set. `bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, …)` is chosen by
     * this app, on every bind, and cannot drift. The reticle, the sweep and live detection are what
     * the requirement asked for; the back lens is why it could not be declined.
     *
     * ── THE FOUR LINES, AND WHAT ELSE ARRIVES WITH THEM ────────────────────────────────────────
     *
     * Sizes read off `dl.google.com/dl/android/maven2` on 2026-08-24 — never inferred from a version
     * bump, which is what that document requires of a size claim:
     *
     *     androidx.camera:camera-core:1.5.3                            1,184,683 bytes
     *     androidx.camera:camera-camera2:1.5.3                           604,031
     *     androidx.camera:camera-lifecycle:1.5.3                          50,554
     *     androidx.camera:camera-compose:1.5.3                            45,173
     *     androidx.camera.viewfinder:viewfinder-compose:1.5.3  (transitive) 66,485
     *     androidx.camera.viewfinder:viewfinder-core:1.5.3      (transitive) 90,373
     *     androidx.camera.featurecombinationquery:…:1.5.3        (transitive) 18,525
     *     androidx.lifecycle:lifecycle-livedata:2.8.7            (transitive) 57,494
     *     androidx.concurrent:concurrent-futures-ktx:1.1.0       (transitive)  5,605
     *                                                          ─────────────────────
     *                                                          2,122,923  (2.02 MiB)
     *
     * The last two are the ONLY genuinely new non-camera rows and they were checked against
     * `android/deps.txt` rather than assumed: `lifecycle-livedata-CORE:2.8.7` is already there
     * (fourteen times), the full `lifecycle-livedata` is not, and `concurrent-futures-ktx` is not.
     * Everything else CameraX asks for already resolves on `releaseRuntimeClasspath` —
     * `concurrent-futures:1.1.0` (deps.txt line 64), `tracing:1.2.0` (line 104), `jspecify:1.0.0`
     * (line 596), the empty `listenablefuture` stub already forced to `9999.0`,
     * `kotlinx-coroutines-android` (already forced to 1.9.0, above camera-core's 1.8.1 request),
     * `fragment:1.5.7` and `appcompat:1.6.1` (arriving via credentials → biometric).
     *
     * `lifecycle-livedata` arrives because `CameraInfo.getTorchState()` is a `LiveData<Integer>` and
     * is read to drive the torch button. That is not an accident of the dependency graph — a torch
     * button whose lit state comes from a local boolean goes wrong for real: `enableTorch` is
     * asynchronous and the platform turns the torch off on unbind, so a stale `true` leaves a lit
     * icon over a dark frame. `DwQrLiveScanner` reads the platform's own state instead.
     *
     * ── 1.5.3 AND NOT 1.6.x, MEASURED FROM THE AARs' OWN METADATA ─────────────────────────────
     *
     * `META-INF/com/android/build/gradle/aar-metadata.properties`, unzipped out of three
     * `camera-core` artifacts:
     *
     *     1.4.2  minCompileSdk 34   minAndroidGradlePluginVersion 1.0.0
     *     1.5.3  minCompileSdk 35   minAndroidGradlePluginVersion 8.6.0
     *     1.6.1  minCompileSdk 36   minAndroidGradlePluginVersion 8.9.1
     *
     * This build is `compileSdk = 35` on AGP 8.7.3, so 1.6.x FAILS the metadata check outright — it
     * would require a compileSdk and an AGP bump in the same commit as a scanner, on a release path
     * that also carries R8 keep rules, four-ABI packaging, a vendored 37 MB AAR and a signing
     * arrangement built to fail loudly. Two changes, two commits. 1.4.2 clears the check and its
     * `camera-core` is 246 KB smaller, and it is still the wrong choice: its `camera-compose` is a
     * 1,449-byte STUB against 1.5.3's real 45,173-byte module, so 1.4.2 forces `camera-view` and
     * `PreviewView` instead of the Compose viewfinder. That is the deciding fact, not the version.
     *
     * ── `camera-compose` AND NOT `camera-view`, WHICH WOULD HAVE COST NO NEW ARTIFACT ──────────
     *
     * `appcompat:1.6.1` and `fragment:1.5.7` are already on the release classpath, so `PreviewView`
     * was free. `CameraXViewfinder` is taken anyway because it is a Compose composable in a codebase
     * that is Compose all the way down, and because wrapping a `PreviewView` in an `AndroidView`
     * inside a `Dialog` is the shape that produces the black-first-frame reports. What it does NOT
     * buy is the reticle mapping: see `dwQrCropInBuffer`, which uses `ImageProxy.cropRect` and a
     * `ViewPort` rather than the coordinate transformer, and says why.
     *
     * ── THE APK COST IS AN ESTIMATE AND IS LABELLED AS ONE ────────────────────────────────────
     *
     * NOT MEASURED. `docs/R8-MEASUREMENT.md` establishes that only a packaged-APK read counts here,
     * and the command is:
     *
     *     ./gradlew :app:assembleRelease
     *     stat -c %s app/build/outputs/apk/release/app-release.apk
     *
     * (Written as two lines with the file named rather than as one line with a glob, because a glob
     * before `.apk` spells the end of a block comment and silently ate this whole paragraph once.)
     *
     * What can be said with evidence: release R8 is ON (`isMinifyEnabled` + `isShrinkResources`
     * below), and unlike every previous size decision in this file CameraX is pure JVM bytecode —
     * no `.so`, no model asset — so R8 can actually reach it, where that document records "99.5% of
     * the cost is in rows R8 is structurally unable to shrink" for ML Kit and sherpa. The nearest
     * precedent there ("five ML Kit artifacts bring roughly 1 MB of Java/Kotlin … 214,881 bytes —
     * R8 ate almost all of it") will FLATTER CameraX, though: ML Kit's Java was mostly unreached
     * API surface, whereas `camera-core` plus `camera-camera2` is a pipeline entered wholesale
     * through `bindToLifecycle` whose ~100 device-quirk classes are reached by enumeration. Estimate
     * +700 KB to +1.2 MB of dex, which against the last measured shipping figure
     * (`docs/ASR-RUNTIME-MEASUREMENT.md` row E, 66,056,244 bytes) is 1.1%–1.8%. Do not quote it as
     * a measurement.
     *
     * ── NO NEW PERMISSION AND NOTHING NEW IN THE INSTALL DIALOG ───────────────────────────────
     *
     * All five CameraX manifests were unzipped and read: `camera-core` contributes only a DISABLED,
     * unexported `androidx.camera.core.impl.MetadataHolderService`, and the rest contribute only
     * `<uses-sdk android:minSdkVersion="23"/>`, which the merger discards under this module's 26.
     * No `uses-permission`, no `uses-feature`. `android.permission.CAMERA` was already declared.
     */
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-compose:1.5.3")

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
