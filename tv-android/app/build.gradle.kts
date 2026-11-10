import com.android.build.api.variant.BuildConfigField
import java.time.Duration
import java.time.Instant
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val productionTvUrl = "https://vistaks.com/tv"

// Debug-only override: -PvistaksTvUrl=http://192.168.x.x:5183/tv.html (or gradle.properties).
val debugTvUrl: String = (project.findProperty("vistaksTvUrl") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: productionTvUrl

fun javaStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// ---------------------------------------------------------------------------
// versionCode
//
// Release builds get versionCode = whole minutes elapsed since 2026-01-01T00:00Z (UTC), computed
// when Gradle configures the build. Nothing is written to the repo, the value only ever grows, and
// Play's ceiling (2,100,000,000) is about 3,990 years away. Two release builds inside the same
// minute share a code, so wait a minute before rebuilding for a second upload.
// Debug builds stay at 1 so incremental debug builds are not invalidated every minute.
// Emergency override (any variant): -PvistaksTvVersionCode=<1..2100000000>.
// ---------------------------------------------------------------------------
val playMaxVersionCode = 2_100_000_000L
val versionCodeEpoch: Instant = Instant.parse("2026-01-01T00:00:00Z")

val versionCodeOverride: Int? = (project.findProperty("vistaksTvVersionCode") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { raw ->
        val parsed = raw.toLongOrNull()
        if (parsed == null || parsed < 1 || parsed > playMaxVersionCode) {
            throw GradleException(
                "-PvistaksTvVersionCode must be an integer between 1 and $playMaxVersionCode (got '$raw').",
            )
        }
        parsed.toInt()
    }

val releaseVersionCode: Int = versionCodeOverride ?: run {
    val minutes = Duration.between(versionCodeEpoch, Instant.now()).toMinutes()
    check(minutes in 1..playMaxVersionCode) {
        "Time-based versionCode $minutes is out of range; check the system clock or pass -PvistaksTvVersionCode."
    }
    minutes.toInt()
}

// ---------------------------------------------------------------------------
// Upload-key signing for release (Play App Signing re-signs with the Google-held app key).
//
// Each value is read from its environment variable first, then from the gitignored
// tv-android/keystore.properties:
//   storeFile     / VISTAKS_TV_UPLOAD_STORE_FILE      (relative paths resolve against tv-android/)
//   storePassword / VISTAKS_TV_UPLOAD_STORE_PASSWORD
//   keyAlias      / VISTAKS_TV_UPLOAD_KEY_ALIAS
//   keyPassword   / VISTAKS_TV_UPLOAD_KEY_PASSWORD
// Without a complete set, release still configures (and assemble<Flavor>Release still yields an
// unsigned APK), but every bundle<Flavor>Release fails before doing any work: never ship an
// unsigned or debug-signed AAB. Play uploads use bundlePlayRelease.
// ---------------------------------------------------------------------------
val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun uploadKeyValue(propertyKey: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotEmpty() }
        ?: keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotEmpty() }

val uploadStoreFilePath = uploadKeyValue("storeFile", "VISTAKS_TV_UPLOAD_STORE_FILE")
val uploadStorePassword = uploadKeyValue("storePassword", "VISTAKS_TV_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = uploadKeyValue("keyAlias", "VISTAKS_TV_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = uploadKeyValue("keyPassword", "VISTAKS_TV_UPLOAD_KEY_PASSWORD")
val uploadStoreFile: File? = uploadStoreFilePath?.let { rootProject.file(it) }

val uploadKeyProblems: List<String> = buildList {
    if (uploadStoreFilePath == null) add("storeFile (VISTAKS_TV_UPLOAD_STORE_FILE) is not set")
    else if (uploadStoreFile?.isFile != true) add("storeFile does not exist: ${uploadStoreFile?.absolutePath}")
    if (uploadStorePassword == null) add("storePassword (VISTAKS_TV_UPLOAD_STORE_PASSWORD) is not set")
    if (uploadKeyAlias == null) add("keyAlias (VISTAKS_TV_UPLOAD_KEY_ALIAS) is not set")
    if (uploadKeyPassword == null) add("keyPassword (VISTAKS_TV_UPLOAD_KEY_PASSWORD) is not set")
}
val hasUploadKey = uploadKeyProblems.isEmpty()

// ---------------------------------------------------------------------------
// Self-update channel (sideload flavor only).
//
// The updater checks one manifest URL. It is never part of the source: a build without one has no
// updater at all (the bridge is not even registered), which is what a build from the published
// source gets. Release builds read, in this order:
//   VISTAKS_TV_UPDATE_MANIFEST_URL (environment) / -PvistaksTvUpdateManifestUrl / the gitignored
//   tv-android/update.properties (manifestUrl=https://...).
// Debug builds never read update.properties: they get a manifest only from
//   -PvistaksTvDebugUpdateManifestUrl / VISTAKS_TV_DEBUG_UPDATE_MANIFEST_URL
// (an update test against a test manifest), so a debug build never meets the release channel.
// ---------------------------------------------------------------------------
val updatePropertiesFile: File = rootProject.file("update.properties")
val updateProperties = Properties().apply {
    if (updatePropertiesFile.isFile) {
        updatePropertiesFile.inputStream().use { load(it) }
    }
}

fun checkedManifestUrl(raw: String?, origin: String): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return ""
    if (!Regex("^https://[A-Za-z0-9.-]+(?::\\d+)?/[A-Za-z0-9._~/-]+\\.json$").matches(value)) {
        throw GradleException("$origin must be an https:// URL of a .json manifest (got '$value').")
    }
    return value
}

val releaseUpdateManifestUrl: String = run {
    System.getenv("VISTAKS_TV_UPDATE_MANIFEST_URL")?.takeIf { it.isNotBlank() }
        ?.let { checkedManifestUrl(it, "VISTAKS_TV_UPDATE_MANIFEST_URL") }
        ?: (project.findProperty("vistaksTvUpdateManifestUrl") as String?)?.takeIf { it.isNotBlank() }
            ?.let { checkedManifestUrl(it, "-PvistaksTvUpdateManifestUrl") }
        ?: checkedManifestUrl(updateProperties.getProperty("manifestUrl"), "update.properties manifestUrl")
}

val debugUpdateManifestUrl: String = run {
    (project.findProperty("vistaksTvDebugUpdateManifestUrl") as String?)?.takeIf { it.isNotBlank() }
        ?.let { checkedManifestUrl(it, "-PvistaksTvDebugUpdateManifestUrl") }
        ?: checkedManifestUrl(System.getenv("VISTAKS_TV_DEBUG_UPDATE_MANIFEST_URL"), "VISTAKS_TV_DEBUG_UPDATE_MANIFEST_URL")
}

android {
    namespace = "com.vistaks.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vistaks.tv"
        minSdk = 26
        targetSdk = 36
        // Debug default; release variants get releaseVersionCode (see androidComponents below).
        versionCode = versionCodeOverride ?: 1
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = uploadStoreFile
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    // ADR-0100: two distributions of one app (both com.vistaks.tv, so a TV never shows two).
    //   sideload: the friends' APK; native 4K player (Media3 + NewPipeExtractor), GPL-3.0-or-later.
    //   play:     the Play upload (bundlePlayRelease); iframe only, no extractor, no GPL code.
    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            buildConfigField("boolean", "NATIVE_PLAYER", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "NATIVE_PLAYER", "false")
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "TV_URL", javaStringLiteral(debugTvUrl))
        }
        release {
            // Never fall back to the debug key: no upload key means no signing config at all.
            signingConfig = if (hasUploadKey) signingConfigs.getByName("upload") else null
            buildConfigField("String", "TV_URL", javaStringLiteral(productionTvUrl))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor needs java.nio APIs below minSdk 33 (desugar_jdk_libs_nio).
        isCoreLibraryDesugaringEnabled = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output -> output.versionCode.set(releaseVersionCode) }
    }
    // The self-update manifest (see above): sideload only. The play flavor has no such field, no
    // updater classes and no install permission (scripts/test_tv_self_update_contract.mjs).
    onVariants(selector().withFlavor("distribution" to "sideload")) { variant ->
        val url = if (variant.buildType == "release") releaseUpdateManifestUrl else debugUpdateManifestUrl
        variant.buildConfigFields?.put(
            "UPDATE_MANIFEST_URL",
            BuildConfigField("String", javaStringLiteral(url), "self-update manifest; empty = no updater"),
        )
    }
}

