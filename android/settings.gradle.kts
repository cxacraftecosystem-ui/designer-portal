pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        /**
         * THE SPEECH ENGINE, VENDORED, BECAUSE IT CANNOT BE RESOLVED ANY OTHER WAY.
         *
         * `docs/ASR-RUNTIME-MEASUREMENT.md` §1 asked the Gradle resolver itself — six plausible
         * coordinates for `sherpa-onnx`, six live 404s from `google()` and `mavenCentral()`, and
         * Maven Central's own search index answering `numFound: 0`. Upstream publishes the Android
         * build as a **GitHub release asset** and nothing else. That document listed three ways
         * forward and called each of them somebody's decision; this is the first of them — vendor
         * the binary — taken deliberately and recorded here rather than in a commit message.
         *
         * `repositoriesMode` above is `FAIL_ON_PROJECT_REPOS`, so a `repositories { flatDir { … } }`
         * block inside `app/build.gradle.kts` would fail the build outright. That is the reason the
         * declaration is HERE and not beside the dependency that uses it: the settings file is the
         * only place this project permits a repository to be declared, and weakening the mode to
         * put it next to its dependency would remove the guard that keeps every other dependency
         * coming from a repository the whole project agreed on.
         *
         * A `flatDir` repository resolves by FILE NAME and carries no POM, so it brings no
         * transitive dependencies with it. For this artifact that is correct rather than a
         * limitation — the static-link AAR is self-contained (`docs/ASR-RUNTIME-MEASUREMENT.md` §3
         * read its central directory: four `.so` files per ABI, one `classes.jar`, and nothing
         * else) — but it is exactly the trap a future reader falls into if they vendor something
         * that DOES have dependencies and finds it failing at run time rather than at build time.
         */
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "DesignPrototypeWorkshopAndroid"
include(":app")
