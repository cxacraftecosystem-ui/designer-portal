import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    // No `org.jetbrains.kotlin.android`: AGP 9 compiles Kotlin itself. See the root build.gradle.kts.
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
val appVersionName = "0.0.16"
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
    /**
     * THE NEWEST STABLE PLATFORM, 37.2 — Android 17's second minor release — as of 2026-10-09.
     *
     * Not a free choice in the other direction either: the current core-ktx, Compose UI,
     * lifecycle-compose, OkHttp and Coil AARs each declare `minCompileSdk=37` in their own
     * `aar-metadata.properties`, so anything older fails `checkDebugAarMetadata` before a line is
     * compiled. A minor level only ADDS APIs and nothing here calls one of them yet; `targetSdk`, which
     * is what changes behaviour, has no minor level and is 37 below. CI installs
     * `platforms;android-37.2` for this in android-build.yml, android-emulator.yml and
     * publish-android.yml — move the four together.
     */
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    /**
     * Pinned rather than left to AGP's default (36.0.0 for AGP 9.4), so the `aapt2` and `apksigner`
     * that publish-android.yml uses to PROVE the signer and read the packaged manifest are the same
     * 37.0.0 this build packaged with. `BUILD_TOOLS_VERSION` there moves with this line.
     */
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.designprototype.workshop"
        minSdk = 26
        /**
         * Android 17. What that switches on for THIS app, checked against the code on 2026-10-09
         * rather than copied from the release notes:
         *
         *  * Edge-to-edge with no opt-out (targetSdk 35 already enforced it on Android 15, and this
         *    app never opted out): the activity's root pays the system-bar and cutout insets once,
         *    `SystemBarsInsetsRoot` in ui/Theme.kt.
         *  * Predictive back: nothing overrides `onBackPressed` or reads KEYCODE_BACK; every back
         *    path is a Compose `BackHandler`, which the system's back callback reaches.
         *  * Orientation and resizability limits ignored at 600dp and wider: the manifest sets none.
         *  * ACCESS_LOCAL_NETWORK for private-address hosts: only a developer's `apiBaseUrl` override
         *    is one, so only the DEBUG manifest declares it and `MainActivity` asks for it when
         *    `apiHostNeedsLocalNetwork` says the configured host needs it.
         *  * Background audio: a hidden app's playback is silenced, so both players in
         *    ui/MediaPlayers.kt pause when the app leaves the screen.
         *  * Certificate Transparency and Encrypted Client Hello on by default: every production host
         *    has a publicly-trusted certificate (res/xml/network_security_config.xml).
         *  * Native libraries loaded by PATH must be read-only: nothing here calls `System.load`.
         */
        targetSdk = 37
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
         *     machine a contributor without a handset has, and the one
         *     `.github/workflows/android-emulator.yml` installs this debug APK on (on demand, and
         *     never a gate — the only device CI this project has, since 2026-09-03).
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
     * applies automatically. Verified by unzipping them rather than trusted, and re-read on
     * 2026-10-09 for the versions this file now names: `kotlinx-serialization-core-jvm-1.11.0`
     * carries `META-INF/proguard/kotlinx-serialization-common.pro` (and the
     * `META-INF/com.android.tools/` proguard and r8 copies), `retrofit-3.0.0` carries
     * `META-INF/proguard/retrofit2.pro`, and OkHttp 5's Android artifact, `okhttp-android-5.5.0.aar`,
     * carries a consumer `proguard.txt` — `-dontwarn` lines only, because OkHttp needs no keep rule.
     * `proguard-rules.pro` therefore holds what is specific to this app or that no library ships: our
     * own `@Serializable` wire types, two of the seven Retrofit interfaces by name (Retrofit's own
     * `-if interface * { @retrofit2.http.* <methods>; }` rule keeps all seven), `-dontoptimize`, the
     * ML Kit registrars' constructors, the sherpa-onnx binding, and Credential Manager's provider.
     *
     * WHAT IS PROVEN, AND WHAT IS STILL NOT. R8's failure mode is not a compile error — it is a
     * `SerializationException` or a `NoSuchMethodError` at the first sync, on a build that installed
     * and ran fine on a desk. On 2026-10-09 this shrunk build ran on the API 37 emulator (runs
     * 37949869530, 37951774101, 37953735603; docs/CI.md §1.5): sign-in, both set-password
     * link forms, the designer profile's Coil pictures, no crash or R8-shaped exception, and every
     * class reached by name present. Never run shrunk: sync and media upload, dictation on ARM, a
     * live camera, Credential Manager — so a release still goes through the offline loop on real
     * hardware before it ships, the gap the handover records against the offline claim itself.
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
             * THE EMULATOR, because dropping x86 drops it and this project's only device CI is the
             * on-demand android-emulator.yml. `debug`
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
                // keeps. R8's OPTIMISATION pass is still deliberately off: it is the aggressive
                // variant, and the extra few per cent is not worth a second variable in a build that
                // cannot be exercised on hardware here.
                //
                // `-optimize.txt` IS NAMED BELOW ONLY BECAUSE AGP 9 REFUSES THE OTHER ONE. Until
                // 2026-10-09 this line read `proguard-android.txt` — AGP's common rules plus a
                // `-dontoptimize` — and AGP 9 rejects that file outright
                // (`android.r8.proguardAndroidTxt.disallowed`). AGP's own error message prescribes
                // the replacement, which is what is done: the optimize baseline here, and the
                // `-dontoptimize` carried by `proguard-rules.pro` itself, so R8 still does not
                // optimise. Turning optimisation ON is deleting that one line there — a separate
                // decision, to be taken with a release build running on a handset.
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Left unshrunk on purpose. The debug build is what the JVM unit tests and any
            // day-to-day install run against, and shrinking it would make a stack trace during
            // development point at an obfuscated name for no benefit.
            //
            // AND DELIBERATELY LEFT WITHOUT `abiFilters`, which is the other half of the release
            // block's narrowing. The only device CI here is the on-demand android-emulator.yml, so the
            // emulator is how a
            // developer with no handset runs anything at all — and a standard AVD is x86_64. The
            // debug APK is never downloaded over a mobile connection by anybody, so the ABIs it
            // carries cost nothing that matters. Narrowing both build types would have saved no
            // field byte and taken the only machine some contributors have.
            isMinifyEnabled = false
        }
    }

    /**
     * THE BYTECODE LEVEL IS JAVA 17, AND IT STAYS 17 WHATEVER JDK RUNS GRADLE.
     *
     * 17 is the newest Java level Android documents for any platform (Android 14 / API 34 maps to
     * Java 17 — developer.android.com/build/jdks), so it is a ceiling, not a version to chase. CI runs
     * Gradle on JDK 25 since 2026-10-09; these two pins and the `kotlin { }` block after `android { }`
     * are what keep the class files at major version 61 regardless. (The `kotlinOptions { }` block that
     * used to sit here is gone from AGP 9's DSL; `compilerOptions` is its replacement.)
     */
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

