plugins {
    id("com.android.application") version "9.4.1" apply false
    // NO `org.jetbrains.kotlin.android` HERE OR IN `:app`, AND THAT IS NOT AN OMISSION. AGP 9 compiles
    // Kotlin itself ("built-in Kotlin"), using the Kotlin Gradle plugin that the `kotlin.jvm` line below
    // already puts on the build classpath, and it fails the build outright if the old plugin is applied
    // on top of it. Its `android.builtInKotlin=false` opt-out is not used: AGP 10 removes it.
    //
    // The vendored trace engine (:core-imaging, :core-vector, :core-pipeline, :core-export) is
    // plain Kotlin/JVM, not Android — see the block in settings.gradle.kts for why. One Kotlin version
    // for every Kotlin plugin here, so the four modules and :app are compiled by one compiler rather
    // than two. 2.4.21 is the newest stable Kotlin as of 2026-10-09 (2.5.0 was at Beta1). Upstream
    // pinned 2.0.21 when the engine was vendored on 2026-08-27, and so did this file until 2026-10-09;
    // the vendored sources compile on 2.4.21 unchanged, which is what keeps them byte-for-byte.
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
