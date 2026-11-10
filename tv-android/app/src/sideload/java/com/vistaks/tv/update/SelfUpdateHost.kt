package com.vistaks.tv.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.vistaks.tv.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The sideload APK's self-update (ADR-0094 amendment 2026-11-10, ADR-0100 §3). It reads one
 * manifest (BuildConfig.UPDATE_MANIFEST_URL, injected at build time; empty = no updater, no bridge),
 * at most once per launch and at most every six hours, never during start-up. A newer versionCode
 * is offered to the page; the download starts only when the viewer presses 更新, slows down while
 * something plays, and is checked (size, SHA-256, package, versionCode, signers) before
 * PackageInstaller sees it. The system's own confirmation dialog does the install.
 *
 * The public signature matches the play flavor's stub (scripts/test_tv_self_update_contract.mjs):
 * MainActivity compiles against both.
 */
class SelfUpdateHost(
    private val activity: Activity,
    private val emit: (event: String, json: String) -> Unit, // main thread; MainActivity forwards to window.VistaksTv.native
) {
    private val manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "vistaks-update").apply { isDaemon = true } }
    private val prefs by lazy { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val lock = Any()
    private var state = UpdateState()
    @Volatile private var stateJson: String = UpdateMachine.toJson(state, BuildConfig.VERSION_NAME)

    @Volatile private var playing = false
    private var checkedThisLaunch = false
    private var released = false

    /** The installer's confirmation, kept so 更新 can open it again (the viewer pressed Home on it). */
    private var pendingConfirm: Intent? = null
    private var sessionId = -1
    private var receiverRegistered = false

    private val firstCheck = Runnable { worker.execute { check() } }

    /** The bridge object (`window.VistaksTvUpdate`), or null: no manifest configured, no updater. */
    fun bridge(): Any? = if (manifestUrl.isEmpty()) null else Bridge()

    /** Start-up: old downloads go; the check is scheduled after the page's first load. */
    fun onCreate(forceCheck: Boolean) {
        if (manifestUrl.isEmpty()) return
        worker.execute { clearDownloads() }
        // Debug only (an update test on a device): check now, past the interval.
        val force = BuildConfig.DEBUG && forceCheck
        if (force) prefs.edit().remove(KEY_LAST_CHECK).apply()
        main.postDelayed(firstCheck, if (force) 3_000L else UpdatePolicy.FIRST_CHECK_DELAY_MS)
    }

    /** Back in front: the viewer may have allowed installs, or left the installer's dialog. */
    fun onResume() {
        if (manifestUrl.isEmpty() || released) return
        if (activity.packageManager.canRequestPackageInstalls()) update { UpdateMachine.onPermissionGranted(it) }
        if (current().phase == UpdatePhase.INSTALLING && pendingConfirm != null) {
            // The dialog closed without a word (some versions send nothing on Back): when the
            // session is gone and we are still running, nothing was installed.
            main.postDelayed({ settleInstall() }, 2_500L)
        }
    }

    /** The page's keep-awake lease: something plays. */
    fun setPlaybackActive(active: Boolean) {
        playing = active
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try { activity.unregisterReceiver(installReceiver) } catch (_: IllegalArgumentException) {}
            receiverRegistered = false
        }
        worker.shutdownNow()
    }

    // --- the check ------------------------------------------------------------------------------

    private fun check() {
        if (checkedThisLaunch || released) return
        checkedThisLaunch = true
        val installed = installedVersionCode()
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!UpdatePolicy.checkDue(last, now)) {
            val cached = prefs.getString(KEY_MANIFEST, null)
            val manifest = cached?.let { UpdateManifest.parse(it, manifestUrl) }
            Log.i(TAG, "check skipped (last ${(now - last) / 60_000} min ago); cached ${manifest?.versionCode ?: "none"}, installed $installed")
            update { UpdateMachine.onManifest(it, manifest, installed) }
            return
        }
        val body = try {
            val request = Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").header("Accept", "application/json").build()
            http.newCall(request).execute().use { response ->
                if (response.code != 200) throw UpdateException(UpdateFailure.NETWORK, "HTTP ${response.code}")
                val source = response.body ?: throw UpdateException(UpdateFailure.NETWORK, "no body")
                val bytes = source.byteStream().readNBytesCompat(UpdateManifest.MAX_MANIFEST_BYTES + 1)
                if (bytes.size > UpdateManifest.MAX_MANIFEST_BYTES) throw UpdateException(UpdateFailure.NETWORK, "manifest too large")
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            // Quiet: no network, no manifest yet (404), anything. The next launch tries again.
            Log.i(TAG, "check failed on ${hostOf(manifestUrl)}: ${e.message}")
            return
        }
        val manifest = try {
            UpdateManifest.parseOrThrow(body, manifestUrl)
        } catch (e: Exception) {
            Log.w(TAG, "manifest refused: ${e.message}")
            null
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).putString(KEY_MANIFEST, if (manifest != null) body else null).apply()
        Log.i(TAG, "checked: manifest ${manifest?.versionCode ?: "none"}, installed $installed")
        update { UpdateMachine.onManifest(it, manifest, installed) }
    }

    // --- 更新 -----------------------------------------------------------------------------------

    private fun beginUpdate() {
        val before = current()
        if (before.phase == UpdatePhase.INSTALLING) {
            pendingConfirm?.let { main.post { openConfirm(it) } }
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // 安裝未知的應用程式 is off for Vistaks: the system's own page for it, when this TV has
            // one; without one, go on and let the installer ask.
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            if (settings.resolveActivity(activity.packageManager) != null) {
                update { UpdateMachine.onFailure(it, UpdateFailure.PERMISSION) }
                main.post {
                    try { activity.startActivity(settings) } catch (_: ActivityNotFoundException) {}
                }
                return
            }
        }
        val next = update { UpdateMachine.onStart(it) }
        when {
            before.phase != UpdatePhase.READY && next.phase == UpdatePhase.DOWNLOADING -> worker.execute { download(next.manifest!!) }
            before.phase == UpdatePhase.READY && next.phase == UpdatePhase.INSTALLING -> worker.execute { install(next.manifest!!) }
        }
    }

    private fun download(manifest: UpdateManifest) {
        val dir = updatesDir()
        clearDownloads()
        dir.mkdirs()
        if (!UpdatePolicy.hasSpace(dir.usableSpace, manifest.sizeBytes)) return fail(UpdateFailure.SPACE, "usable ${dir.usableSpace}")
        val part = File(dir, "vistaks-tv-${manifest.versionCode}.apk.part")
        val result = try {
            val request = Request.Builder().url(manifest.apkUrl).header("Accept", "application/vnd.android.package-archive, */*").build()
            http.newCall(request).execute().use { response ->
                if (response.code != 200) throw UpdateException(UpdateFailure.NETWORK, "HTTP ${response.code}")
                val body = response.body ?: throw UpdateException(UpdateFailure.NETWORK, "no body")
                val announced = body.contentLength()
                if (announced >= 0 && announced != manifest.sizeBytes) throw UpdateException(UpdateFailure.SIZE, "Content-Length $announced")
                var windowStart = System.currentTimeMillis()
                var windowBase = 0L
                var windowPlaying = playing
                var lastEmit = 0L
                part.outputStream().use { out ->
                    UpdateVerifier.copyHashed(body.byteStream(), out, manifest.sizeBytes) { total ->
                        if (released) throw UpdateException(UpdateFailure.NETWORK, "released")
                        val now = System.currentTimeMillis()
                        if (playing != windowPlaying) { windowPlaying = playing; windowStart = now; windowBase = total }
                        if (windowPlaying) {
                            val wait = UpdatePolicy.throttleDelayMs(total - windowBase, windowStart, now, UpdatePolicy.PLAYING_BYTES_PER_SECOND)
                            if (wait > 0) Thread.sleep(wait)
                        }
                        if (now - lastEmit >= PROGRESS_EMIT_MS) {
                            lastEmit = now
                            update { UpdateMachine.onProgress(it, total, windowPlaying) }
                        }
                    }
                }
            }
        } catch (e: UpdateException) {
            part.delete()
            return fail(e.failure, e.message ?: "")
        } catch (e: Exception) {
            part.delete()
            return fail(if (dir.usableSpace < manifest.sizeBytes) UpdateFailure.SPACE else UpdateFailure.NETWORK, e.toString())
        }
        UpdateVerifier.check(result, manifest)?.let { failure ->
            part.delete()
            return fail(failure, "got ${result.sizeBytes} bytes sha256 ${result.sha256.take(12)}…")
        }
        val apk = File(dir, "vistaks-tv-${manifest.versionCode}.apk")
        if (!part.renameTo(apk)) {
            part.delete()
            return fail(UpdateFailure.STORAGE, "rename")
        }
        val archive = archiveInfo(apk)
        val failure = UpdateVerifier.checkArchive(
            packageName = archive?.packageName,
            versionCode = archive?.let { versionCodeOf(it) } ?: -1,
            signers = archive?.let { signersOf(it) } ?: emptySet(),
            expectedPackage = activity.packageName,
            manifest = manifest,
            installedSigners = installedSigners(),
        )
        if (failure != null) {
            apk.delete()
            return fail(failure, "archive ${archive?.packageName} ${archive?.let { versionCodeOf(it) }}")
        }
        val next = update { UpdateMachine.onVerified(it, playing) }
        Log.i(TAG, "verified ${manifest.versionName} (${manifest.versionCode}); ${if (next.phase == UpdatePhase.READY) "waiting: something plays" else "installing"}")
        if (next.phase == UpdatePhase.INSTALLING) install(manifest)
    }

    private fun install(manifest: UpdateManifest) {
        val apk = File(updatesDir(), "vistaks-tv-${manifest.versionCode}.apk")
        if (!apk.isFile) return fail(UpdateFailure.STORAGE, "the verified APK is gone")
        val installer = activity.packageManager.packageInstaller
        try {
            registerReceiver()
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(activity.packageName)
                setSize(apk.length())
            }
            val id = installer.createSession(params)
            sessionId = id
            installer.openSession(id).use { session ->
                session.openWrite("vistaks-tv.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(activity, id, Intent(ACTION_INSTALL_STATUS).setPackage(activity.packageName), flags)
                session.commit(callback.intentSender)
            }
            Log.i(TAG, "session $id committed for ${manifest.versionCode}")
        } catch (e: Exception) {
            Log.w(TAG, "install session failed: $e")
            return fail(UpdateFailure.INSTALL, e.toString())
        } finally {
            // The session holds its own copy; nothing stays in the cache.
            apk.delete()
        }
    }

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            Log.i(TAG, "installer status $status $message")
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm == null) {
                        fail(UpdateFailure.INSTALL, "no confirmation intent")
                    } else {
                        pendingConfirm = confirm
                        openConfirm(confirm)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    // Seldom seen: replacing this app ends its process.
                    pendingConfirm = null
                    update { UpdateState() }
                }
                else -> {
                    pendingConfirm = null
                    fail(installFailure(status), message)
                }
            }
        }
    }

    private fun openConfirm(confirm: Intent) {
        try {
            activity.startActivity(confirm)
        } catch (e: ActivityNotFoundException) {
            pendingConfirm = null
            fail(UpdateFailure.INSTALL, e.toString())
        }
    }

    /** INSTALLING, back in front with no word from the installer: decide from the session. */
    private fun settleInstall() {
        if (released || current().phase != UpdatePhase.INSTALLING) return
        val info = if (sessionId >= 0) activity.packageManager.packageInstaller.getSessionInfo(sessionId) else null
        if (info == null) {
            Log.i(TAG, "session $sessionId gone with no result: cancelled")
            pendingConfirm = null
            fail(UpdateFailure.CANCELLED, "dialog closed")
        }
        // Still there: the dialog was left open (Home); 更新 opens it again.
    }

    @Synchronized
    private fun registerReceiver() {
        if (receiverRegistered || released) return
        ContextCompat.registerReceiver(activity, installReceiver, IntentFilter(ACTION_INSTALL_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    // --- state ----------------------------------------------------------------------------------

    private fun current(): UpdateState = synchronized(lock) { state }

    private fun update(transition: (UpdateState) -> UpdateState): UpdateState {
        val (before, after) = synchronized(lock) {
            val before = state
            state = transition(before)
            before to state
        }
        if (after != before) {
            val json = UpdateMachine.toJson(after, BuildConfig.VERSION_NAME)
            stateJson = json
            main.post { if (!released) emit(EVENT, json) }
        }
        return after
    }

    private fun fail(failure: UpdateFailure, detail: String) {
        Log.w(TAG, "update failed: ${failure.wire} ($detail)")
        update { UpdateMachine.onFailure(it, failure) }
    }

    // --- package facts --------------------------------------------------------------------------

    private fun installedVersionCode(): Long = try {
        versionCodeOf(activity.packageManager.getPackageInfo(activity.packageName, 0))
    } catch (_: Exception) {
        Long.MAX_VALUE // unknown: never offer anything
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun archiveInfo(apk: File): PackageInfo? = try {
        activity.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
    } catch (_: Exception) {
        null
    }

    private fun installedSigners(): Set<String> = try {
        signersOf(activity.packageManager.getPackageInfo(activity.packageName, signingFlags()))
    } catch (_: Exception) {
        emptySet()
    }

    /** The current signers' certificate fingerprints (no past certificates of a rotation). */
    @Suppress("DEPRECATION")
    private fun signersOf(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { UpdateVerifier.certificateFingerprint(it.toByteArray()) }.toSet()
    }

    private fun installFailure(status: Int): UpdateFailure = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateFailure.CANCELLED
        PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateFailure.STORAGE
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateFailure.INCOMPATIBLE
        PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateFailure.BLOCKED
        PackageInstaller.STATUS_FAILURE_INVALID -> UpdateFailure.PACKAGE
        else -> UpdateFailure.INSTALL
    }

    private fun updatesDir(): File = File(activity.cacheDir, UPDATES_DIR)

    private fun clearDownloads() {
        updatesDir().listFiles()?.forEach { it.delete() }
    }

    private fun hostOf(url: String): String = try { URI(url).host ?: "?" } catch (_: Exception) { "?" }

    /** `window.VistaksTvUpdate` (sideload only, and only with a manifest). Binder thread. */
    inner class Bridge {
        /** `{ state, currentVersion, versionName, versionCode, sizeBytes, downloadedBytes, progress, reason, dismissed, waitingForPlayback, throttled, notes }`. */
        @JavascriptInterface
        fun state(): String = stateJson

        /** 更新: download and install (AVAILABLE / FAILED), install (READY), reopen the dialog (INSTALLING). */
        @JavascriptInterface
        fun start() {
            worker.execute { beginUpdate() }
        }

        /** 稍後: Home does not offer this version again this launch. */
        @JavascriptInterface
        fun dismiss() {
            update { UpdateMachine.onDismiss(it) }
        }
    }

    private companion object {
        const val TAG = "VistaksUpdate"
        const val EVENT = "update"
        const val PREFS = "vistaks_update"
        const val KEY_LAST_CHECK = "lastCheckAt"
        const val KEY_MANIFEST = "manifest"
        const val UPDATES_DIR = "updates"
        const val PROGRESS_EMIT_MS = 500L
        const val ACTION_INSTALL_STATUS = "com.vistaks.tv.update.INSTALL_STATUS"
    }
}

/** InputStream.readNBytes is API 33; at most [limit] bytes. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