// The Kotlin half of the Java 17 pin in `compileOptions` above — see the note there. Explicit rather
// than inherited from `targetCompatibility`, which AGP 9's built-in Kotlin would also do, so that the
// four vendored engine modules and this one state the same target in the same words.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/*
 * EVERY VERSION BELOW IS THE NEWEST STABLE RELEASE AS OF 2026-10-09, read off Google Maven's and Maven
 * Central's own `maven-metadata.xml` that day with alpha, beta and rc builds excluded. Two rows are
 * older than that and say why where they stand: `material-icons-extended`, which the Compose BOM still
 * maps to its final 1.7.8, and `junit:junit`, whose 4.13.2 is JUnit 4's last release.
 * `.github/dependabot.yml` watches this file from that date on, so it does not freeze again.
 */
dependencies {
    // Compose UI/foundation/runtime 1.12.1 and material3 1.4.0.
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Instrumented-test only: nothing here reaches the shipped APK, so it costs no download size.
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.compose.material3:material3")
    // Frozen upstream at 1.7.8 (last published 2025-02-12); the BOM above still maps it. If a later
    // BOM stops doing so, pin 1.7.8 here or move to Material Symbols vector assets.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")

    /**
     * Coil 3, which moved to its own group (`io.coil-kt.coil3`) and package (`coil3.*`); Coil 2's
     * 2.7.0 was its final release.
     *
     * `coil-network-okhttp` IS NOT OPTIONAL. Coil 3's core no longer fetches http(s) URLs by itself:
     * without a network artifact every remote `AsyncImage` fails quietly into its placeholder, with
     * nothing in the build to say so. It registers itself through Coil's service loader, on its own
     * OkHttp client — as Coil 2's built-in fetcher did — so no image request carries this app's
     * session. Coil 3 also caps a decoded image at 4096×4096 by default, which is a smaller bitmap
     * on the cheapest phones in the field rather than a lost one.
     */
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-video:3.6.3")

    // In-app video/audio playback. media3 1.11 merges `android.permission.WAKE_LOCK` into the
    // manifest, for its default wake-lock handling while playing.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

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
     * `sherpa-onnx-static-link-onnxruntime-1.13.8.aar`, 38,691,998 bytes, SHA-256
     * `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471`, downloaded from the
     * `k2-fsa/sherpa-onnx` GitHub release `v1.13.8` — the newest release as of 2026-10-09, and the
     * digest is the one GitHub publishes for the asset as well as the one read off the downloaded
     * file. `docs/ASR-RUNTIME-MEASUREMENT.md` §1 proved through the Gradle resolver — not through a
     * web search — that no spelling of these coordinates resolves from `google()` or
     * `mavenCentral()`: six coordinates, six live 404s. The `flatDir` that reaches this file is
     * declared in `settings.gradle.kts`, which explains why it has to live there.
     *
     * ── 1.13.8 SINCE 2026-10-09; EVERY MEASUREMENT CITED HERE WAS TAKEN ON 1.13.5 ───────────────
     *
     * What moved, read out of the two AARs rather than the release notes: the same fifteen entries
     * and the same four ABIs; `javap` prints every class this app imports from the binding
     * (`OfflineRecognizer`, its config classes, `OfflineStream`, `WaveReader` and the result type)
     * identically; the native code is onnxruntime 1.28.2 in place of 1.27.1, and the ARM pair grew by
     * 807,680 bytes (arm64-v8a 23,646,824 → 24,169,352, armeabi-v7a 16,152,132 → 16,437,284). What
     * a desk cannot re-establish is the on-device behaviour, so the engine probes
     * (`DwAsrEngineProbeTest` on android-emulator.yml, then the handset) are run again before a
     * release carries it.
     *
     * ── WHY THE STATIC-LINK VARIANT AND NOT THE DEFAULT ONE ───────────────────────────────────
     *
     * Recommendation 2 of that document, measured on eight real packaged APKs at 1.13.5 and NOT
     * re-derived here: the ARM pair costs **+39,811,828 bytes** with the static-link AAR against
     * **+53,308,196** with `sherpa-onnx-1.13.5.aar`. The difference is 13,496,368 bytes and it is
     * free — the static-link build has `libonnxruntime.so` linked into `libsherpa-onnx-jni.so`
     * instead of beside it.
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
    implementation(":sherpa-onnx-static-link-onnxruntime-1.13.8@aar")

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
     * ══════════════════════════════════════════════════════════════════════════════════════════
     * ⚠ HALF OF THE ARGUMENT ABOVE WAS OVERTURNED ON 2026-08-28. READ THIS BEFORE ACTING ON IT.
     * ══════════════════════════════════════════════════════════════════════════════════════════
     *
     * EVERYTHING ABOVE IS KEPT DELIBERATELY, and it is kept because it is still the reasoning that
     * decides half the question. Nothing in it was wrong when it was written; the sizes were and are
     * correct, and the paragraph naming the live-frame regression was the one that turned out to
     * matter. What follows says which sentences no longer describe this build.
     *
     * WHAT HAPPENED. The owner reported on 2026-08-27: "QR scan on android devices does not pick up
     * the region of interest and scan while the camera is on", and "I do not mind MLKit, use it if it
     * guarantees the behaviour." THE ACCEPTED REGRESSION NAMED ABOVE BECAME THE REPORTED DEFECT, and
     * a trade whose cost has been paid in the field is re-taken rather than re-defended. The line
     * below this block adds `com.google.mlkit:barcode-scanning:17.3.0`, and the paragraph two above
     * ("sixteen times the size, for a symbol that a designer is holding inside a reticle") is now the
     * PRICE OF THE FIX rather than a reason against it.
     *
     * THE UNBUNDLED VARIANT IS STILL DISQUALIFIED AND THE OWNER'S WORDS MAKE THAT REASON STRONGER,
     * not weaker. A model fetched on first use cannot "guarantee the behaviour" anywhere this app is
     * used. That clause of the argument above stands in full.
     *
     * WHAT IS FALSE NOW, SENTENCE BY SENTENCE:
     *
     *  * "The frame is CROPPED to the reticle before ZXing sees it" — true only of the FALLBACK
     *    reader. ML Kit takes no crop parameter, so the live camera reads the whole frame and a
     *    sighting whose bounding-box centre falls outside the reticle is refused afterwards, with a
     *    sentence on screen when that happens. Cropping was the alternative and was rejected on the
     *    direction of the defect: the complaint is a FALSE NEGATIVE, and cropping is the operation
     *    that manufactures false negatives.
     *  * "Choosing ML Kit forfeits the only accuracy evidence a repository with no handset can
     *    produce" — this was the strongest sentence in the argument and it was engineered around
     *    rather than accepted. ZXING IS NOT REMOVED AND IS NOT DEAD CODE. It reads every photograph
     *    and every picked picture, unchanged; it is the live path's fallback on a device where ML
     *    Kit cannot start, said out loud on screen when it engages; and `DwQrLiveFrameTest` still
     *    runs THAT decoder on the desktop over symbols this app's own `DwQrEncode` produced. The
     *    seam that makes both true is `data/DwQrFrameReader.kt`.
     *
     * WHAT IS STILL TRUE AND IS WHY THIS LINE STAYS: ZXing is pure Java, it is 610,364 bytes at
     * 3.5.4 (read off Maven Central on 2026-10-09; 3.5.3 was the 607,650 re-measured on this machine
     * on 2026-08-28), and it is the only QR reader in this build that a machine with no handset can
     * make any accuracy claim about at all.
     */
    implementation("com.google.zxing:core:3.5.4")

    /**
     * READING A QR OFF A LIVE FRAME — ML Kit, BUNDLED, added 2026-08-28.
     *
     * ── WHY THIS IS HERE, WHICH IS AN OWNER'S DECISION AND NOT A NEW MEASUREMENT ───────────────
     *
     * The block immediately above chose ZXing, measured the alternative honestly, and wrote down the
     * cost of the choice as an ACCEPTED REGRESSION: "ML Kit reads a bent, angled or glared code off a
     * live frame better than ZXing does." On 2026-08-27 the owner reported that regression as a
     * defect — "QR scan on android devices does not pick up the region of interest and scan while the
     * camera is on" — and settled the trade: "I do not mind MLKit, use it if it guarantees the
     * behaviour." A cost that has been paid in the field stops being a trade and becomes a bug.
     *
     * ── BUNDLED, AND THE SMALL ONE IS DISQUALIFIED BY THE OWNER'S OWN WORD "GUARANTEES" ────────
     *
     * `com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1` is **519,271 bytes**
     * against this line's **9,898,786** — both re-measured from the Gradle cache on this machine on
     * 2026-08-28, and both unchanged from the 2026-08-24 reading above, so the figures are a fact
     * about the artifacts rather than a memory. THE SMALL ONE FETCHES ITS MODEL ON FIRST USE. First
     * use is a courtyard that has had no signal for two days, and a reader that must download itself
     * guarantees nothing at all there — it fails as "the camera does not read cards". Same reason
     * `text-recognition` is the bundled one; same reason the sherpa engine is in the APK.
     *
     * ── THE MEASURED ARTIFACT COST, AND WHAT IS ALREADY PAID FOR ───────────────────────────────
     *
     * Read off `~/.gradle/caches/modules-2/files-2.1` after resolution on this machine, 2026-08-28.
     * Only TWO of these rows are new: the rest arrived with `com.google.mlkit:text-recognition` and
     * are shared, which is the one genuine saving in choosing a vendor already in the build.
     *
     *     com.google.mlkit:barcode-scanning:17.3.0             9,898,786 bytes   NEW
     *     com.google.mlkit:barcode-scanning-common:17.0.0         63,153         NEW
     *     com.google.mlkit:common:18.11.0                        434,066         already present
     *     com.google.mlkit:vision-common:17.3.0                  269,178         already present
     *     com.google.mlkit:vision-interfaces:16.3.0               73,882         already present
     *
     * AAR bytes are not APK bytes, and the difference is not small: an AAR is a zip of a jar, native
     * libraries and resources, and R8 shrinks the Java half while touching none of the native half.
     * So the APK cost was MEASURED rather than inferred from the row above.
     *
     * ── THE APK DELTA, ON TWO REAL `:app:packageRelease` RUNS DIFFERING ONLY BY THIS LINE ──────
     *
     * Same tree, same R8 configuration, same `abiFilters` pair; the second run additionally reverts
     * the four source files this wave touched, so the figure covers the whole change and not only the
     * dependency. Both APKs read with `zipfile`, on this machine, 2026-08-28.
     *
     *     with `barcode-scanning`      77,009,672 bytes
     *     without it                   67,738,370
     *     ──────────────────────────────────────
     *     DELTA                        +9,271,302
     *
     * AND IT RECONCILES, WHICH IS WHY IT IS TRUSTWORTHY. Comparing the two APKs' central directories
     * entry by entry accounts for every byte of it:
     *
     *     lib/arm64-v8a/libbarhopper_v3.so                     4,946,720   stored, not compressed
     *     lib/armeabi-v7a/libbarhopper_v3.so                   3,244,440   stored, not compressed
     *     the three tflite models under assets/mlkit_barcode_models    880,888   stored, not compressed
     *     the three `classes*.dex`, net, AFTER R8                169,955
     *     zip central directory and alignment padding             29,299
     *
     * TWO THINGS TO TAKE FROM THAT TABLE.
     *
     *  * **R8 CANNOT TOUCH 96% OF THIS.** The native library and the models are `STORED` entries — no
     *    deflate, no shrinking, no `minifyEnabled` that will ever help. Only the 169,955 dex bytes
     *    passed through R8 at all, and that figure already includes this wave's own new Kotlin.
     *  * **THE QR-ONLY NARROWING SAVES NO SPACE, AND IT WAS NEVER CLAIMED TO.** Two of the three
     *    packaged models are `oned_*` — one-dimensional barcode models, 490,432 bytes of the 880,888 —
     *    and they ship whatever `setBarcodeFormats` says, because assets are not stripped by format.
     *    The narrowing buys inference time and refusal honesty, not bytes. Anybody hoping to recover
     *    half a megabyte by restricting formats harder should stop here.
     *
     * ── WHAT IT DOES NOT COST: THE ONLY ACCURACY TEST THIS REPOSITORY CAN RUN ──────────────────
     *
     * The block above warned that "choosing ML Kit forfeits the only accuracy evidence a repository
     * with no handset can produce", and that was the sentence worth engineering around rather than
     * accepting. ML Kit cannot run in a JVM unit test — `IdentityCardRecognizer`'s header says the
     * same of the text recogniser. So ZXing was NOT removed: `data/DwQrFrameReader.kt` is a seam with
     * `MlKitQrFrameReader` on the live camera and `ReferenceQrFrameReader` beside it, and the
     * reference reader is the fallback on a device where this library cannot start, is still the
     * decoder behind both picture routes, and is still what `DwQrLiveFrameTest` runs on every build.
     *
     * ── THE FORMAT SET IS NARROWED TO QR, AND THAT IS NOT A MICRO-OPTIMISATION ─────────────────
     *
     * `BarcodeScannerOptions.setBarcodeFormats(FORMAT_QR_CODE)`, set in `MlKitQrFrameReader`. Left at
     * its default the detector looks for every symbology it knows on every frame, and each one is
     * something a designer might point a phone at BY MISTAKE — a UPC on the next table, the
     * DataMatrix on a courier label. The honest answer for a courier label is "no QR code was found",
     * not a payload `decodeWorkshopCode` refuses one step further from the truth.
     *
     * ── REVIEW TRIGGER ────────────────────────────────────────────────────────────────────────
     *
     * `docs/DECISION-qr-scanning-on-android.md` names "any barcode or QR dependency appearing in
     * `android/app/build.gradle.kts`" as a review trigger. This line fired it, and that document has
     * been updated in the same change rather than left to rot for a third time.
     */
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

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
     * AT 1.6.2, read off `dl.google.com/dl/android/maven2` on 2026-10-09: `camera-core` 1,213,194
     * bytes, `camera-camera2` 1,184,080, `camera-lifecycle` 54,987, `camera-compose` 42,565.
     * `camera-camera2` nearly doubled because 1.6 rebuilt its internals on CameraPipe, the stack
     * Google's own camera app runs on, which now arrives as an artifact of its own. Every other row
     * on the release classpath that nothing but CameraX asks for, worked out from the dump
     * regenerated that day (`android/deps.txt`) and read off the same hosts (Maven Central for the
     * non-AndroidX rows):
     *
     *     androidx.camera:camera-camera2-pipe:1.6.2                    1,585,622 bytes
     *     androidx.camera.viewfinder:viewfinder-core:1.6.2                91,706
     *     androidx.camera.viewfinder:viewfinder-compose:1.6.2             66,150
     *     com.google.dagger:dagger:2.59                                   55,480
     *     androidx.lifecycle:lifecycle-livedata:2.11.0                    54,902
     *     org.jetbrains.kotlinx:atomicfu-jvm:0.28.0                       48,578
     *     androidx.core:core-backported-fixes:1.0.0                       18,905
     *     androidx.camera.featurecombinationquery:…:1.6.2                 18,525
     *     jakarta.inject:jakarta.inject-api:2.0.1                         10,681
     *     androidx.tracing:tracing-ktx:1.3.0                               6,095
     *     com.google.auto.value:auto-value-annotations:1.6.3               5,906
     *     androidx.lifecycle:lifecycle-livedata-core-ktx:2.11.0            5,663
     *     androidx.concurrent:concurrent-futures-ktx:1.1.0                 5,605
     *
     * CameraPipe, Dagger, `jakarta.inject-api` and `core-backported-fixes` are new with 1.6: the
     * 1.5.3 POMs name none of them. (`javax.inject:1`, which Dagger also wants, was already there
     * through ML Kit.) The table and the paragraph after it are the reading taken when CameraX first
     * arrived, at 1.5.3, and are kept as that record.
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
     * The last two were counted then as the ONLY genuinely new non-camera rows, checked against the
     * pre-CameraX `android/deps.txt`: `lifecycle-livedata-CORE:2.8.7` was there (fourteen times),
     * the full `lifecycle-livedata` was not, and `concurrent-futures-ktx` was not. That count missed
     * three small rows the 1.5.3 POMs name as well, none of them in the pre-CameraX dump:
     * `auto-value-annotations` (camera-core), `tracing-ktx` (camera-lifecycle) and `atomicfu`
     * (viewfinder-core). Everything else CameraX asks for is pulled in by something else too, and
     * the dump regenerated on 2026-10-09 lists it above the camera rows: `concurrent-futures:1.1.0`
     * (deps.txt line 67), `tracing` (1.3.0 now, line 117), `jspecify:1.0.0` (line 87), the empty
     * `listenablefuture` stub forced to `9999.0` (line 69), `kotlinx-coroutines-android` (1.10.2 now,
     * above camera-core's 1.9.0 request), `fragment:1.5.7` and `appcompat` (1.7.1 now, 1.6.1 then),
     * both arriving via credentials → biometric.
     *
     * `lifecycle-livedata` arrives because `CameraInfo.getTorchState()` is a `LiveData<Integer>` and
     * is read to drive the torch button. That is not an accident of the dependency graph — a torch
     * button whose lit state comes from a local boolean goes wrong for real: `enableTorch` is
     * asynchronous and the platform turns the torch off on unbind, so a stale `true` leaves a lit
     * icon over a dark frame. `DwQrLiveScanner` reads the platform's own state instead.
     *
     * ── 1.6.2 SINCE 2026-10-09, AND WHY IT WAS 1.5.3 UNTIL THEN ─────────────────────────────────
     *
     * `META-INF/com/android/build/gradle/aar-metadata.properties`, unzipped out of the `camera-core`
     * artifacts (the 1.6.2 row read on 2026-10-09, the others when CameraX arrived):
     *
     *     1.4.2  minCompileSdk 34   minAndroidGradlePluginVersion 1.0.0
     *     1.5.3  minCompileSdk 35   minAndroidGradlePluginVersion 8.6.0
     *     1.6.1  minCompileSdk 36   minAndroidGradlePluginVersion 8.9.1
     *     1.6.2  minCompileSdk 36   minAndroidGradlePluginVersion 8.9.1
     *
     * Until 2026-10-09 this build was `compileSdk = 35` on AGP 8.7.3, so 1.6.x failed the metadata
     * check outright, and moving it would have meant a compileSdk and an AGP bump in the same commit
     * as a scanner. The toolchain change of that date (compileSdk 37.2, AGP 9.4.1) removed the
     * blocker, and 1.6.2 — the newest stable release — moved in with it. Its four entry points this
     * app calls (`CameraXViewfinder`, `ViewPort`/`UseCaseGroup`, `ImageAnalysis`, `awaitInstance`)
     * compiled unchanged, but CameraPipe is a new engine underneath them, so live QR reading, the
     * torch state and the reticle crop are re-checked on the handset before a release carries it.
     * (The Android 17 crash CameraX 1.6 is known for — a dynamic-range profile it did not recognise —
     * was fixed in 1.5.2 as well, so it is not the reason for the move.)
     *
     * 1.4.2 cleared the old check, its `camera-core` is 246 KB smaller, and it was still the wrong
     * choice: its `camera-compose` is a 1,449-byte STUB against 1.5.3's real 45,173-byte module, so
     * 1.4.2 forces `camera-view` and `PreviewView` instead of the Compose viewfinder.
     *
     * ── `camera-compose` AND NOT `camera-view`, WHICH WOULD HAVE COST NO NEW ARTIFACT ──────────
     *
     * `appcompat` (1.7.1 since 2026-10-09) and `fragment:1.5.7` are already on the release
     * classpath, so `PreviewView` was free. `CameraXViewfinder` is taken anyway because it is a
     * Compose composable in a codebase that is Compose all the way down, and because wrapping a
     * `PreviewView` in an `AndroidView` inside a `Dialog` is the shape that produces the
     * black-first-frame reports. What it does NOT buy is the reticle mapping: see
     * `dwQrCropInBuffer`, which uses `ImageProxy.cropRect` and a `ViewPort` rather than the
     * coordinate transformer, and says why.
     *
     * ── THE APK COST IS AN ESTIMATE AND IS LABELLED AS ONE ────────────────────────────────────
     *
     * NOT MEASURED. `docs/R8-MEASUREMENT.md` establishes that only a packaged-APK read counts here,
     * and the command is:
     *
     *     ./gradlew :app:assembleRelease
     *     stat -c %s app/build/outputs/apk/release/app-release.apk
     *
     * (`app-release-unsigned.apk` when no key is configured, which is what CI builds.) (Written as
     * two lines with the file named rather than as one line with a glob, because a glob
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
     * +700 KB to +1.2 MB of dex, which against the shipping figure measured at the time
     * (`docs/ASR-RUNTIME-MEASUREMENT.md` row E, 66,056,244 bytes) is 1.1%–1.8%. Do not quote it as
     * a measurement.
     *
     * That estimate is the 1.5.3 one, and 1.6.2 roughly doubles the camera bytecode R8 has to work
     * through (CameraPipe, Dagger, atomicfu), so expect more. The whole release APK was read once on
     * 2026-10-09, from a CI build of the 1.6.2 tree with every other upgrade of that day (android-build
     * run 37931290718, ARM pair, R8 on, UNSIGNED): 78,637,438 bytes, against 77,451,931 for the
     * signed 0.0.15 APK published 2026-09-20. That is a whole-app difference across three weeks of
     * source, not CameraX's share, and it is still no measurement of CameraX.
     *
     * ── NO NEW PERMISSION AND NOTHING NEW IN THE INSTALL DIALOG ───────────────────────────────
     *
     * All five CameraX manifests were unzipped and read: `camera-core` contributes only a DISABLED,
     * unexported `androidx.camera.core.impl.MetadataHolderService`, and the rest contribute only
     * `<uses-sdk android:minSdkVersion="23"/>`, which the merger discards under this module's 26.
     * No `uses-permission`, no `uses-feature`. `android.permission.CAMERA` was already declared.
     * Re-read at 1.6.2 on 2026-10-09: the same, with `camera-camera2` now adding its config
     * `<meta-data>` under that same disabled service.
     */
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-compose:1.6.2")

    /**
     * Retrofit 3 and OkHttp 5. Retrofit 3.0.0 is binary-compatible with 2.x and only raised its OkHttp
     * floor; OkHttp 5 stays binary-compatible for CALLERS, and Gradle module metadata picks its
     * Android artifact (`okhttp-android`) on its own. Two things did move: `Response.body` is no
     * longer nullable, so a few `?:` fallbacks in this app are dead code now (compiler warnings, not
     * errors), and OkHttp 5.3 added members to `okhttp3.Call`, which the JVM tests' canned calls
     * implement.
     *
     * The converter is Retrofit's own `converter-kotlinx-serialization`, the same code Jake
     * Wharton's `retrofit2-kotlinx-serialization-converter:1.0.0` shipped (Retrofit imported it
     * "unchanged" in 2.10.0); that repository is archived and its README says DEPRECATED, so the
     * coordinate and the import moved to `retrofit2.converter.kotlinx.serialization`.
     *
     * OkHttp 5 also connects with Happy Eyeballs (RFC 8305) by default — racing IPv6 against IPv4.
     * The CloudFront host was chosen because it answers on IPv6-only networks (see `apiBaseUrl`
     * above), so sign-in and sync on such a network are among the handset checks before a release.
     */
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    /**
     * THE VENDORED TRACE ENGINE — the only way this app turns a photograph into vectors.
     *
     * `DwTraceRuntime` in `ui/designworkshop/DwSketchTraceEngine.kt` is the seam: six suspend
     * members, and the whole sketch-trace panel written against the interface rather than against
     * any engine. `DwTraceKotlinRuntime` is the one implementation, and these four modules are what
     * it calls.
     *
     * ── WHAT WAS HERE BEFORE, AND WHY IT IS NOT ANY MORE ──────────────────────────────────────
     *
     * There used to be a second route and a dependency above this one for it. The handset ran the
     * SAME upstream engine as JavaScript — `src/main/assets/dw-trace-engine.js`, 128,026 bytes built
     * from `frontend/lib/trace/` by a script in that tree, evaluated inside an
     * `androidx.javascriptengine` isolate. **The owner replaced it with this one; there is no
     * JavaScript fallback.** The asset, the script, the CI step that rebuilt and byte-compared it,
     * the `androidx.javascriptengine:1.0.0` dependency and the Kotlin that drove it are all gone.
     *
     * The measured reason it went, rather than a preference: that route needed an Android System
     * WebView at Chromium M97 (January 2022) or newer for `JavaScriptSandbox.isSupported()` to
     * answer true, WebView updates arrive through Play, and this product's premise is a handset that
     * has been in a village for a fortnight — so on a real phone in the field the tracer could
     * simply not exist. These modules are compiled into the APK by this build, so if the app runs,
     * it traces.
     *
     * WHAT THE SWAP COSTS THE APK, WITH BOTH HALVES NAMED AND NEITHER MEASURED ON THIS APK. Going
     * out: `androidx.javascriptengine:1.0.0` and the 128,026-byte asset, which an isolated
     * `assembleRelease` probe (R8 off, so an upper bound — `docs/R8-MEASUREMENT.md` on why only a
     * packaged read counts) differenced at +321,584 and +43,147 bytes, so **-364,731**. Coming in:
     * 20,998 lines of Kotlin across these four modules, which is dex this build has never packaged
     * and nobody has weighed. The net is genuinely unknown and this comment will not guess at it.
     * The command that settles it is the one the release block already names:
     *
     *     ./gradlew :app:assembleRelease
     *     stat -c %s app/build/outputs/apk/release/app-release.apk
     *
     * WHY ALL FOUR ARE NAMED WHEN ONE WOULD COMPILE. `:core-pipeline` declares `api(...)` on the
     * other three, so `implementation(project(":core-pipeline"))` alone would already put every
     * engine type on this module's compile classpath. They are listed anyway because these are
     * VENDORED build files: the day someone re-vendors from upstream and finds `api` has become
     * `implementation`, the failure should be a resolved dependency graph that still compiles, not
     * a wall of unresolved references in code that never changed.
     *
     * VERSION ALIGNMENT, CHECKED RATHER THAN ASSUMED. `:core-pipeline` asks for
     * `kotlinx-serialization-json:1.11.0` and the line directly above asks for the same 1.11.0, so
     * there is no conflict for Gradle to resolve and no chance of the engine being handed a
     * different serialization runtime from the one the app's DTOs use. Move the two together. The
     * serialization COMPILER PLUGIN `:core-pipeline` applies is declared in the root
     * `build.gradle.kts` at 2.4.21 — the version this module already applies.
     *
     * JVM TARGET. Upstream builds these with `jvmToolchain(17)`; this module compiles at
     * `jvmTarget = JVM_17` with `sourceCompatibility`/`targetCompatibility` 17. See the note in each
     * vendored `build.gradle.kts` for how the two were reconciled — the bytecode level is the same
     * 17 either way, but the toolchain form demands a JDK 17 that does not exist on this machine —
     * and for the `-Xjdk-release=17` that keeps the engine compiling against the JDK 17 API now that
     * CI runs Gradle on JDK 25.
     */
    implementation(project(":core-imaging"))
    implementation(project(":core-vector"))
    implementation(project(":core-pipeline"))
    implementation(project(":core-export"))

    debugImplementation("androidx.compose.ui:ui-tooling")

    // JUnit 4 rather than 5: AGP's `testDebugUnitTest` runs JUnit 4 out of the box, and a JUnit 5
    // platform here would need a third-party Gradle plugin to be fetched before a single assertion
    // could run — a dependency on the network in the one repository whose entire premise is working
    // without one. 4.13.2 is JUnit 4's final release (2021-02-13), so it is the newest there is;
    // moving off it is a change of test framework, not a version bump.
    testImplementation("junit:junit:4.13.2")
}
