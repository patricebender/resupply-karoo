import java.util.Base64
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.jetbrains.kotlin.compose)
}

android {
    namespace = "io.resupply.karoo"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.resupply.karoo"
        // Karoo hardware runs Android 12 (API 32); 24 is the floor a current qrose needs.
        minSdk = 24
        targetSdk = 34
        // versionCode: CI injects the monotonic run number; defaults to 1 locally.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.7.0" // x-release-please-version

        // Bundled SQLite ships a native .so, so the APK is ABI-specific. Karoo 3 is
        // arm64-v8a; Karoo 2 is 32-bit armeabi-v7a (Android 8). Ship both or the
        // install fails NO_MATCHING_ABIS on whichever device the APK wasn't built for.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Google Places API key for the on-demand "check hours on Google" feature.
        // Read from local.properties (gitignored) or the PLACES_API_KEY env var; empty
        // when unset, which disables the feature at runtime. Never commit the key.
        val placesKey = run {
            val props = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }
                ?.inputStream()?.use { props.load(it) }
            props.getProperty("PLACES_API_KEY") ?: System.getenv("PLACES_API_KEY") ?: ""
        }
        buildConfigField("String", "PLACES_API_KEY", "\"$placesKey\"")

        // A single "Resupply" app: nothing bundled, the rider downloads regions on demand
        // (fast, over WiFi). The seed machinery (BundledSeed / seedFromAsset) is kept but
        // dormant — these empty values make it a no-op — so a bundled edition can be
        // reintroduced later without re-plumbing. See git history for the flavor setup.
        buildConfigField("String", "SEED_ASSET", "\"\"")
        buildConfigField("String", "SEED_REGION_IDS", "\"\"")
    }

    // Release signing key, from one of two sources (CI wins):
    //  1. CI: a base64 keystore secret + passwords in env vars, decoded to a temp file.
    //  2. Local: a `release.keystore` next to this module, with its passwords/alias in the
    //     gitignored `local.properties` (RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS /
    //     RELEASE_KEY_PASSWORD). This lets a developer build a *production-signed* local APK to
    //     test the real in-app OTA flow (install it, then let the in-app updater replace it with
    //     a GitHub release APK — only possible when both share this signature).
    // Otherwise `release` falls back to debug signing so a plain local `assembleRelease` still
    // produces an installable (dev) APK. Store-distributed builds MUST use this stable key — its
    // signature is permanent once riders install.
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val hasCiKeystore = !System.getenv("KEYSTORE_BASE64").isNullOrBlank()
    // Local base64 keystore in local.properties — intended to hold the SAME key CI uses, so a
    // developer can build a production-signed APK and test the real in-app OTA (a GitHub release
    // can only replace it in place when both share this signature).
    val localKeystoreB64 = localProps.getProperty("RELEASE_KEYSTORE_B64")?.takeIf { it.isNotBlank() }
    val hasLocalKeystore = localKeystoreB64 != null &&
        localProps.getProperty("RELEASE_STORE_PASSWORD") != null
    signingConfigs {
        create("release") {
            if (hasCiKeystore) {
                val keystoreFile = File.createTempFile("keystore", ".jks")
                keystoreFile.writeBytes(Base64.getDecoder().decode(System.getenv("KEYSTORE_BASE64")))
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else if (hasLocalKeystore) {
                val keystoreFile = File.createTempFile("keystore", ".jks")
                keystoreFile.writeBytes(Base64.getDecoder().decode(localKeystoreB64!!.trim()))
                storeFile = keystoreFile
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                // Key password defaults to the store password when unset (common for single-key stores).
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
                    ?: localProps.getProperty("RELEASE_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Use the real release key when CI or a local keystore provided one; otherwise fall
            // back to debug signing so local `assembleRelease` still produces an installable APK.
            // The CI-must-have-a-keystore guard lives at task-execution time (see below), not
            // here: this block is evaluated whenever the project is configured — including plain
            // `testDebugUnitTest` runs that need no keystore — so throwing here would break
            // unrelated CI jobs.
            signingConfig = if (hasCiKeystore || hasLocalKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(libs.karoo.ext)
    implementation(libs.timber)
    implementation(libs.qrose)
    implementation(libs.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sqlite.android)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.glance.appwidget)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}

// --- Karoo extension library manifest ---------------------------------------
//
// The official Karoo Extensions library installs/updates an extension from a
// `manifest.json` attached to its GitHub release, discovered via the
// `io.hammerhead.karooext.MANIFEST_URL` meta-data in AndroidManifest.xml. This
// task generates that manifest and, in CI (when BASE_URL is set), substitutes
// the `$BASE_URL$` placeholder in AndroidManifest.xml with the release download
// prefix. See docs/releasing.md and the headwind extension for the schema.
tasks.register("generateManifest") {
    description = "Generates manifest.json for the Karoo extensions library."
    group = "karoo"

    doLast {
        // Release-asset download prefix. Locally defaults to the `latest` release so a
        // hand-built manifest points somewhere valid; CI overrides per tag.
        val baseUrl = System.getenv("BASE_URL")
            ?: "https://github.com/patricebender/resupply-karoo/releases/latest/download"

        // First bullet block of the newest CHANGELOG entry, as the library's "what's new".
        // Beautified for display: the CHANGELOG is release-please Markdown with trailing PR/commit
        // refs (`([#107](url)) ([d68cdf7](url))`) — strip those and reduce any `[text](url)` to its
        // text, so the manifest carries clean prose. (The app also strips these at runtime in
        // MainActivity.releaseNotes(); baking it keeps the two in sync and the field tiny.)
        val releaseNotes = runCatching {
            val changelog = rootProject.file("CHANGELOG.md")
            if (!changelog.exists()) return@runCatching ""
            changelog.readLines()
                .dropWhile { !it.startsWith("## ") }        // to the first version heading
                .drop(1)
                .takeWhile { !it.startsWith("## ") }        // until the next version heading
                .filter { it.startsWith("* ") || it.startsWith("- ") }
                .map { line ->
                    line.replace(Regex("""\s*\(\[[0-9a-f]{6,}]\([^)]*\)\)"""), "") // ([hash](url))
                        .replace(Regex("""\s*\(\[#\d+]\([^)]*\)\)"""), "")          // ([#123](url))
                        .replace(Regex("""\[([^]]+)]\([^)]*\)"""), "$1")            // [text](url) -> text
                        .replace(Regex("""\s{2,}"""), " ")
                        .trimEnd()
                }
                .joinToString("\n")
        }.getOrDefault("")

        // Screenshots are release assets named preview*.png next to this module. None yet;
        // drop them into extension/app/ and they'll be listed + attached automatically.
        val screenshotUrls = projectDir.listFiles { f -> f.name.matches(Regex("preview.*\\.png")) }
            ?.sortedBy { it.name }
            ?.map { "$baseUrl/${it.name}" }
            ?: emptyList()

        val manifest = linkedMapOf(
            "label" to "Resupply",
            "packageName" to android.defaultConfig.applicationId,
            "iconUrl" to "$baseUrl/resupply.png",
            "latestApkUrl" to "$baseUrl/app-release.apk",
            "latestVersion" to android.defaultConfig.versionName,
            "latestVersionCode" to android.defaultConfig.versionCode,
            "developer" to "github.com/patricebender",
            "description" to "Turns a loaded route into an offline guide of POIs along the way " +
                "(water, food, bike shops, fuel and more), with in-ride data fields and a map layer. " +
                "Download the regions you ride on demand.",
            "releaseNotes" to releaseNotes,
            "screenshotUrls" to screenshotUrls,
            "tags" to listOf("navigation", "poi"),
        )

        val json = groovy.json.JsonBuilder(manifest).toPrettyString()
        file("$projectDir/manifest.json").writeText(json)
        println("Generated manifest.json for ${manifest["latestVersion"]} (${manifest["latestVersionCode"]})")

        // Only rewrite the checked-in AndroidManifest in CI, where BASE_URL is set — never
        // leave a substituted placeholder in a local working tree.
        if (System.getenv("BASE_URL") != null) {
            val androidManifest = file("$projectDir/src/main/AndroidManifest.xml")
            androidManifest.writeText(
                androidManifest.readText().replace("\$BASE_URL\$", baseUrl)
            )
            println("Substituted \$BASE_URL\$ in AndroidManifest.xml -> $baseUrl")
        }
    }
}

// The manifest URL must be substituted before the manifest is merged into the APK.
tasks.matching { it.name == "processReleaseMainManifest" || it.name == "processDebugMainManifest" }
    .configureEach { dependsOn("generateManifest") }

// Guard: a release APK built in CI without a keystore is debug-signed with a throwaway key
// that changes every run, so no release can install over another ("different signing") and
// the in-app auto-update silently breaks. Fail the actual release-packaging task instead of
// shipping it. This runs only when a release APK is really being assembled — not on plain
// `testDebugUnitTest`, which configures the project but never triggers these tasks.
tasks.matching { it.name == "packageRelease" || it.name == "assembleRelease" }
    .configureEach {
        doFirst {
            if (System.getenv("CI") == "true" && System.getenv("KEYSTORE_BASE64").isNullOrBlank()) {
                throw GradleException(
                    "Release build in CI requires KEYSTORE_BASE64 — refusing to debug-sign a release.",
                )
            }
        }
    }
