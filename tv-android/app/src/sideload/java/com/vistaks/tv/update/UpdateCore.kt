package com.vistaks.tv.update

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

/*
 * The self-update's pure parts (no Android types, so they run as JVM unit tests): the manifest,
 * when to check, what counts as newer, the download's size / SHA-256 / signer checks and the state
 * machine the page sees. SelfUpdateHost wires them to OkHttp, PackageManager and PackageInstaller.
 *
 * Trust: Android installs an APK over this app only when it is signed with the same key and its
 * versionCode is not lower; that is the main line. On top of it: HTTPS end to end, the APK on the
 * manifest's own host, its size and SHA-256 checked before PackageInstaller sees it, and its
 * signing certificates compared with the installed app's (different: never offered for install).
 */

/** Why an update did not go through. The wire value is what the page maps to plain words. */
enum class UpdateFailure(val wire: String) {
    NETWORK("network"),
    SPACE("space"),
    SIZE("size"),
    CHECKSUM("checksum"),
    SIGNATURE("signature"),
    PACKAGE("package"),
    PERMISSION("permission"),
    INCOMPATIBLE("incompatible"),
    STORAGE("storage"),
    BLOCKED("blocked"),
    INSTALL("install"),
    CANCELLED("cancelled"),
}

class UpdateException(val failure: UpdateFailure, message: String) : IOException(message)

