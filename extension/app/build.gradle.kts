import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.jetbrains.kotlin.compose)
}

android {
    namespace = "io.resupply.karoo"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.resupply.karoo"
        minSdk = 23
        targetSdk = 34
        // versionCode: CI injects the monotonic run number; defaults to 1 locally.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0.0" // x-release-please-version

        // Karoo is arm64 — only ship that ABI of the bundled SQLite native lib.
        ndk {
            abiFilters += "arm64-v8a"
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
    }

    // Release signing key. In CI the keystore is a base64 secret decoded to a temp file;
    // locally there's usually no key, so `release` falls back to debug signing below and
    // sideloaded dev builds keep installing without a keystore. Store-distributed builds
    // MUST be signed with this stable key — its signature is permanent once riders install.
    signingConfigs {
        create("release") {
            val base64Keystore = System.getenv("KEYSTORE_BASE64")
            if (!base64Keystore.isNullOrBlank()) {
                val keystoreFile = File.createTempFile("keystore", ".jks")
                keystoreFile.writeBytes(Base64.getDecoder().decode(base64Keystore))
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Use the real release key when CI provided a keystore; otherwise fall back to
            // debug signing so local `assembleRelease` still produces an installable APK.
            signingConfig = if (System.getenv("KEYSTORE_BASE64").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.karoo.ext)
    implementation(libs.timber)
    implementation(libs.qrose)

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
        val releaseNotes = runCatching {
            val changelog = rootProject.file("CHANGELOG.md")
            if (!changelog.exists()) return@runCatching ""
            changelog.readLines()
                .dropWhile { !it.startsWith("## ") }        // to the first version heading
                .drop(1)
                .takeWhile { !it.startsWith("## ") }        // until the next version heading
                .filter { it.startsWith("* ") || it.startsWith("- ") }
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
                "(water, food, bike shops, fuel and more), with in-ride data fields and a map layer.",
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
