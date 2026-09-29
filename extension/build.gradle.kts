// Top-level build file.
//
// AGP 9 has built-in Kotlin support, so the `org.jetbrains.kotlin.android` plugin is gone
// (see app/build.gradle.kts). AGP 9.4 bundles KGP 2.2.x; we pin the compiler up to the
// version in libs.versions.toml via the buildscript classpath below so all Kotlin plugins
// (compose, serialization) and the compiler agree on one version.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.serialization) apply false
    alias(libs.plugins.jetbrains.kotlin.compose) apply false
}