/** `sideload.json`, schema 1: `{ schema, versionName, versionCode, apk: { url, sha256, sizeBytes }, publishedAt, sourceUrl, notes? }`. */
data class UpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAt: String,
    val sourceUrl: String,
    val notes: String,
) {
    companion object {
        const val SCHEMA = 1

        /** The manifest is a few hundred bytes; anything larger is not ours. */
        const val MAX_MANIFEST_BYTES = 16 * 1024

        /** The sideload APK is tens of MB; a manifest that claims more is refused. */
        const val MAX_APK_BYTES = 256L * 1024 * 1024

        /** Play's ceiling, and the time-based versionCode's range (README, versionCode). */
        const val MAX_VERSION_CODE = 2_100_000_000L

        private val VERSION_NAME = Regex("^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /**
         * The manifest at [manifestUrl], or null with the reason logged by the caller: wrong schema,
         * a field missing or out of range, an APK that is not https on the manifest's own host.
         */
        fun parse(json: String, manifestUrl: String): UpdateManifest? = try {
            parseOrThrow(json, manifestUrl)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JSONException) {
            null
        }

        /** As [parse], with the reason (IllegalArgumentException / JSONException). */
        fun parseOrThrow(json: String, manifestUrl: String): UpdateManifest {
            require(json.length <= MAX_MANIFEST_BYTES) { "manifest too large" }
            val root = JSONObject(json)
            val schema = root.opt("schema")
            require(schema is Number && schema.toDouble() == SCHEMA.toDouble()) { "schema $schema is not $SCHEMA" }
            val versionName = root.getString("versionName").trim()
            require(VERSION_NAME.matches(versionName)) { "versionName '$versionName'" }
            val versionCode = root.getLong("versionCode")
            require(versionCode in 1..MAX_VERSION_CODE) { "versionCode $versionCode" }
            val apk = root.getJSONObject("apk")
            val apkUrl = apk.getString("url").trim()
            require(sameHttpsHost(apkUrl, manifestUrl)) { "apk.url is not https on the manifest's host" }
            require(URI(apkUrl).path.orEmpty().endsWith(".apk")) { "apk.url is not an .apk" }
            val sha256 = apk.getString("sha256").trim().lowercase()
            require(SHA256.matches(sha256)) { "apk.sha256" }
            val sizeBytes = apk.getLong("sizeBytes")
            require(sizeBytes in 1..MAX_APK_BYTES) { "apk.sizeBytes $sizeBytes" }
            val sourceUrl = root.optString("sourceUrl", "").trim().takeIf { isHttps(it) }.orEmpty()
            return UpdateManifest(
                versionName = versionName,
                versionCode = versionCode,
                apkUrl = apkUrl,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                publishedAt = root.optString("publishedAt", "").trim().take(40),
                sourceUrl = sourceUrl,
                notes = root.optString("notes", "").trim().take(500),
            )
        }

        fun isHttps(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
        } catch (_: Exception) {
            false
        }

        /** Both https, no user info, the same host and port. */
        fun sameHttpsHost(url: String, manifestUrl: String): Boolean = try {
            val a = URI(url)
            val b = URI(manifestUrl)
            isHttps(url) && isHttps(manifestUrl) && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
        } catch (_: Exception) {
            false
        }
    }
}

/** When to look and what counts. */
object UpdatePolicy {
    /** At most one check per launch, and launches closer than this reuse the last answer. */
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** After start-up, so the check never competes with the page's first load or the first video. */
    const val FIRST_CHECK_DELAY_MS = 45_000L

    /** Room left on the cache volume besides the APK itself. */
    const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024

    /** The download's ceiling while something plays (the video keeps the bandwidth): 512 KiB/s. */
    const val PLAYING_BYTES_PER_SECOND = 512L * 1024

    fun isNewer(manifest: UpdateManifest?, installedVersionCode: Long): Boolean =
        manifest != null && manifest.versionCode > installedVersionCode

    /** A check is due when none was made, the interval has passed, or the clock went backwards. */
    fun checkDue(lastCheckAtMs: Long, nowMs: Long): Boolean =
        lastCheckAtMs <= 0 || nowMs < lastCheckAtMs || nowMs - lastCheckAtMs >= CHECK_INTERVAL_MS

    fun hasSpace(usableBytes: Long, sizeBytes: Long): Boolean = usableBytes >= sizeBytes + SPACE_MARGIN_BYTES

    /**
     * How long to wait before reading more, so that [bytesSoFar] read since [startedAtMs] stays
     * within [bytesPerSecond]; 0 when there is no ceiling or the download is behind it.
     */
    fun throttleDelayMs(bytesSoFar: Long, startedAtMs: Long, nowMs: Long, bytesPerSecond: Long): Long {
        if (bytesPerSecond <= 0) return 0
        val due = startedAtMs + bytesSoFar * 1000 / bytesPerSecond
        return (due - nowMs).coerceIn(0, 1000)
    }
}

/** What a download measured. */
data class DownloadResult(val sizeBytes: Long, val sha256: String)

object UpdateVerifier {
    /**
     * Copies [input] to [output] while hashing it; never more than [expectedBytes] (one byte more
     * fails with SIZE at once). [onChunk] gets the total so far and may sleep (the throttle).
     */
    fun copyHashed(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long,
        onChunk: (Long) -> Unit = {},
    ): DownloadResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > expectedBytes) throw UpdateException(UpdateFailure.SIZE, "more than $expectedBytes bytes")
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            onChunk(total)
        }
        return DownloadResult(total, hex(digest.digest()))
    }

    /** The download against the manifest: null when both match, else the failure. */
    fun check(result: DownloadResult, manifest: UpdateManifest): UpdateFailure? = when {
        result.sizeBytes != manifest.sizeBytes -> UpdateFailure.SIZE
        !MessageDigest.isEqual(result.sha256.toByteArray(), manifest.sha256.toByteArray()) -> UpdateFailure.CHECKSUM
        else -> null
    }

    /** SHA-256 of a certificate's DER bytes, lower-case hex. */
    fun certificateFingerprint(der: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(der))

    /**
     * The downloaded APK may replace the installed app only when its current signers are exactly
     * the installed app's (by certificate fingerprint). An empty side never matches.
     */
    fun sameSigners(installed: Set<String>, archive: Set<String>): Boolean =
        installed.isNotEmpty() && installed == archive

    /** The archive itself: our package, the manifest's versionCode, the same signers. */
    fun checkArchive(
        packageName: String?,
        versionCode: Long,
        signers: Set<String>,
        expectedPackage: String,
        manifest: UpdateManifest,
        installedSigners: Set<String>,
    ): UpdateFailure? = when {
        packageName != expectedPackage || versionCode != manifest.versionCode -> UpdateFailure.PACKAGE
        !sameSigners(installedSigners, signers) -> UpdateFailure.SIGNATURE
        else -> null
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** The page's view of the update (`updateState()`, and the `update` event). */
enum class UpdatePhase(val wire: String) {
    NONE("none"),
    AVAILABLE("available"),
    DOWNLOADING("downloading"),
    READY("ready"),
    INSTALLING("installing"),
    FAILED("failed"),
}

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.NONE,
    val manifest: UpdateManifest? = null,
    val downloadedBytes: Long = 0,
    /** The last failure ('' when none); `cancelled` leaves the phase at AVAILABLE. */
    val reason: String = "",
    /** The viewer put this version aside for this launch (the Home hint stays away). */
    val dismissed: Boolean = false,
    /** READY while something plays: the installer waits for the viewer's 安裝. */
    val waitingForPlayback: Boolean = false,
    /** The download is slowed down because something plays. */
    val throttled: Boolean = false,
)

