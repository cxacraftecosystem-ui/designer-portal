# R8 rules for the Design Prototype Workshop handset app.
#
# DELIBERATELY SHORT, and that is the point rather than an omission. Every library this app uses
# that is reached reflectively already SHIPS ITS OWN consumer rules inside its artifact, and R8
# applies those automatically:
#
#   kotlinx-serialization-core-jvm-1.7.3.jar  META-INF/proguard/kotlinx-serialization-common.pro
#                                             META-INF/com.android.tools/proguard/…
#   retrofit-2.11.0.jar                       META-INF/proguard/retrofit2.pro
#   okhttp-4.12.0.jar                         META-INF/proguard/okhttp3.pro
#
# (verified by unzipping them out of the Gradle cache, not assumed). Compose's rules come with AGP.
# Copying those rules in here would create a SECOND copy that silently goes stale the next time a
# dependency is upgraded, which is the failure this file exists to avoid rather than cause.
#
# What remains below is only what is specific to THIS application.

# ── Our own serialized wire types ───────────────────────────────────────────────────────────────
#
# kotlinx's own rules keep the `$$serializer` machinery generically, and in a correct build that is
# enough. This is the belt to that braces, and it is here because of what the failure looks like: a
# stripped serializer does not fail at build time or at start-up — it throws
# `SerializationException` the first time a designer syncs, in a village, with a stage full of a
# day's fieldwork behind it, on a build that installed and ran fine on a desk. The cost of keeping
# 12 data-holder classes is a few kilobytes; the cost of being wrong is a day's work that cannot be
# sent.
-keepclassmembers @kotlinx.serialization.Serializable class com.designprototype.workshop.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.designprototype.workshop.**$$serializer { *; }
-keepclasseswithmembers class com.designprototype.workshop.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Retrofit service interfaces ─────────────────────────────────────────────────────────────────
#
# Reached through `Retrofit.create(WorkshopRepositoryApi::class.java)` (ApiClient.kt:42) and
# `.create(SttProviderApi::class.java)` (TranscriptionProviders.kt:168) — a dynamic proxy, so
# nothing statically calls these methods and R8 cannot see that they are used. Retrofit's own rules
# cover the annotations and the generic signatures; this covers OUR interfaces by name.
-keep,allowobfuscation interface com.designprototype.workshop.data.WorkshopRepositoryApi { *; }
-keep,allowobfuscation interface com.designprototype.workshop.ui.SttProviderApi { *; }

# ── ML Kit's own components, which are found by NAME out of the merged manifest ─────────────────
#
# THE ONE RULE IN THIS FILE THAT COVERS SOMEBODY ELSE'S LIBRARY, and it is here because ML Kit does
# not ship it and R8 cannot possibly infer it. ML Kit boots by reading `<meta-data>` entries off its
# own `MlKitComponentDiscoveryService` and calling `Class.forName` on the class names spelled inside
# the meta-data KEY. Three of them are pulled in by `com.google.mlkit:text-recognition`:
#
#   com.google.mlkit.common.internal.CommonComponentRegistrar        (com.google.mlkit:common)
#   com.google.mlkit.vision.common.internal.VisionCommonRegistrar    (com.google.mlkit:vision-common)
#   com.google.mlkit.vision.text.internal.TextRegistrar              (play-services-mlkit-text-recognition-common)
#
# Nothing in this application, or in ML Kit, references any of them from code. AGP keeps the SERVICE
# — it is an `android:name` on a manifest component — but a class name that appears only inside a
# meta-data attribute is a string, and R8 does not read strings.
#
# BELT AND BRACES, NOT THE ONLY BRACE — and the earlier version of this comment was wrong about that.
# It claimed that "every proguard.txt in the resolved ML Kit graph was read ... none of them keeps a
# ComponentRegistrar". One does. `com.google.firebase:firebase-components:16.1.0` sits squarely
# inside this graph — `play-services-mlkit-text-recognition-common`, `com.google.mlkit:common` and
# `com.google.mlkit:vision-common` each depend on it (`:app:dependencies --configuration
# releaseRuntimeClasspath`) — and its consumer `proguard.txt` is three lines, of which the third is:
#
#     -keep class * implements com.google.firebase.components.ComponentRegistrar
#
# Read out of the release build's OWN merged configuration rather than from the cache alone:
# `app/build/outputs/mapping/release/configuration.txt` carries it under the
# `firebase-components-16.1.0/proguard.txt` banner. So the registrars would have survived R8 without
# anything in this file, and the claim that they would not was not checked as thoroughly as it said
# it was.
#
# THE RULE STAYS, for the one thing the library rule does not say. `-keep class *` with no member
# specification keeps the class; ML Kit reaches these through `Class.forName(name).newInstance()`,
# which needs the NO-ARG CONSTRUCTOR as well, and that is what the body below adds. Keeping a
# constructor that would probably have survived anyway costs nothing measurable; discovering in a
# courtyard that it did not would cost the whole feature. What it must not do is claim to be the
# only thing standing between this build and a crash.
#
# Confirmed in the release build's own output, not asserted: `r8-kept.txt` (`-printseeds`) lists all
# three classes AND their no-arg constructors, `mapping.txt` maps each to ITSELF (so `Class.forName`
# still finds them by name), and `r8-removed.txt` shows only a synthetic `int zza` trimmed from each.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}

# ── What R8 removed, written down where a human can read it ─────────────────────────────────────
#
# Not diagnostics for their own sake: the ONLY way to check a shrunk build without a device is to
# read what was dropped and confirm nothing load-bearing is in the list. `mapping.txt` is also the
# file a future crash report has to be de-obfuscated against, and a release whose mapping was not
# kept is a stack trace nobody can read.
-printusage build/outputs/mapping/release/r8-removed.txt
-printseeds build/outputs/mapping/release/r8-kept.txt