// Fail fast (before any task runs) when a release bundle is requested without a usable upload key.
// Matched by name pattern, not by a fixed name: with product flavors the tasks are
// bundlePlayRelease / bundleSideloadRelease and signPlayReleaseBundle / signSideloadReleaseBundle,
// and a fixed "bundleRelease" would silently never match (ADR-0100 S0, Gradle 6).
gradle.taskGraph.whenReady {
    fun isReleaseBundleTask(name: String): Boolean =
        (name.startsWith("bundle") && name.endsWith("Release")) ||
            (name.startsWith("sign") && name.endsWith("ReleaseBundle"))
    val releaseBundleTasks = allTasks.filter { it.project == project && isReleaseBundleTask(it.name) }
    if (releaseBundleTasks.isNotEmpty() && !hasUploadKey) {
        throw GradleException(
            buildString {
                val requested = releaseBundleTasks.map { it.name }.sorted().joinToString(", ")
                appendLine("Release bundles need the Play upload key (Play uploads use bundlePlayRelease), and none is configured.")
                appendLine("Requested: $requested")
                uploadKeyProblems.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Create it once (you type the passwords yourself):")
                appendLine("  cd tv-android")
                appendLine("  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\create-upload-key.ps1")
                appendLine("That writes tv-android\\upload-keystore.jks and (optionally) tv-android\\keystore.properties.")
                appendLine("Or set VISTAKS_TV_UPLOAD_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD.")
                append("See tv-android/README.md (上架 Play 內部測試).")
            },
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // JVM unit tests (tv-android/app/src/testSideload: the HDR track supplement, TV-S4H).
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM unit tests (android.jar's copy is a stub that throws): the
    // self-update manifest parser and state JSON (testSideload/.../update).
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Native 4K player (ADR-0100), sideload flavor only: the play APK must not carry any of it.
    "sideloadImplementation"("androidx.media3:media3-exoplayer:1.11.1")
    "sideloadImplementation"("androidx.media3:media3-exoplayer-dash:1.11.1")
    "sideloadImplementation"("androidx.media3:media3-ui:1.11.1")
    // OkHttp 4.12.0 (last 4.x), not 5.x: 5.5 needs compileSdk 37 (past AGP 8.11). Move to 5.x
    // together with an AGP upgrade.
    "sideloadImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
    "sideloadImplementation"("com.github.teamnewpipe:NewPipeExtractor:v0.27.0")
}