/**
 * The transitions. Each returns the next state (the same object when the event does not apply);
 * SelfUpdateHost acts on the transition (starts the download, opens the installer).
 */
object UpdateMachine {
    private val BUSY = setOf(UpdatePhase.DOWNLOADING, UpdatePhase.READY, UpdatePhase.INSTALLING)

    /** A manifest arrived (or none could be read: null). A download or install under way is kept. */
    fun onManifest(state: UpdateState, manifest: UpdateManifest?, installedVersionCode: Long): UpdateState {
        if (state.phase in BUSY) return state
        if (!UpdatePolicy.isNewer(manifest, installedVersionCode)) return UpdateState()
        val sameVersion = state.manifest?.versionCode == manifest!!.versionCode
        return UpdateState(
            phase = if (sameVersion && state.phase == UpdatePhase.FAILED) UpdatePhase.FAILED else UpdatePhase.AVAILABLE,
            manifest = manifest,
            reason = if (sameVersion) state.reason else "",
            dismissed = sameVersion && state.dismissed,
        )
    }

    /** 更新: AVAILABLE / FAILED download, READY installs; anything else is left alone. */
    fun onStart(state: UpdateState): UpdateState = when {
        state.manifest == null -> state
        state.phase == UpdatePhase.AVAILABLE || state.phase == UpdatePhase.FAILED ->
            state.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0, reason = "", waitingForPlayback = false)
        state.phase == UpdatePhase.READY -> state.copy(phase = UpdatePhase.INSTALLING, waitingForPlayback = false)
        else -> state
    }

    fun onProgress(state: UpdateState, downloadedBytes: Long, throttled: Boolean): UpdateState =
        if (state.phase != UpdatePhase.DOWNLOADING) state
        else state.copy(downloadedBytes = downloadedBytes, throttled = throttled)

    /** The APK passed every check. [playing]: it waits for the viewer instead of opening the installer. */
    fun onVerified(state: UpdateState, playing: Boolean): UpdateState =
        if (state.phase != UpdatePhase.DOWNLOADING) state
        else state.copy(
            phase = if (playing) UpdatePhase.READY else UpdatePhase.INSTALLING,
            downloadedBytes = state.manifest?.sizeBytes ?: state.downloadedBytes,
            waitingForPlayback = playing,
            throttled = false,
        )

    /** A failure. CANCELLED (the viewer said no in the system's dialog) is not an error: the
     *  version stays offered in 設定, and Home does not ask again this launch. */
    fun onFailure(state: UpdateState, failure: UpdateFailure): UpdateState = when {
        state.manifest == null -> state
        failure == UpdateFailure.CANCELLED ->
            state.copy(phase = UpdatePhase.AVAILABLE, reason = failure.wire, dismissed = true, downloadedBytes = 0, waitingForPlayback = false, throttled = false)
        else ->
            state.copy(phase = UpdatePhase.FAILED, reason = failure.wire, downloadedBytes = 0, waitingForPlayback = false, throttled = false)
    }

    /** 稍後 on Home: this version is not offered there again this launch (設定 still has it). */
    fun onDismiss(state: UpdateState): UpdateState = if (state.manifest == null) state else state.copy(dismissed = true)

    /** The install permission came back (設定 › 安裝未知的應用程式): offer the update again. */
    fun onPermissionGranted(state: UpdateState): UpdateState =
        if (state.phase == UpdatePhase.FAILED && state.reason == UpdateFailure.PERMISSION.wire) state.copy(phase = UpdatePhase.AVAILABLE, reason = "")
        else state

    fun toJson(state: UpdateState, currentVersionName: String): String {
        val manifest = state.manifest
        val total = manifest?.sizeBytes ?: 0L
        val progress = when {
            total <= 0 -> 0.0
            state.phase == UpdatePhase.READY || state.phase == UpdatePhase.INSTALLING -> 1.0
            else -> (state.downloadedBytes.toDouble() / total).coerceIn(0.0, 1.0)
        }
        return JSONObject()
            .put("state", state.phase.wire)
            .put("currentVersion", currentVersionName)
            .put("versionName", manifest?.versionName ?: "")
            .put("versionCode", manifest?.versionCode ?: 0L)
            .put("sizeBytes", total)
            .put("downloadedBytes", state.downloadedBytes)
            .put("progress", Math.round(progress * 1000) / 1000.0)
            .put("reason", state.reason)
            .put("dismissed", state.dismissed)
            .put("waitingForPlayback", state.waitingForPlayback)
            .put("throttled", state.throttled)
            .put("notes", manifest?.notes ?: "")
            .toString()
    }
}
