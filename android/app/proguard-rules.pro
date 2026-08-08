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

# ── What R8 removed, written down where a human can read it ─────────────────────────────────────
#
# Not diagnostics for their own sake: the ONLY way to check a shrunk build without a device is to
# read what was dropped and confirm nothing load-bearing is in the list. `mapping.txt` is also the
# file a future crash report has to be de-obfuscated against, and a release whose mapping was not
# kept is a stack trace nobody can read.
-printusage build/outputs/mapping/release/r8-removed.txt
-printseeds build/outputs/mapping/release/r8-kept.txt
