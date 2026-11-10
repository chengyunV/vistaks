package com.vistaks.tv.nativeplayer

import android.app.Activity
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.vistaks.tv.BuildConfig
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.ui.SubtitleView
import com.vistaks.tv.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The `sideload` flavor's native player (ADR-0100): Media3 ExoPlayer decoding to a SurfaceView
 * (its own hardware layer, so 3840x2160 reaches the panel) under a transparent WebView, streams
 * resolved by NewPipeExtractor. This flavor is GPL-3.0-or-later.
 *
 * Threads (ADR-0100 §3):
 *  - [load] / [command] / [rect] parse their JSON on the caller's thread (a WebView binder
 *    thread), then act on the player and the views on the main thread; bad JSON never reaches them.
 *  - Extraction and manifest parsing run on [worker] (one thread, gone when idle); results come
 *    back to the main thread tagged with the load [generation], so a stale one is dropped, and a
 *    queued or running extraction a newer load superseded gives up early.
 *  - [state] / [stats] only read `@Volatile` immutable snapshots the main thread replaces.
 *  - [emit] and [overlay] are always called on the main thread.
 *
 * Overlay (ADR-0100 §1): the surface is VISIBLE exactly while a source is in the player; every
 * change of that is reported through [overlay], and the activity turns the WebView transparent
 * (true) or back to its opaque #050505 (false). A failure, a release, a pause of the host or a
 * hand-over nobody took all hide the surface first, so the page is never left transparent over
 * nothing it can see.
 *
 * Placement (`nativeRect`, §3): the page sends its video box in CSS px; the surface is laid out at
 * that box times the CSS-to-device ratio (the page's innerWidth when it sends one, else the
 * display density: the TV page is width=device-width, initial-scale=1), the picture contained in
 * it at the video's own aspect. The box may overflow @id/root (fill mode): root clips it. Until a
 * box arrives the picture is contained in the whole of @id/root.
 *
 * Recycling (ADR-0100 §7): [onHostPause] remembers the position and releases the player (decoder
 * and Surface); [release] does the same and forgets the video; a fallback (`error{fallback:true}`)
 * releases first, then reports; [onWebViewRebuilt] holds whatever is loaded or still resolving
 * (an extraction that lands meanwhile is prepared paused, never autoplayed for a page that did
 * not ask) and forgets it after 30 s unless a page takes over. Only a page that acts takes over
 * (`load`, `command`, `rect`); a mere `state()` read does not (a page that reads and then sits
 * on Home must not keep a paused 4K decoder): it only stretches the hold to at least
 * [STATE_READ_GRACE_MS] from the read, once per hold. The next video reuses the same ExoPlayer.
 *
 * Start-up drops (S1 measured 46 dropped frames in the first seconds, none after): playback is
 * pre-rolled — prepared paused, the first frame rendered, then [PREROLL_MS] for the decoder to
 * fill its output queue before the audio clock starts. Every drop burst in the first
 * [STARTUP_WINDOW_MS] is logged with its offset from the start, so the effect can be measured
 * on the device (`prerollMs` in nativeLoad overrides the default, 0 turns it off). A resume after
 * a pause dropped 7-11 frames at 60p because the audio clock jumped ahead on resume (HDMI
 * latency): [ResumeClockRenderersFactory] keeps that clock continuous (ResumeClock.kt). The HDMI
 * path also drops sound it had been given while paused, so a pause of 2 s or more repositions the
 * player where it is (still paused; ResumePlan.kt) and the resume is a fresh start whose picture
 * waits for its sound. The resume pre-roll ([RESUME_PREROLL_MS], now 0; `resumePrerollMs` sets
 * one) stays for measurements; resume drops are logged like start-up ones.
 *
 * Commands that arrive while a video is still resolving (or before the first player exists) are
 * kept, not dropped: the last rate, mute, volume, seek and captions apply when the source is
 * ready; play / pause decide the start (ADR-0100 S4).
 *
 * Display (S3, [DisplayModeMatcher]): with nativeLoad's `matchFrameRate` the display switches to a
 * refresh rate that fits the video's frame rate, and is restored whenever the player lets go.
 * HDR (VP9.2) is chosen only when nativeLoad's `hdr` is true and the display reports HDR10; the
 * chosen track's colour info (the dataspace SurfaceFlinger should show) is logged at the first frame.
 *
 * Captions (S4): the extractor's tracks are listed in the `captions` event; with captions on, the
 * chosen track's file is fetched on the worker (zh-TW first, like the iframe's cc_lang_pref) and
 * merged into the source as a side-loaded text track that Media3 renders into a [SubtitleView]
 * over the surface and under the WebView, laid out on the visible part of the picture. Captions
 * off only disables the text track; turning them on (or naming another language) later fetches
 * and swaps the source at the current position. A caption that cannot be fetched never stops the
 * video.
 *
 * The public signature must stay identical to `src/play/.../NativePlayerHost.kt`
 * (scripts/test_tv_native_bridge_contract.mjs pins it).
 */
@OptIn(UnstableApi::class)
class NativePlayerHost(
    activity: Activity,
    emit: (event: String, json: String) -> Unit, // main thread; MainActivity forwards to window.VistaksTv.native
    overlay: (transparent: Boolean) -> Unit, // main thread; the WebView turns transparent while the surface shows
) {
    private val activity: Activity = activity
    private val emitEvent = emit
    private val setOverlay = overlay
    private val main = Handler(Looper.getMainLooper())

    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "VistaksNative-resolve").apply { isDaemon = true }
    }
    private val resolver = StreamResolver()
    private val dashDataSource = YoutubeDataSource.Factory(rangeParameter = true)
    private val progressiveDataSource = YoutubeDataSource.Factory(rangeParameter = false)

    private val displayModes = DisplayModeMatcher(activity)

    init {
        displayModes.onSwitched = { mode, waited -> onDisplayModeLanded(mode, waited) }
    }

    /** NOTICE.json from the APK's assets (S5), read once on the worker; null until read. */
    @Volatile private var licenseJson: String? = null

    // --- main-thread state -------------------------------------------------------------------
    private var root: FrameLayout? = null
    private var surface: SurfaceView? = null
    private var subtitles: SubtitleView? = null

    /** The last rate the page asked for (the load's, then any rate command); applied at play(). */
    private var desiredRate = 1f
    /** A seek that arrived while resolving: play() starts there instead of the load's position. */
    private var pendingSeekMs: Long? = null
    /** Captions on (the last captions command or the load's `captions`); kept across videos. */
    private var captionsOn = false
    /** A language a captions command named (null = zh-TW first, the iframe's cc_lang_pref). */
    private var captionLanguage: String? = null
    /** The caption track merged into the current source (null = none). */
    private var attachedCaption: CaptionTrack? = null
    /** "videoId|languageCode|kind" of a caption fetch in flight (one at a time). */
    private var captionFetching: String? = null

    /** A resume after a pause waits this long with the source ready (`resumePrerollMs`; default 0). */
    private var resumePrerollMs = RESUME_PREROLL_MS
    /** Debug-only (`noAudio` in nativeLoad): play without the audio track — the standalone media
        clock runs the video. S4 device run (2026-11-10, SHIELD, 60p): every resume after a pause
        dropped 7-11 frames (~150 ms) with audio; 0 without it. The drops are the audio clock's
        jump on resume (HDMI output latency), not the decoder's: a 150 ms resume pre-roll, a
        re-seek to the paused position and tunneled playback (below) changed nothing. See
        ResumeClock.kt for the fix. */
    private var noAudio = false
    /** Debug-only (`tunneling` in nativeLoad): Android TV tunneled playback. Measured on the
        SHIELD: the same 8-14 drops per resume (whether the selector granted tunneling is unknown). */
    private var tunneling = false
    /** The continuous audio clock across pause→play (ResumeClock.kt): on; a debug build's nativeLoad
        may turn it off (`resumeClock: false`) to compare. SHIELD 2026-11-10, 60p: 7-11 drops per
        resume without it, 0 with it. Read by the audio sink on the playback thread. */
    @Volatile private var resumeClock = true
    private var resumePending = false
    private var resumeStartedAt = 0L

    /** How a resume after a pause starts (ResumePlan.kt); a debug build's nativeLoad may pick another. */
    @Volatile private var resumeConfig = ResumeConfig.DEFAULT
    private var reposition = Reposition.NONE
    private var repositionStartedAt = 0L
    private var repositionReadyAt = 0L
    private var repositionSawBuffering = false
    /** The page said play while the reposition ran: it plays when that is done. */
    private var playAfterReposition = false
    private var pausedAt = 0L

    /** When the hand-over hold runs out (elapsedRealtime); a state() read may stretch it once. */
    private var handoverDeadline = 0L
    private var handoverReadExtended = false
    private var player: ExoPlayer? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null

    /** Bumped on the main thread by every load and release; read by the worker to give up early. */
    @Volatile private var generation = 0
    private var request: LoadRequest? = null
    private var resolved: Resolved? = null
    private var firstFrame = false
    private var reResolved = false
    private var lastState = STATE_UNSTARTED
    private var decoderName = ""
    private var fpsSampleAt = 0L
    private var fpsSampleRendered = 0
    private var measuredFps = 0.0
    private var overlayOn = false
    private var muted = false
    private var volume = 1f

    /** The page's video box in CSS px (`nativeRect`); null = contain in the whole of @id/root. */
    private var pageRect: CssRect? = null
    private var videoWidth = 0
    private var videoHeight = 0
    /** The decoder's picture in buffer pixels (videoWidth / videoHeight carry the pixel aspect). */
    private var bufferWidth = 0
    private var bufferHeight = 0

    /**
     * The picture's own layer (API 29+): a child SurfaceControl of the SurfaceView, placed with an
     * integer source crop and destination ([VideoGeometry]) instead of the SurfaceView's own
     * scale-and-clip (the edge noise, docs/adr/0100 "影像邊緣雜訊"). Debug builds may turn it off
     * (`videoLayer: false` in nativeLoad) to compare.
     */
    private var videoLayerWanted = Build.VERSION.SDK_INT >= 29
    private var videoLayer: SurfaceControl? = null
    private var videoLayerSurface: Surface? = null
    private var lastPlacement: VideoGeometry.Placement? = null
    /** The decoder's own crop starts past 0 on that side (padding before the picture there too). */
    private var decoderPaddedLeft = false
    private var decoderPaddedTop = false
    private var lastOutputFormat = ""
    /** Debug builds: `edgeInset` in nativeLoad fixes the crop's inset (A/B on the TV); null = computed. */
    private var edgeInsetOverride: Int? = null
    /**
     * GPU composition of the picture's layer (docs/adr/0100 "影像邊緣雜訊"): the SHIELD's hardware
     * composer draws noise on the right and bottom edges when it scales a crop that does not start at
     * the buffer's top-left corner (VideoGeometry.needsGpu); on such a composer (ComposerQuirks) those
     * placements go to the GPU. [gpuForce] (debug nativeLoad `gpuCompose: "on" | "off"`) overrides
     * it for an A/B; [layerGpu] is what the layer has now; [gpuUnavailable]: the GPU request failed
     * once, and such placements are contained instead (bars, never the noise).
     */
    private val tegraComposer = ComposerQuirks.tegraComposer(Build.HARDWARE ?: "", Build.BOARD ?: "", systemProperty("ro.board.platform"))
    private var gpuForce: Boolean? = null
    private var gpuRadius = GPU_CORNER_RADIUS
    private var layerGpu = false
    private var gpuUnavailable = false

    /** Prepared paused; the first frame is up; playback starts [PREROLL_MS] later (start-up drops). */
    private var prerollPending = false
    private var prerollMs = PREROLL_MS
    private var playStartedAt = 0L

    /**
     * S3: a refresh-rate switch for this video is still landing (HDMI dark for 1-3 s). The start
     * of playback waits for it, plus [MODE_SETTLE_MS], at most [MODE_WAIT_MAX_MS] in all.
     */
    private var modeWait = false
    private var modeWaitSince = 0L
    /** The start (pre-roll over, or the page's play) is held until [modeWait] ends. */
    private var modeHeldStart = false

    /** A WebView rebuild left a video with no page: whatever lands is prepared paused (§7). */
    private var heldForHandover = false
    /** True while an extraction is in flight (resolveAsync until play / fail / release). */
    private var resolving = false
    /** The page's play / pause while the extraction was still in flight; null = the load's autoplay. */
    private var playOverride: Boolean? = null

    // --- snapshots (written on main, read anywhere) --------------------------------------------
    private data class StateSnapshot(val videoId: String, val state: Int, val currentTime: Double, val duration: Double) {
        fun toJson(): String = JSONObject()
            .put("videoId", videoId)
            .put("state", state)
            .put("currentTime", currentTime)
            .put("duration", duration)
            .toString()
    }

    private data class StatsSnapshot(
        val videoId: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val fps: Double = 0.0,
        val codec: String = "",
        val decoder: String = "",
        val hdr: Boolean = false,
        val dropped: Int = 0,
        val rendered: Int = 0,
        val bitrateEstimate: Long = 0,
        val itag: Int = 0,
        val audioItag: Int = 0,
        val audioCodec: String = "",
        val delivery: String = "",
        /** The stream's nominal frame rate (the extractor's), next to the measured [fps]. */
        val contentFps: Double = 0.0,
        /** "bt2020" / "bt709" / "bt601" / "" from the decoded format's colour info. */
        val colorSpace: String = "",
        /** "pq" (HDR10) / "hlg" / "sdr" / "" — PQ on BT.2020 is SurfaceFlinger's BT2020_PQ dataspace. */
        val colorTransfer: String = "",
        /** The display mode in force and whether we asked for it (S3 frame-rate matching). */
        val displayModeId: Int = 0,
        val refreshRate: Double = 0.0,
        val frameRateMatched: Boolean = false,
        /** The caption track shown ("" = none) and whether captions are on. */
        val captions: String = "",
        val captionsOn: Boolean = false,
    ) {
        fun toJson(): String = JSONObject()
            .put("videoId", videoId)
            .put("width", width)
            .put("height", height)
            .put("fps", Math.round(fps * 10) / 10.0)
            .put("codec", codec)
            .put("decoder", decoder)
            .put("hdr", hdr)
            .put("dropped", dropped)
            .put("rendered", rendered)
            .put("bitrateEstimate", bitrateEstimate)
            .put("itag", itag)
            .put("audioItag", audioItag)
            .put("audioCodec", audioCodec)
            .put("delivery", delivery)
            .put("contentFps", contentFps)
            .put("colorSpace", colorSpace)
            .put("colorTransfer", colorTransfer)
            .put("displayModeId", displayModeId)
            .put("refreshRate", Math.round(refreshRate * 1000) / 1000.0)
            .put("frameRateMatched", frameRateMatched)
            .put("captions", captions)
            .put("captionsOn", captionsOn)
            .toString()
    }

    /** `nativeRect`'s JSON: CSS px; innerWidth / innerHeight optional (the CSS-to-device ratio). */
    private data class CssRect(val x: Double, val y: Double, val width: Double, val height: Double, val innerWidth: Double, val innerHeight: Double) {
        companion object {
            fun parse(json: String): CssRect? = try {
                val o = JSONObject(json)
                val r = CssRect(
                    o.optDouble("x", 0.0), o.optDouble("y", 0.0),
                    o.optDouble("width", Double.NaN), o.optDouble("height", Double.NaN),
                    o.optDouble("innerWidth", 0.0), o.optDouble("innerHeight", 0.0),
                )
                val sane = listOf(r.x, r.y, r.width, r.height).all { it.isFinite() } &&
                    r.width > 0 && r.height > 0 && r.width < 100_000 && r.height < 100_000 &&
                    kotlin.math.abs(r.x) < 100_000 && kotlin.math.abs(r.y) < 100_000
                if (sane) r else null
            } catch (_: Exception) {
                null
            }
        }
    }

    @Volatile private var stateSnapshot = StateSnapshot("", STATE_UNSTARTED, 0.0, 0.0)
    @Volatile private var statsSnapshot = StatsSnapshot()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }
    // Two watchdogs, so the page hears which half stalled: the extraction (cold NewPipe + Rhino on
    // the signature JS can take seconds) gets its own budget, and the first-frame clock starts
    // only once the source is handed to the player.
    private val extractionTimeout = Runnable {
        if (request != null) fail("extraction-timeout", "no stream within ${EXTRACTION_TIMEOUT_MS / 1000} s")
    }
    private val firstFrameTimeout = Runnable {
        if (!firstFrame && request != null) {
            fail("first-frame-timeout", "no frame within ${FIRST_FRAME_TIMEOUT_MS / 1000} s of the source being ready")
        }
    }
    private val handoverTimeout = Runnable {
        // Forget the video too (as release() does): a page that later reads nativeState must not
        // take over a player that no longer exists.
        Log.i(TAG, "no page took the player over within ${HANDOVER_MS / 1000} s; releasing and forgetting ${request?.videoId}")
        forget()
    }
    // A state() read is not a take-over (S2 measured: a page that only reads kept the paused 4K
    // decoder forever); it stretches the hold to at least STATE_READ_GRACE_MS from the read, once.
    private val stateRead = Runnable { stretchHandoverForRead() }
    private val resumeEnd = Runnable {
        if (!resumePending) return@Runnable
        resumePending = false
        val p = player ?: return@Runnable
        Log.i(TAG, "${request?.videoId}: resume pre-roll over, playing (${countersText(p)})")
        resume(p)
    }
    private val repositionTimer = Runnable { startReposition("paused ${SystemClock.elapsedRealtime() - pausedAt} ms") }
    private val repositionTimeout = Runnable {
        if (reposition != Reposition.IN_FLIGHT) return@Runnable
        Log.w(TAG, "${request?.videoId}: reposition not done within $REPOSITION_TIMEOUT_MS ms; going on")
        repositionDone("timeout")
    }
    /** Debug: when the picture moves after a resume (the first frame rendered after play). */
    private var watchRenderedFrom = -1
    private var watchSince = 0L
    private var watchFromPositionMs = 0L
    private val pictureWatch = object : Runnable {
        override fun run() {
            val p = player ?: return
            val c = p.videoDecoderCounters ?: return
            c.ensureUpdated()
            val waited = SystemClock.elapsedRealtime() - watchSince
            if (c.renderedOutputBufferCount > watchRenderedFrom) {
                Log.i(TAG, "${request?.videoId}: resume: picture moved $waited ms after play (position ${p.currentPosition} ms, paused at ${watchFromPositionMs} ms)")
                watchRenderedFrom = -1
            } else if (waited < 3_000 && p.playWhenReady) {
                main.postDelayed(this, 4)
            } else {
                watchRenderedFrom = -1
            }
        }
    }
    private val prerollEnd = Runnable {
        if (!prerollPending) return@Runnable
        prerollPending = false
        val p = player ?: return@Runnable
        if (modeWait) {
            // The picture and sound would play into the HDMI re-sync: hold until it lands.
            modeHeldStart = true
            Log.i(TAG, "${request?.videoId}: pre-roll over; holding for the display mode switch (${SystemClock.elapsedRealtime() - modeWaitSince} ms so far)")
            syncPlayState()
            return@Runnable
        }
        Log.i(TAG, "${request?.videoId}: pre-roll over, playing (${countersText(p)})")
        startPlayback(p)
    }
    private val modeSettled = Runnable { endModeWait("settled $MODE_SETTLE_MS ms after the switch landed") }
    private val modeWaitTimeout = Runnable { endModeWait("no switch reported within $MODE_WAIT_MAX_MS ms; going on") }

    /** S3: the display reached the mode a switch asked for; the start waits [MODE_SETTLE_MS] more (vsync settles). */
    private fun onDisplayModeLanded(mode: DisplayModeMatcher.ModeInfo, waited: Long) {
        if (!modeWait) return
        main.removeCallbacks(modeWaitTimeout)
        main.removeCallbacks(modeSettled)
        Log.i(TAG, "${request?.videoId}: display reached $mode after $waited ms; starting in $MODE_SETTLE_MS ms")
        main.postDelayed(modeSettled, MODE_SETTLE_MS)
    }

    private fun beginModeWait(videoId: String) {
        if (modeWait) return
        modeWait = true
        modeWaitSince = SystemClock.elapsedRealtime()
        main.removeCallbacks(modeSettled)
        main.removeCallbacks(modeWaitTimeout)
        main.postDelayed(modeWaitTimeout, MODE_WAIT_MAX_MS)
        Log.i(TAG, "$videoId: the start waits for the display mode switch (at most $MODE_WAIT_MAX_MS ms)")
    }

    /** The mode switch landed and settled, or the cap ran out: a held start goes now. */
    private fun endModeWait(why: String) {
        if (!modeWait) return
        modeWait = false
        main.removeCallbacks(modeSettled)
        main.removeCallbacks(modeWaitTimeout)
        val waited = SystemClock.elapsedRealtime() - modeWaitSince
        val held = modeHeldStart
        modeHeldStart = false
        Log.i(TAG, "${request?.videoId}: display mode wait over after $waited ms ($why)${if (held) "; playing" else ""}")
        if (!held) return
        val p = player ?: return
        startPlayback(p)
        syncPlayState()
    }

    /** A load, release, rebuild: the wait (and any start it held) goes with the video. */
    private fun cancelModeWait(why: String) {
        if (modeWait || modeHeldStart) {
            Log.i(TAG, "${request?.videoId}: display mode wait cancelled after ${SystemClock.elapsedRealtime() - modeWaitSince} ms ($why)")
        }
        modeWait = false
        modeHeldStart = false
        main.removeCallbacks(modeSettled)
        main.removeCallbacks(modeWaitTimeout)
    }

    /** 1: the native player is here (ADR-0100 §3). */
    fun version(): Int = 1

    fun attach(root: FrameLayout) {
        this.root = root
        Log.i(TAG, "composer: hardware=${Build.HARDWARE} board=${Build.BOARD} platform=${systemProperty("ro.board.platform")} -> ${if (tegraComposer) "Tegra: a scaled crop with a left or top offset is composed on the GPU" else "overlay for every placement"}")
        val view = SurfaceView(activity).apply {
            id = R.id.native_surface
            visibility = View.GONE
            isFocusable = false
            isFocusableInTouchMode = false
        }
        surface = view
        // Index 0: under the WebView (and the error screen). SurfaceView punches through the
        // window, so whatever sits above it must be transparent where the video shows (§1).
        // Default Z order (never raised above the window): the page's UI draws over the picture.
        root.addView(
            view,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        // S3 on API 31+: the Surface's frame rate needs a valid Surface; it may come after play().
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                displayModes.onSurface(holder.surface)
                createVideoLayer()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                displayModes.onSurface(null)
                releaseVideoLayer()
            }
        })
        // S4: captions right above the picture (index 1), under the WebView: a plain View drawn into
        // the window, so the transparent page lets it through. Laid out with the surface.
        val captionView = SubtitleView(activity).apply {
            id = R.id.native_subtitles
            visibility = View.GONE
            isFocusable = false
            isFocusableInTouchMode = false
            setUserDefaultStyle()
            setUserDefaultTextSize()
        }
        subtitles = captionView
        root.addView(
            captionView,
            root.indexOfChild(view) + 1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // S5: NOTICE.json is read once, off the main and bridge threads.
        worker.execute { readLicense() }
        // Root resizes (never on a TV, but a rotation or a split screen would): lay the box out again.
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) layoutSurface("root resized")
        }
    }

    fun load(json: String) {
        val parsed = LoadRequest.parse(json)
        val extra = try {
            JSONObject(json)
        } catch (_: Exception) {
            null
        }
        val preroll = extra?.optLong("prerollMs", PREROLL_MS)?.coerceIn(0, 3_000) ?: PREROLL_MS
        val resumePreroll = extra?.optLong("resumePrerollMs", RESUME_PREROLL_MS)?.coerceIn(0, 1_000) ?: RESUME_PREROLL_MS
        val withoutAudio = BuildConfig.DEBUG && extra?.optBoolean("noAudio", false) == true
        val tunneled = BuildConfig.DEBUG && extra?.optBoolean("tunneling", false) == true
        val layerWanted = Build.VERSION.SDK_INT >= 29 && (!BuildConfig.DEBUG || extra?.optBoolean("videoLayer", true) != false)
        val clockGuard = !BuildConfig.DEBUG || extra?.optBoolean("resumeClock", true) != false
        // Debug A/B for the edge noise: a fixed inset (0, 2, ...) instead of VideoGeometry.EDGE_INSET.
        val insetOverride = if (BuildConfig.DEBUG && extra?.has("edgeInset") == true) extra.optInt("edgeInset", -1).takeIf { it >= 0 } else null
        val force = if (BuildConfig.DEBUG) when (extra?.optString("gpuCompose", "")) { "on" -> true; "off" -> false; else -> null } else null
        val radius = if (BuildConfig.DEBUG && extra?.has("gpuRadius") == true) extra.optDouble("gpuRadius", GPU_CORNER_RADIUS.toDouble()).toFloat() else GPU_CORNER_RADIUS
        val resumePlan = ResumeConfig.parse(extra, BuildConfig.DEBUG)
        runOnMain {
            if (parsed == null) {
                // Nothing playable asked for: never leave the page waiting on a picture that will not come.
                Log.w(TAG, "nativeLoad: unusable JSON ${json.take(200)}")
                fail("bad-request", "nativeLoad needs { videoId } (11 characters)")
            } else {
                resumePrerollMs = resumePreroll
                noAudio = withoutAudio
                tunneling = tunneled
                if (layerWanted != videoLayerWanted) {
                    // The next player attaches to the other path (a debug comparison).
                    videoLayerWanted = layerWanted
                    if (!layerWanted) releaseVideoLayer() else createVideoLayer()
                }
                resumeClock = clockGuard
                if (insetOverride != edgeInsetOverride) {
                    edgeInsetOverride = insetOverride
                    lastPlacement = null
                }
                if (force != gpuForce || radius != gpuRadius) {
                    gpuForce = force
                    gpuRadius = radius
                    // Applied again at the next placement (a radius change too).
                    lastPlacement = null
                    if (layerGpu) videoLayer?.let { layer -> SurfaceControl.Transaction().also { setLayerGpu(it, layer, false) }.apply(); layerGpu = false }
                    Log.i(TAG, "debug gpuCompose=${force ?: "auto"} radius=$radius")
                }
                resumeConfig = resumePlan.copy(resumePrerollMs = resumePreroll)
                Log.i(TAG, "${parsed.videoId}: resume plan $resumeConfig, guard ${if (clockGuard) "on" else "off"}")
                startLoad(parsed, preroll)
            }
        }
    }

    fun command(json: String) {
        val parsed = try {
            JSONObject(json)
        } catch (_: Exception) {
            Log.w(TAG, "nativeCommand: unusable JSON ${json.take(200)}")
            return
        }
        val cmd = parsed.optString("cmd")
        val value = parsed.optDouble("value", Double.NaN)
        val raw = parsed.opt("value")
        // captions: 1 / 0 (what the page sends), true / false, "on" / "off", or a language tag
        // ("en", "zh-TW": on, in that language). `language` may also come as its own field.
        val language = (raw as? String)?.trim()?.takeIf { LANGUAGE_TAG.matches(it) && it.lowercase() !in FLAG_WORDS }
            ?: parsed.optString("language").trim().takeIf { LANGUAGE_TAG.matches(it) }
        val flag = when (raw) {
            is Boolean -> raw
            is Number -> raw.toDouble() != 0.0
            is String -> if (language != null && raw.trim() == language) true else raw.trim().lowercase() in TRUE_WORDS
            else -> null
        }
        runOnMain { applyCommand(cmd, value, flag, language) }
    }

    fun rect(json: String) {
        val parsed = CssRect.parse(json)
        if (parsed == null) {
            Log.w(TAG, "nativeRect: unusable JSON ${json.take(200)}")
            return
        }
        runOnMain {
            takeOver("nativeRect")
            pageRect = parsed
            layoutSurface("nativeRect")
        }
    }

    fun state(): String {
        // Only a read: it never takes over (the page's next load / command / rect does); it may
        // stretch a hand-over hold once so the page has time to act on what it read.
        main.post(stateRead)
        return stateSnapshot.toJson()
    }

    fun stats(): String {
        return statsSnapshot.toJson()
    }

    fun release() {
        runOnMain { forget() }
    }

    /**
     * S5: assets/NOTICE.json (`{ license, sourceUrl, components: [{ name, version, license, url }] }`),
     * as one JSON object; "{}" when the APK has none or it is not a JSON object. Read once on the
     * worker at attach(); a call before that reads it here (a small asset, no network).
     */
    fun licenseInfo(): String = licenseJson ?: readLicense()

    fun onHostPause() {
        // §7: remember where we were, give the decoder and the Surface back. The page resumes with
        // a new nativeLoad from that position when it hears lifecycle('resume'); nothing replays
        // on its own.
        val wasPlaying = player != null
        releasePlayer()
        if (wasPlaying) emitState(STATE_PAUSED, force = true)
    }

    fun onWebViewRebuilt() {
        // The new WebView went in above the surface (MainActivity puts it under error_view); the
        // activity re-applies the transparency it was told last. Hold the picture for the new page.
        // Keyed on the request, not the player: an extraction in flight (the first load, a next
        // video, a 403 re-resolve) lands after the rebuild and must not start sound under an
        // opaque page that never asked for it. play() prepares paused while held.
        prerollPending = false
        main.removeCallbacks(prerollEnd)
        resumePending = false
        main.removeCallbacks(resumeEnd)
        playAfterReposition = false
        cancelModeWait("webview rebuilt")
        // The hold is a pause like the page's: it begins now when something played, and a long
        // one repositions while paused (ResumePlan.kt), so the take-over's resume is measured from it.
        val holding = player
        val wasPlaying = holding?.playWhenReady == true
        holding?.pause()
        if (holding != null) {
            pausedAt = ResumePlanner.pauseStart(wasPlaying, pausedAt, SystemClock.elapsedRealtime())
            if (wasPlaying && reposition == Reposition.NONE) {
                main.removeCallbacks(repositionTimer)
                ResumePlanner.repositionDelay(resumeConfig, desiredRate)?.let { main.postDelayed(repositionTimer, it) }
            }
        }
        main.removeCallbacks(handoverTimeout)
        val held = request
        heldForHandover = held != null
        handoverReadExtended = false
        playOverride = null
        if (held != null) {
            handoverDeadline = SystemClock.elapsedRealtime() + HANDOVER_MS
            main.postDelayed(handoverTimeout, HANDOVER_MS)
        }
        val what = when {
            held == null -> "nothing"
            player != null && !resolving -> "player held paused at ${stateSnapshot.currentTime}s"
            resolving -> "${held.videoId} still resolving; it will be prepared paused"
            else -> "${held.videoId} released at ${stateSnapshot.currentTime}s (no player)"
        }
        Log.i(TAG, "webview rebuilt: $what${if (held != null) " for ${HANDOVER_MS / 1000} s" else ""}, overlay=$overlayOn")
    }

    /** A page spoke to the host after a rebuild: it owns the video now; the hand-over clock stops. */
    private fun takeOver(via: String) {
        main.removeCallbacks(handoverTimeout)
        if (heldForHandover) {
            heldForHandover = false
            Log.i(TAG, "page took over via $via (${request?.videoId})")
        }
    }

    /**
     * A page read nativeState() while a video is held for a hand-over: not a take-over. The hold
     * runs to at least [STATE_READ_GRACE_MS] after the read, once per hold, so a page that reads
     * and then acts (load / command / rect) keeps the player, and one that only reads lets it go.
     */
    private fun stretchHandoverForRead() {
        if (!heldForHandover) return
        if (handoverReadExtended) {
            Log.i(TAG, "nativeState read while ${request?.videoId} is held; not a take-over (hold already stretched once)")
            return
        }
        handoverReadExtended = true
        val now = SystemClock.elapsedRealtime()
        val deadline = maxOf(handoverDeadline, now + STATE_READ_GRACE_MS)
        if (deadline != handoverDeadline) {
            handoverDeadline = deadline
            main.removeCallbacks(handoverTimeout)
            main.postDelayed(handoverTimeout, deadline - now)
        }
        Log.i(TAG, "nativeState read while ${request?.videoId} is held; not a take-over; released in ${(deadline - now) / 1000} s unless the page acts")
    }

    /** Worker (or a bridge thread before the worker got to it): assets/NOTICE.json as compact JSON, or "{}". */
    private fun readLicense(): String {
        licenseJson?.let { return it }
        val text = try {
            activity.assets.open(NOTICE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: java.io.IOException) {
            Log.i(TAG, "no $NOTICE_ASSET in the APK; licenseInfo() is {}")
            null
        }
        val json = try {
            if (text == null) "{}" else JSONObject(text).also { notice ->
                // The source bundle is versioned per release (ADR-0100 §6, decision 2: a release asset of
                // github.com/chengyunV/vistaks): {version} in sourceUrl is this APK's own version.
                val source = notice.optString("sourceUrl")
                if (source.contains("{version}")) notice.put("sourceUrl", source.replace("{version}", BuildConfig.VERSION_NAME))
            }.toString()
        } catch (e: Exception) {
            Log.w(TAG, "$NOTICE_ASSET is not a JSON object (${e.message}); licenseInfo() is {}")
            "{}"
        }
        licenseJson = json
        return json
    }

    /** Release, and forget the video: the state reads unstarted and empty again (release, hand-over timeout). */
    private fun forget() {
        releasePlayer()
        request = null
        resolved = null
        heldForHandover = false
        playOverride = null
        stateSnapshot = StateSnapshot("", STATE_UNSTARTED, 0.0, 0.0)
    }

    // ------------------------------------------------------------------------------------------

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun startLoad(next: LoadRequest, preroll: Long) {
        takeOver("nativeLoad")
        playOverride = null
        main.removeCallbacks(firstFrameTimeout)
        main.removeCallbacks(extractionTimeout)
        main.removeCallbacks(prerollEnd)
        cancelModeWait("next load ${next.videoId}")
        generation++
        val gen = generation
        request = next
        resolved = null
        firstFrame = false
        reResolved = false
        prerollPending = false
        resumePending = false
        main.removeCallbacks(resumeEnd)
        cancelReposition()
        lastState = STATE_UNSTARTED
        // The load sets the start; commands that follow it (even mid-extraction) override.
        muted = next.muted
        desiredRate = next.rate
        pendingSeekMs = null
        next.captions?.let { captionsOn = it }
        attachedCaption = null
        captionFetching = null
        prerollMs = preroll
        // The next video reuses the player (§7); stop the old one so it does not play on meanwhile.
        // The surface keeps its last picture until the new source replaces it.
        player?.stop()
        stopTicker()
        stateSnapshot = StateSnapshot(next.videoId, STATE_UNSTARTED, next.startSeconds, 0.0)
        // The decoder name survives only while the same ExoPlayer lives (it may not re-initialise).
        statsSnapshot = StatsSnapshot(videoId = next.videoId, decoder = if (player != null) decoderName else "")
        emitState(STATE_BUFFERING)
        resolveAsync(next, gen, fresh = false, positionMs = (next.startSeconds * 1000).toLong())
    }

    private fun resolveAsync(next: LoadRequest, gen: Int, fresh: Boolean, positionMs: Long) {
        val hdr10 = displayHdr10()
        Log.i(TAG, "${next.videoId}: HDR requested=${next.hdr}, display HDR10=$hdr10 -> VP9.2 HDR tracks ${if (next.hdr && hdr10) "allowed" else "excluded"}; matchFrameRate=${next.matchFrameRate}")
        // Captions on: the chosen track's file comes with the source (one fetch, on the worker).
        val wantCaptions = captionsOn
        val language = captionLanguage
        // Every resolve (the first and the 403 retry) runs under the extraction watchdog only;
        // play() swaps it for the first-frame one.
        main.removeCallbacks(firstFrameTimeout)
        main.removeCallbacks(extractionTimeout)
        main.postDelayed(extractionTimeout, EXTRACTION_TIMEOUT_MS)
        resolving = true
        val stale = { gen != generation }
        worker.execute {
            // Queued behind an older extraction that a newer load already replaced: give up now.
            if (stale()) {
                Log.i(TAG, "${next.videoId}: superseded before extraction; dropped")
                return@execute
            }
            try {
                if (fresh) resolver.invalidate(next.videoId)
                val result = resolver.resolve(next, hdr10, stale)
                if (stale()) throw StaleResolveException()
                val caption = if (wantCaptions) loadCaption(result, language) else null
                if (stale()) throw StaleResolveException()
                val source = buildSource(result, caption)
                main.post { if (gen == generation) play(result, source, next, positionMs, caption?.track) }
            } catch (_: StaleResolveException) {
                Log.i(TAG, "${next.videoId}: superseded during extraction; dropped")
            } catch (e: ResolveException) {
                Log.w(TAG, "${next.videoId}: ${e.code}: ${e.message}")
                main.post { if (gen == generation) fail(e.code, e.message ?: e.code) }
            } catch (t: Throwable) {
                Log.e(TAG, "${next.videoId}: resolve failed", t)
                main.post { if (gen == generation) fail("extraction", "${t.javaClass.simpleName}: ${t.message}") }
            }
        }
    }

    /** A caption file fetched for a source: the track and its bytes (worker thread). */
    private class LoadedCaption(val track: CaptionTrack, val bytes: ByteArray)

    /** Worker thread: the track captions show in, fetched; null when there is none or it failed. */
    private fun loadCaption(result: Resolved, language: String?): LoadedCaption? {
        val track = StreamResolver.pickCaption(result.captions, language) ?: return null
        val bytes = StreamResolver.fetchCaption(track) ?: return null
        return LoadedCaption(track, bytes)
    }

    /**
     * Worker thread: DASH from the extractor's manifests, progressive per track as the fallback,
     * plus the fetched caption as a side-loaded text track (parsed by Media3's subtitle extractor
     * from memory, so a caption can never fail the video's loading).
     */
    private fun buildSource(result: Resolved, caption: LoadedCaption? = null): MediaSource {
        val item = MediaItem.Builder()
            .setMediaId(result.videoId)
            .setUri(Uri.parse("https://www.youtube.com/watch?v=${result.videoId}"))
            .build()
        fun sourceOf(track: ResolvedTrack): MediaSource {
            val manifest = track.manifest
            return if (manifest != null) {
                val parsed = DashManifestParser().parse(Uri.parse(track.url), manifest.byteInputStream())
                DashMediaSource.Factory(dashDataSource).createMediaSource(parsed, item)
            } else {
                ProgressiveMediaSource.Factory(progressiveDataSource)
                    .createMediaSource(MediaItem.Builder().setMediaId(result.videoId).setUri(track.url).build())
            }
        }
        val captionSource = caption?.let { captionSourceOf(result.videoId, it) }
        return if (captionSource != null) {
            MergingMediaSource(true, sourceOf(result.video), sourceOf(result.audio), captionSource)
        } else {
            MergingMediaSource(true, sourceOf(result.video), sourceOf(result.audio))
        }
    }

    /** The caption file as a one-track progressive source (Media3 cues), or null when Media3 cannot parse it. */
    private fun captionSourceOf(videoId: String, caption: LoadedCaption): MediaSource? {
        val track = caption.track
        val format = Format.Builder()
            .setId("captions:${track.languageCode}:${track.kind}")
            .setSampleMimeType(track.mimeType)
            .setLanguage(track.languageCode)
            .setLabel(track.languageName)
            // Selected whenever the text track type is enabled (captions on).
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val parsers = DefaultSubtitleParserFactory()
        if (!parsers.supportsFormat(format)) {
            Log.w(TAG, "$videoId: caption format ${track.mimeType} is not parsed by Media3; none shown")
            return null
        }
        val bytes = caption.bytes
        val extractors = ExtractorsFactory { arrayOf(SubtitleExtractor(parsers.create(format), format)) }
        return ProgressiveMediaSource.Factory(DataSource.Factory { ByteArrayDataSource(bytes) }, extractors)
            .createMediaSource(MediaItem.Builder().setMediaId("$videoId:captions").setUri(Uri.parse("data:${track.mimeType},captions")).build())
    }

    private fun play(result: Resolved, source: MediaSource, next: LoadRequest, positionMs: Long, caption: CaptionTrack?) {
        val view = surface ?: return
        resolving = false
        resolved = result
        attachedCaption = caption
        val p = ensurePlayer()
        videoWidth = result.video.width
        videoHeight = result.video.height
        bufferWidth = result.video.width
        bufferHeight = result.video.height
        layoutSurface("source ready")
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        applyOverlay(true)
        // S3: the refresh rate follows the video's frame rate (asked for before prepare, so the
        // HDMI re-sync overlaps the extraction-to-first-frame time); a load that does not ask
        // restores the TV's own mode. Either may leave a switch landing: the start waits for it
        // (beginModeWait; prerollEnd / play hold, endModeWait releases).
        val switchLanding = if (next.matchFrameRate) {
            displayModes.match(result.video.fps.toFloat(), view.holder.surface?.takeIf { it.isValid }, result.videoId)
        } else {
            displayModes.restore("${result.videoId}: matchFrameRate off")
            displayModes.switchPending
        }
        if (switchLanding) beginModeWait(result.videoId)
        attachVideoOutput(p, view)
        // A seek the page sent while this was resolving wins over the load's position.
        val startMs = pendingSeekMs ?: positionMs
        pendingSeekMs = null
        p.setMediaSource(source, startMs)
        applyVolume()
        // The last rate the page asked for (the load's, or a rate command sent while resolving).
        p.setPlaybackSpeed(desiredRate)
        applyCaptions()
        // Pre-roll: prepared paused; onRenderedFirstFrame starts it (start-up drops). A 403 re-resolve
        // after the first frame resumes at once (the decoder is warm, the page saw a picture).
        // Held for a hand-over (the WebView was rebuilt meanwhile): prepared paused, no pre-roll;
        // the page that takes over says play. A play / pause the page sent while this was still
        // resolving wins over the load's autoplay.
        val wantsPlay = when {
            heldForHandover -> false
            firstFrame -> playOverride ?: (lastState != STATE_PAUSED)
            else -> playOverride ?: next.autoplay
        }
        playOverride = null
        if (heldForHandover) Log.i(TAG, "${result.videoId}: source ready while held for a hand-over; prepared paused")
        prerollPending = wantsPlay && prerollMs > 0 && !firstFrame
        // No pre-roll (prerollMs 0, or a 403 re-resolve) but a mode switch landing: held all the same.
        modeHeldStart = wantsPlay && !prerollPending && modeWait
        p.playWhenReady = wantsPlay && !prerollPending && !modeHeldStart
        if (p.playWhenReady) playStartedAt = SystemClock.elapsedRealtime()
        p.prepare()
        main.removeCallbacks(extractionTimeout)
        main.removeCallbacks(firstFrameTimeout)
        // A 403 after the first frame re-resolves too; the surface already showed a picture then.
        if (!firstFrame) main.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS)
        statsSnapshot = statsSnapshot.copy(
            itag = result.video.itag,
            audioItag = result.audio.itag,
            audioCodec = result.audio.codec,
            delivery = if (result.video.manifest != null) "dash" else "progressive",
            contentFps = result.video.fps.toDouble(),
        )
        stateSnapshot = StateSnapshot(result.videoId, lastState, startMs / 1000.0, result.durationSeconds.toDouble())
        emitCaptions(result)
        // Captions turned on (or to another language) while this resolved without them.
        ensureCaptions()
    }

    /** Main thread: the text track on or off, and the caption view with it. */
    private fun applyCaptions() {
        val p = player
        if (p != null) {
            // ExoPlayer.Builder's default selector is a DefaultTrackSelector, whose Parameters.Builder
            // carries the tunneling flag (TrackSelectionParameters.Builder does not).
            val builder = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsOn)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, noAudio)
            p.trackSelectionParameters = (builder as? DefaultTrackSelector.Parameters.Builder)?.setTunnelingEnabled(tunneling)?.build() ?: builder.build()
        }
        val view = subtitles ?: return
        val show = captionsOn && overlayOn && attachedCaption != null
        if (!show) view.setCues(emptyList())
        view.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Main thread: captions are on but the source carries no caption track, or not the one the
     * page asked for: fetch it on the worker and swap the source at the current position (the
     * decoder is kept warm by the same player; playing stays playing).
     */
    private fun ensureCaptions() {
        val result = resolved ?: return
        val p = player ?: return
        if (!captionsOn || resolving) return
        val wanted = StreamResolver.pickCaption(result.captions, captionLanguage) ?: return
        if (wanted == attachedCaption) return
        val key = "${result.videoId}|${wanted.languageCode}|${wanted.kind}"
        if (captionFetching == key) return
        captionFetching = key
        val gen = generation
        Log.i(TAG, "${result.videoId}: captions ${wanted.languageCode}${if (wanted.kind.isNotEmpty()) "/" + wanted.kind else ""} wanted, ${attachedCaption?.languageCode ?: "none"} attached; fetching")
        worker.execute {
            if (gen != generation) return@execute
            val bytes = StreamResolver.fetchCaption(wanted)
            val source = if (bytes != null && gen == generation) {
                try {
                    buildSource(result, LoadedCaption(wanted, bytes))
                } catch (e: Exception) {
                    Log.w(TAG, "${result.videoId}: rebuilding the source with captions failed: ${e.message}")
                    null
                }
            } else {
                null
            }
            main.post {
                if (captionFetching == key) captionFetching = null
                if (source == null || gen != generation || resolved !== result || player !== p || !captionsOn) return@post
                val position = p.currentPosition.coerceAtLeast(0)
                Log.i(TAG, "${result.videoId}: source swapped at ${position} ms to carry captions ${wanted.languageCode}")
                attachedCaption = wanted
                cancelReposition()
                p.setMediaSource(source, position)
                p.prepare()
                applyCaptions()
                emitCaptions(result)
            }
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val meter = DefaultBandwidthMeter.Builder(activity).build()
        bandwidthMeter = meter
        val renderers = ResumeClockRenderersFactory(
            activity,
            policy = { ResumeClock.Policy(enabled = resumeClock, freshGuard = resumeConfig.freshGuard) },
            onVideoOutputFormat = { format -> onDecoderOutputFormat(format) },
        )
        renderers.setMediaCodecSelector(HARDWARE_FIRST).setEnableDecoderFallback(true)
        val created = ExoPlayer.Builder(activity, renderers)
            .setBandwidthMeter(meter)
            // Keeps 2 s behind the playing position (from the keyframe before it): a reposition (a
            // seek 1 ms back from where it is, ResumePlan.kt) lands inside the buffer for the video
            // and the audio alike. With 0 the audio sample before P was sometimes gone, the audio
            // alone had to reload while the video held the whole byte budget, and the player
            // failed with "stuck buffering and not loading" (SHIELD soak, 2026-11-10).
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBackBuffer(BACK_BUFFER_MS, true)
                    // 30 s ahead (Media3: 50 s): at 4K60 (~25 Mbps) that stays under the ~131 MB video
                    // byte target, so a stream that must reload is never starved by the bytes the
                    // others hold.
                    .setBufferDurationsMs(FORWARD_BUFFER_MS, FORWARD_BUFFER_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                    .build(),
            )
            .setLooper(Looper.getMainLooper())
            .setHandleAudioBecomingNoisy(false)
            .build()
        // S3: DisplayModeMatcher owns the refresh rate; Media3's own (seamless-only) strategy would
        // fight it over the Surface's frame rate.
        created.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        created.addListener(listener)
        created.addAnalyticsListener(analytics)
        player = created
        return created
    }

    private fun startPlayback(p: ExoPlayer) {
        playStartedAt = SystemClock.elapsedRealtime()
        p.playWhenReady = true
    }

    /** A resume's clock starts (the guard takes it from there: ResumeClock.kt). */
    private fun resume(p: ExoPlayer) {
        reposition = Reposition.NONE
        resumeStartedAt = SystemClock.elapsedRealtime()
        startPlayback(p)
        if (BuildConfig.DEBUG) {
            val c = p.videoDecoderCounters
            if (c != null) {
                c.ensureUpdated()
                watchRenderedFrom = c.renderedOutputBufferCount
                watchFromPositionMs = p.currentPosition
                watchSince = resumeStartedAt
                main.removeCallbacks(pictureWatch)
                main.post(pictureWatch)
                // The window SurfaceFlinger's frame timeline is compared with (System.nanoTime = SF's clock).
                Log.i(TAG, "${request?.videoId}: resume window start ns=${System.nanoTime()} ${countersText(p)}")
                main.removeCallbacks(windowEnd)
                main.postDelayed(windowEnd, RESUME_WINDOW_MS + 2_000)
            }
        }
    }

    private val windowEnd = Runnable {
        val p = player ?: return@Runnable
        Log.i(TAG, "${request?.videoId}: resume window end ns=${System.nanoTime()} ${countersText(p)}")
    }

    private fun applyResume(p: ExoPlayer, action: ResumeAction, why: String) {
        Log.i(TAG, "${request?.videoId}: resume ($why): $action")
        when (action) {
            ResumeAction.PlayNow -> resume(p)
            is ResumeAction.Preroll -> {
                resumePending = true
                main.postDelayed(resumeEnd, action.ms)
            }
            ResumeAction.WaitForReposition -> {
                playAfterReposition = true
                syncPlayState()
            }
        }
    }

    /**
     * Paused for [ResumeConfig.repositionAfterMs]: restart the audio output (and the decoders) at
     * the paused position, still paused (ResumePlan.kt). The page keeps hearing "paused"; the
     * position does not move and the surface keeps showing the same picture.
     */
    private fun startReposition(why: String) {
        main.removeCallbacks(repositionTimer)
        val p = player ?: return
        if (resumeConfig.mode == ResumeMode.SLEW || reposition != Reposition.NONE) return
        if (!firstFrame || resolving || p.playWhenReady || p.playbackState != Player.STATE_READY) return
        reposition = Reposition.IN_FLIGHT
        repositionStartedAt = SystemClock.elapsedRealtime()
        repositionSawBuffering = false
        val at = p.currentPosition.coerceAtLeast(0)
        Log.i(TAG, "${request?.videoId}: reposition (${resumeConfig.mode}) at $at ms ($why; ${countersText(p)})")
        main.removeCallbacks(repositionTimeout)
        main.postDelayed(repositionTimeout, REPOSITION_TIMEOUT_MS)
        // 1 ms back: Media3 treats a seek to the same millisecond as a no-op (nothing flushed).
        p.seekTo((at - 1).coerceAtLeast(0))
    }

    private fun repositionDone(how: String) {
        if (reposition != Reposition.IN_FLIGHT) return
        main.removeCallbacks(repositionTimeout)
        reposition = Reposition.DONE
        repositionReadyAt = SystemClock.elapsedRealtime()
        val p = player
        Log.i(TAG, "${request?.videoId}: reposition done in ${repositionReadyAt - repositionStartedAt} ms ($how; position ${p?.currentPosition} ms${if (p != null) ", ${countersText(p)}" else ""})")
        if (playAfterReposition && p != null) {
            playAfterReposition = false
            applyResume(p, ResumeAction.PlayNow, "after the reposition")
        } else {
            syncPlayState()
        }
    }

    /** A seek, a load, a release, a source swap: whatever reposition there was goes with it. */
    private fun cancelReposition() {
        main.removeCallbacks(repositionTimer)
        main.removeCallbacks(repositionTimeout)
        main.removeCallbacks(pictureWatch)
        reposition = Reposition.NONE
        playAfterReposition = false
    }

    private fun applyCommand(cmd: String, value: Double, flag: Boolean?, language: String? = null) {
        takeOver("nativeCommand $cmd")
        // Settings first: kept on the host, so one sent while the video resolves (or before any
        // player exists) is applied by play() when the source is ready, never dropped (S4).
        when (cmd) {
            "rate" -> {
                if (value.isFinite() && value in 0.25..2.0) {
                    desiredRate = value.toFloat()
                    player?.setPlaybackSpeed(desiredRate)
                }
                return
            }
            "mute" -> {
                muted = true
                applyVolume()
                return
            }
            "unmute" -> {
                muted = false
                applyVolume()
                return
            }
            "volume" -> {
                if (value.isFinite()) {
                    volume = (value / 100.0).toFloat().coerceIn(0f, 1f)
                    applyVolume()
                }
                return
            }
            "captions" -> {
                if (flag == null && language == null) {
                    Log.w(TAG, "nativeCommand captions: no usable value")
                    return
                }
                captionsOn = flag ?: true
                if (language != null) captionLanguage = language
                Log.i(TAG, "nativeCommand captions=${if (captionsOn) "on" else "off"}${if (language != null) " ($language)" else ""}; attached ${attachedCaption?.languageCode ?: "none"}")
                applyCaptions()
                ensureCaptions()
                resolved?.let { emitCaptions(it) }
                return
            }
            "seek" -> if (resolving || player == null) {
                if (value.isFinite()) {
                    pendingSeekMs = (value * 1000).toLong().coerceAtLeast(0)
                    stateSnapshot = stateSnapshot.copy(currentTime = value.coerceAtLeast(0.0))
                    Log.i(TAG, "nativeCommand seek $value s while ${request?.videoId ?: "nothing"} resolves; applied when the source is ready")
                }
                return
            }
        }
        if (resolving && (cmd == "play" || cmd == "pause")) {
            // Still resolving (a first load, or a page that took over mid-extraction): play() applies
            // it when the source is ready; reloading would restart the extraction for nothing.
            playOverride = cmd == "play"
            Log.i(TAG, "nativeCommand $cmd while ${request?.videoId} resolves; applied when the source is ready")
            return
        }
        val p = player
        if (p == null) {
            val last = request
            // The host paused (onHostPause let the decoder go) and the page says play: load again
            // from where it stopped. Any other command waits for the page's next nativeLoad.
            if (cmd == "play" && last != null) {
                Log.i(TAG, "nativeCommand play with no player: reloading ${last.videoId} at ${stateSnapshot.currentTime}s")
                startLoad(last.copy(startSeconds = stateSnapshot.currentTime, autoplay = true, muted = muted, rate = desiredRate, captions = captionsOn), prerollMs)
            } else if (cmd != "stop" && cmd != "pause") {
                Log.i(TAG, "nativeCommand '$cmd' with no player; ignored")
            }
            return
        }
        when (cmd) {
            "play" -> {
                prerollPending = false
                main.removeCallbacks(prerollEnd)
                if (resumePending || playAfterReposition) return
                if (modeWait && !p.playWhenReady) {
                    // A refresh-rate switch is still landing: endModeWait starts it.
                    modeHeldStart = true
                    Log.i(TAG, "${request?.videoId}: play held for the display mode switch")
                    syncPlayState()
                    return
                }
                val resuming = firstFrame && !p.playWhenReady &&
                    (p.playbackState == Player.STATE_READY || reposition == Reposition.IN_FLIGHT)
                if (resuming) {
                    // A resume after a pause (ResumePlan.kt): the old track plays on under the
                    // guard, or the reposition made it a fresh start. The page keeps hearing
                    // "paused" through any pre-roll (no buffering flash on a resume).
                    main.removeCallbacks(repositionTimer)
                    applyResume(p, ResumePlanner.onPlay(resumeConfig, reposition), "paused ${SystemClock.elapsedRealtime() - pausedAt} ms")
                } else {
                    startPlayback(p)
                }
            }
            "pause" -> {
                // A pause during the pre-roll (start or resume) cancels it: the page's pause wins.
                prerollPending = false
                main.removeCallbacks(prerollEnd)
                resumePending = false
                main.removeCallbacks(resumeEnd)
                // A start held for a mode switch is dropped too; the wait itself runs on (capped),
                // so a play soon after still does not start into the HDMI re-sync.
                modeHeldStart = false
                playAfterReposition = false
                main.removeCallbacks(pictureWatch)
                val wasPlaying = p.playWhenReady
                p.pause()
                if (wasPlaying) {
                    pausedAt = SystemClock.elapsedRealtime()
                    if (reposition == Reposition.NONE) {
                        ResumePlanner.repositionDelay(resumeConfig, desiredRate)?.let { main.postDelayed(repositionTimer, it) }
                    }
                }
            }
            "seek" -> if (value.isFinite()) {
                // A seek is a fresh start of its own (the audio output is flushed).
                cancelReposition()
                p.seekTo((value * 1000).toLong().coerceAtLeast(0))
            }
            "stop" -> {
                releasePlayer()
                request = null
                emitState(STATE_UNSTARTED, force = true)
            }
            else -> Log.w(TAG, "nativeCommand: unknown cmd '$cmd'")
        }
    }

    private fun applyVolume() {
        player?.volume = if (muted || debugForceMute()) 0f else volume
    }

    /**
     * Debug builds only: `adb shell setprop debug.vistaks.mute 1` keeps the native player silent
     * whatever the page asks (device runs drive the real UI, which starts every video unmuted).
     */
    private fun debugForceMute(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.vistaks.mute") == "1"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Gives the decoder and the Surface back (§7), hides the surface, restores the opaque WebView
     * and stops every timer; the ExoPlayer is rebuilt by the next load. In-flight extractions are
     * dropped (generation). The stats snapshot is cleared (no decoder of a player that is gone).
     */
    private fun releasePlayer() {
        generation++
        resolving = false
        playOverride = null
        stopTicker()
        prerollPending = false
        main.removeCallbacks(prerollEnd)
        resumePending = false
        main.removeCallbacks(resumeEnd)
        cancelReposition()
        main.removeCallbacks(firstFrameTimeout)
        main.removeCallbacks(extractionTimeout)
        main.removeCallbacks(handoverTimeout)
        cancelModeWait("player released")
        // S3: never leave the TV at the video's refresh rate for the UI (release, stop, fallback,
        // onPause, a hand-over nobody took).
        displayModes.restore("player released")
        attachedCaption = null
        captionFetching = null
        subtitles?.let {
            it.setCues(emptyList())
            it.visibility = View.GONE
        }
        val p = player
        if (p != null) {
            stateSnapshot = stateSnapshot.copy(currentTime = p.currentPosition.coerceAtLeast(0) / 1000.0)
            p.removeListener(listener)
            p.removeAnalyticsListener(analytics)
            surface?.let { p.clearVideoSurfaceView(it) }
            videoLayerSurface?.let { p.clearVideoSurface(it) }
            p.release()
            player = null
            bandwidthMeter = null
            Log.i(TAG, "player released (decoder and surface)")
        }
        surface?.visibility = View.GONE
        applyOverlay(false)
        firstFrame = false
        decoderName = ""
        fpsSampleAt = 0L
        fpsSampleRendered = 0
        measuredFps = 0.0
        statsSnapshot = StatsSnapshot()
    }

    private fun fail(code: String, message: String) {
        // §7: release everything first (the surface hidden, the WebView opaque again), then tell
        // the page to fall back to the iframe from where it was.
        val videoId = request?.videoId.orEmpty()
        val position = player?.currentPosition?.coerceAtLeast(0)?.div(1000.0) ?: stateSnapshot.currentTime
        releasePlayer()
        request = null
        emitState(STATE_UNSTARTED, force = true)
        emitEvent(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message)
                .put("fallback", true)
                .put("videoId", videoId)
                .put("currentTime", position)
                .toString(),
        )
    }

    private fun applyOverlay(on: Boolean) {
        if (overlayOn == on) return
        overlayOn = on
        Log.i(TAG, "overlay ${if (on) "transparent (surface VISIBLE)" else "opaque (surface GONE)"}")
        setOverlay(on)
    }

    private fun emitState(state: Int, force: Boolean = false) {
        if (state == lastState && !force) return
        lastState = state
        stateSnapshot = stateSnapshot.copy(state = state)
        emitEvent("state", JSONObject().put("state", state).put("videoId", stateSnapshot.videoId).toString())
    }

    /** Playing (1) and paused (2) only after the first frame, so the page's veil lifts on a picture (§3). */
    private fun syncPlayState() {
        val p = player ?: return
        when (p.playbackState) {
            // A reposition while paused is the host's own business: the page keeps "paused".
            Player.STATE_BUFFERING -> emitState(if (reposition == Reposition.IN_FLIGHT && !p.playWhenReady && !playAfterReposition) STATE_PAUSED else STATE_BUFFERING)
            Player.STATE_ENDED -> {
                emitState(STATE_ENDED)
                stopTicker()
                tick()
            }
            Player.STATE_READY -> when {
                !firstFrame -> {}
                // Pre-rolling: still "buffering" to the page, never a pause it did not ask for.
                prerollPending || modeHeldStart || playAfterReposition -> emitState(STATE_BUFFERING)
                else -> emitState(if (p.playWhenReady) STATE_PLAYING else STATE_PAUSED)
            }
            else -> {}
        }
        if (p.isPlaying) startTicker() else {
            stopTicker()
            tick()
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (reposition == Reposition.IN_FLIGHT) {
                if (playbackState == Player.STATE_BUFFERING) repositionSawBuffering = true
                if (playbackState == Player.STATE_READY && repositionSawBuffering) {
                    repositionDone("seek ready")
                    return
                }
            }
            syncPlayState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = syncPlayState()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncPlayState()

        override fun onRenderedFirstFrame() {
            // Every new source renders a first frame (a 403 re-resolve too): the pre-roll ends here.
            if (prerollPending) {
                main.removeCallbacks(prerollEnd)
                main.postDelayed(prerollEnd, prerollMs)
            }
            if (firstFrame) return
            firstFrame = true
            main.removeCallbacks(firstFrameTimeout)
            val p = player
            Log.i(TAG, "${request?.videoId}: first frame (decoder $decoderName, pre-roll ${if (prerollPending) "$prerollMs ms" else "off"}${if (p != null) ", ${countersText(p)}" else ""})")
            logColour()
            emitQuality()
            updateStats()
            syncPlayState()
        }

        override fun onCues(cueGroup: CueGroup) {
            // S4: Media3 decodes the side-loaded caption track; the view draws whatever is current.
            subtitles?.setCues(if (captionsOn) cueGroup.cues else emptyList())
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                bufferWidth = videoSize.width
                bufferHeight = videoSize.height
                videoWidth = videoSize.width
                videoHeight = (videoSize.height / videoSize.pixelWidthHeightRatio.coerceAtLeast(0.01f)).toInt()
                layoutSurface("video size ${videoSize.width}x${videoSize.height}")
            }
            if (firstFrame) emitQuality()
        }

        override fun onPlayerError(error: PlaybackException) {
            val http = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
            val next = request
            if (http?.responseCode == 403 && next != null && !reResolved) {
                // §2: an expired or refused URL; resolve once more from where we are.
                Log.w(TAG, "${next.videoId}: HTTP 403, re-resolving once")
                reResolved = true
                val position = player?.currentPosition ?: (next.startSeconds * 1000).toLong()
                player?.stop()
                generation++
                val gen = generation
                resolveAsync(next, gen, fresh = true, positionMs = position)
                return
            }
            // TV-S4H: a decoder refusing the HDR track the player response supplied (10-bit) before
            // the first frame is not the video's fault: once, resolve again without HDR (SDR 315
            // plays), never the iframe. markHdrFailed already cleared this video's cache.
            if (http == null && !firstFrame && next != null && !reResolved
                && resolved?.video?.source == StreamResolver.SOURCE_PLAYER_RESPONSE) {
                Log.w(TAG, "${next.videoId}: decoder refused the HDR track (${error.errorCodeName}); re-resolving without HDR")
                reResolved = true
                resolver.markHdrFailed(next.videoId)
                val position = player?.currentPosition ?: (next.startSeconds * 1000).toLong()
                player?.stop()
                generation++
                val gen = generation
                resolveAsync(next, gen, fresh = false, positionMs = position)
                return
            }
            val code = when {
                http != null -> "http-${http.responseCode}"
                !firstFrame -> "decode"
                else -> "playback"
            }
            Log.w(TAG, "${next?.videoId}: player error $code (${error.errorCodeName})", error)
            fail(code, "${error.errorCodeName}: ${error.message}")
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            this@NativePlayerHost.decoderName = decoderName
            statsSnapshot = statsSnapshot.copy(decoder = decoderName)
            Log.i(TAG, "video decoder $decoderName (${initializationDurationMs} ms)")
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            // Start-up drops (S1: 46 in the first seconds): when, relative to the play start, and how many.
            val sincePlay = if (playStartedAt > 0) SystemClock.elapsedRealtime() - playStartedAt else -1
            if (sincePlay in 0..STARTUP_WINDOW_MS) {
                Log.i(TAG, "${request?.videoId}: dropped $droppedFrames frame(s) over $elapsedMs ms, ${sincePlay} ms after play start (pre-roll ${prerollMs} ms), position ${eventTime.currentPlaybackPositionMs} ms")
            }
            // Resume drops (S2: 2-3 per resume): measured against the resume pre-roll.
            val sinceResume = if (resumeStartedAt > 0) SystemClock.elapsedRealtime() - resumeStartedAt else -1
            if (sinceResume in 0..RESUME_WINDOW_MS) {
                Log.i(TAG, "${request?.videoId}: dropped $droppedFrames frame(s) over $elapsedMs ms, ${sinceResume} ms after a resume (resume pre-roll ${resumePrerollMs} ms)")
            }
        }
    }

    private fun countersText(p: ExoPlayer): String {
        val c = p.videoDecoderCounters ?: return "no counters"
        c.ensureUpdated()
        return "rendered=${c.renderedOutputBufferCount} dropped=${c.droppedBufferCount} skipped=${c.skippedOutputBufferCount} " +
            "maxConsecutiveDropped=${c.maxConsecutiveDroppedBufferCount} buffered=${p.totalBufferedDuration} ms"
    }

    private fun startTicker() {
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun stopTicker() {
        main.removeCallbacks(ticker)
    }

    /** Main thread, every [TICK_MS] while playing: the `time` event and both snapshots. */
    private fun tick() {
        val p = player ?: return
        val durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: ((resolved?.durationSeconds ?: 0) * 1000)
        val positionMs = p.currentPosition.coerceAtLeast(0)
        val loaded = if (durationMs > 0) (p.bufferedPosition.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        stateSnapshot = StateSnapshot(stateSnapshot.videoId, lastState, positionMs / 1000.0, durationMs / 1000.0)
        if (p.isPlaying) {
            emitEvent(
                "time",
                JSONObject().put("currentTime", positionMs / 1000.0).put("duration", durationMs / 1000.0)
                    .put("loadedFraction", loaded).toString(),
            )
        }
        updateStats()
    }

    /** Main thread: the stats snapshot from the player, the decoder counters and the display (S4: every field). */
    private fun updateStats() {
        val p = player ?: return
        val counters = p.videoDecoderCounters
        counters?.ensureUpdated()
        val rendered = counters?.renderedOutputBufferCount ?: 0
        val dropped = counters?.droppedBufferCount ?: 0
        val now = SystemClock.elapsedRealtime()
        if (fpsSampleAt == 0L || rendered < fpsSampleRendered) {
            fpsSampleAt = now
            fpsSampleRendered = rendered
        } else if (now - fpsSampleAt >= 1000) {
            measuredFps = (rendered - fpsSampleRendered) * 1000.0 / (now - fpsSampleAt)
            fpsSampleAt = now
            fpsSampleRendered = rendered
        }
        val format = p.videoFormat
        val mode = displayModes.current()
        statsSnapshot = statsSnapshot.copy(
            width = format?.width ?: 0,
            height = format?.height ?: 0,
            fps = if (p.isPlaying) measuredFps else 0.0,
            codec = format?.codecs ?: format?.sampleMimeType ?: "",
            decoder = decoderName,
            hdr = isHdr(format?.colorInfo?.colorTransfer),
            dropped = dropped,
            rendered = rendered,
            bitrateEstimate = bandwidthMeter?.bitrateEstimate ?: 0,
            colorSpace = colorSpaceName(format?.colorInfo),
            colorTransfer = colorTransferName(format?.colorInfo),
            displayModeId = mode?.id ?: 0,
            refreshRate = mode?.refreshRate?.toDouble() ?: 0.0,
            frameRateMatched = displayModes.active,
            captions = attachedCaption?.languageCode.orEmpty(),
            captionsOn = captionsOn,
        )
    }

    /**
     * Main thread, at the first frame: the decoded format's colour info and the display's HDR
     * capabilities (VistaksNative), i.e. the dataspace SurfaceFlinger should show for the layer:
     * BT2020_PQ for an HDR10 (VP9.2) track, BT709 / sRGB-ish for SDR.
     */
    @Suppress("DEPRECATION") // defaultDisplay below API 30.
    private fun logColour() {
        val format = player?.videoFormat ?: return
        val colour = format.colorInfo
        val display: Display? = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
        val caps = display?.hdrCapabilities
        val expected = when {
            colour?.colorSpace == C.COLOR_SPACE_BT2020 && colour.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "BT2020_PQ"
            colour?.colorSpace == C.COLOR_SPACE_BT2020 && colour.colorTransfer == C.COLOR_TRANSFER_HLG -> "BT2020_HLG"
            colour == null -> "unspecified (SDR)"
            else -> "SDR (${colorSpaceName(colour)}/${colorTransferName(colour)})"
        }
        Log.i(
            TAG,
            "${request?.videoId}: colour ${format.codecs ?: format.sampleMimeType} space=${colorSpaceName(colour)} transfer=${colorTransferName(colour)}" +
                " range=${colour?.colorRange ?: -1} -> expected dataspace $expected; display HDR types=${caps?.supportedHdrTypes?.joinToString() ?: "none"}" +
                " maxLum=${caps?.desiredMaxLuminance ?: 0f} isHdr=${display?.isHdr ?: false} mode=${displayModes.current() ?: "?"}",
        )
    }

    private fun emitQuality() {
        val p = player ?: return
        val format = p.videoFormat ?: return
        val height = minOf(format.width, format.height).takeIf { it > 0 } ?: format.height
        emitEvent(
            "quality",
            JSONObject()
                .put("width", format.width)
                .put("height", format.height)
                .put("fps", if (format.frameRate > 0) format.frameRate.toDouble() else (resolved?.video?.fps ?: 0).toDouble())
                .put("hdr", isHdr(format.colorInfo?.colorTransfer))
                .put("label", qualityLabel(height))
                .toString(),
        )
    }

    private fun emitCaptions(result: Resolved) {
        val tracks = JSONArray()
        for (t in result.captions) {
            tracks.put(JSONObject().put("languageCode", t.languageCode).put("languageName", t.languageName).put("kind", t.kind))
        }
        // tracks: what the video offers; active: the track shown when captions are on ("" = none
        // attached yet: on its way, or the video has none); enabled: captions on (S4).
        emitEvent(
            "captions",
            JSONObject()
                .put("videoId", result.videoId)
                .put("tracks", tracks)
                .put("active", attachedCaption?.languageCode.orEmpty())
                .put("enabled", captionsOn)
                .toString(),
        )
    }

    /**
     * Lays the surface out: the page's box (CSS px -> device px) or the whole of @id/root, the
     * picture contained in it at the video's aspect, centred. Logs the arithmetic (VistaksNative).
     */
    private fun layoutSurface(reason: String) {
        val view = surface ?: return
        val container = root ?: return
        val cw = container.width
        val ch = container.height
        if (cw <= 0 || ch <= 0) {
            // Before the first layout: once root has a size.
            container.post { if (container.width > 0) layoutSurface("$reason (after layout)") }
            return
        }
        val box = pageRect
        val density = activity.resources.displayMetrics.density.toDouble()
        val scale = when {
            box != null && box.innerWidth > 0 -> cw / box.innerWidth
            else -> density
        }
        // The box in device px (relative to @id/root, which fills the window like the WebView).
        val bx = if (box != null) box.x * scale else 0.0
        val by = if (box != null) box.y * scale else 0.0
        val bw = if (box != null) box.width * scale else cw.toDouble()
        val bh = if (box != null) box.height * scale else ch.toDouble()
        // The picture inside it, contained at the video's own aspect (an iframe letterboxes too).
        var px = bx
        var py = by
        var pw = bw
        var ph = bh
        if (videoWidth > 0 && videoHeight > 0) {
            val fit = minOf(bw / videoWidth, bh / videoHeight)
            pw = videoWidth * fit
            ph = videoHeight * fit
            px = bx + (bw - pw) / 2
            py = by + (bh - ph) / 2
        }
        val lp = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.gravity = Gravity.TOP or Gravity.START
        if (videoLayerWanted) {
            // The SurfaceView is only the parent: the whole root, never past the screen; the
            // picture's own layer is placed in whole pixels inside it.
            lp.leftMargin = 0
            lp.topMargin = 0
            lp.width = cw
            lp.height = ch
            placeVideoLayer(px, py, pw, ph, cw, ch, density)
        } else {
            lp.leftMargin = Math.round(px).toInt()
            lp.topMargin = Math.round(py).toInt()
            lp.width = Math.round(pw).toInt().coerceAtLeast(1)
            lp.height = Math.round(ph).toInt().coerceAtLeast(1)
        }
        // An exact size is measured EXACTLY by FrameLayout even past root's edges (fill): root clips.
        lp.rightMargin = 0
        lp.bottomMargin = 0
        view.layoutParams = lp
        // S4: captions on the visible part of the picture (a fill-mode picture overflows root;
        // its captions must not be cut off with it).
        subtitles?.let { captions ->
            val left = px.coerceIn(0.0, cw.toDouble())
            val top = py.coerceIn(0.0, ch.toDouble())
            val right = (px + pw).coerceIn(0.0, cw.toDouble())
            val bottom = (py + ph).coerceIn(0.0, ch.toDouble())
            val slp = (captions.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            slp.gravity = Gravity.TOP or Gravity.START
            slp.leftMargin = Math.round(left).toInt()
            slp.topMargin = Math.round(top).toInt()
            slp.width = Math.round(right - left).toInt().coerceAtLeast(1)
            slp.height = Math.round(bottom - top).toInt().coerceAtLeast(1)
            captions.layoutParams = slp
        }
        val index = container.indexOfChild(view)
        Log.i(
            TAG,
            "surface layout ($reason): css=${box?.let { "${it.x},${it.y} ${it.width}x${it.height} inner=${it.innerWidth}x${it.innerHeight}" } ?: "none (whole root)"}" +
                " scale=${"%.3f".format(scale)} (density=${"%.3f".format(density)}) root=${cw}x$ch" +
                " box=${bx.toInt()},${by.toInt()} ${bw.toInt()}x${bh.toInt()} video=${videoWidth}x$videoHeight" +
                " -> px=${lp.leftMargin},${lp.topMargin} ${lp.width}x${lp.height} index=$index/${container.childCount}" +
                " visible=${view.visibility == View.VISIBLE} overlay=$overlayOn",
        )
    }

    /** The player draws into the picture's own layer when there is one, else into the SurfaceView. */
    private fun attachVideoOutput(p: ExoPlayer, view: SurfaceView) {
        val layerSurface = videoLayerSurface
        if (videoLayerWanted && layerSurface != null) {
            p.setVideoSurface(layerSurface)
        } else if (videoLayerWanted) {
            // The SurfaceView's surface is not there yet: surfaceCreated -> createVideoLayer attaches.
            // Should that not come, try once more and then let the SurfaceView carry the picture.
            Log.i(TAG, "video layer: waiting for the SurfaceView's surface")
            main.postDelayed({
                if (player !== p || videoLayerSurface != null) return@postDelayed
                createVideoLayer()
                if (videoLayerSurface == null && player === p) {
                    Log.w(TAG, "video layer: none 500 ms after the source; the SurfaceView carries the picture")
                    videoLayerWanted = false
                    layoutSurface("video layer fallback")
                    p.setVideoSurfaceView(view)
                }
            }, 500)
        } else {
            p.setVideoSurfaceView(view)
        }
    }

    /** Main thread, on surfaceCreated (or a debug switch): the child layer and its Surface. */
    private fun createVideoLayer() {
        val view = surface
        if (!videoLayerWanted || Build.VERSION.SDK_INT < 29 || videoLayer != null || view == null || !view.holder.surface.isValid) {
            Log.i(TAG, "video layer: not created now (wanted=$videoLayerWanted, exists=${videoLayer != null}, surface valid=${view?.holder?.surface?.isValid})")
            return
        }
        try {
            val layer = SurfaceControl.Builder()
                .setName("Vistaks video")
                .setParent(view.surfaceControl)
                .setBufferSize(bufferWidth.coerceAtLeast(2), bufferHeight.coerceAtLeast(2))
                .setOpaque(true)
                .build()
            videoLayer = layer
            videoLayerSurface = Surface(layer)
            lastPlacement = null
            layerGpu = false
            SurfaceControl.Transaction().setVisibility(layer, true).setLayer(layer, 0).apply()
            Log.i(TAG, "video layer created (${bufferWidth}x$bufferHeight)")
            layoutSurface("video layer created")
            val p = player
            if (p != null) {
                surface?.let { p.clearVideoSurfaceView(it) }
                p.setVideoSurface(videoLayerSurface)
            }
        } catch (e: Exception) {
            Log.w(TAG, "video layer: not available (${e.message}); the SurfaceView carries the picture")
            releaseVideoLayer()
            videoLayerWanted = false
            player?.let { p -> surface?.let { p.setVideoSurfaceView(it) } }
            layoutSurface("video layer unavailable")
        }
    }

    private fun releaseVideoLayer() {
        val layerSurface = videoLayerSurface
        val layer = videoLayer
        videoLayerSurface = null
        videoLayer = null
        lastPlacement = null
        if (layerSurface != null) player?.clearVideoSurface(layerSurface)
        if (layer != null && Build.VERSION.SDK_INT >= 29) {
            try {
                SurfaceControl.Transaction().reparent(layer, null).apply()
            } catch (_: Exception) {}
            layer.release()
        }
        layerSurface?.release()
    }

    /** The GPU for this placement: the composer's own rule, or a debug A/B's. */
    private fun wantsGpu(placement: VideoGeometry.Placement): Boolean =
        gpuForce ?: (tegraComposer && VideoGeometry.needsGpu(placement))

    /**
     * Asks SurfaceFlinger to compose the layer on the GPU (a corner radius: the hardware composer
     * cannot round a layer, so SurfaceFlinger composes it itself; 0.01 px rounds nothing visible),
     * or gives it back to the overlay (radius 0). SurfaceControl.Transaction.setCornerRadius is a
     * hidden API, called by reflection. False when the call is not there or fails.
     */
    private fun setLayerGpu(t: SurfaceControl.Transaction, layer: SurfaceControl, on: Boolean): Boolean {
        val method = cornerRadiusMethod ?: return false
        return try {
            method.invoke(t, layer, if (on) gpuRadius else 0f)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "GPU composition: setCornerRadius failed (${e.javaClass.simpleName} ${e.message})")
            false
        }
    }

    private val cornerRadiusMethod: java.lang.reflect.Method? by lazy {
        try {
            SurfaceControl.Transaction::class.java.getMethod("setCornerRadius", SurfaceControl::class.java, Float::class.javaPrimitiveType)
        } catch (e: Throwable) {
            Log.w(TAG, "GPU composition: no setCornerRadius (${e.javaClass.simpleName}); scaled crops with an offset are contained")
            null
        }
    }

    /** The picture's layer: an integer source crop of the buffer onto the visible box, inside root. */
    private fun placeVideoLayer(px: Double, py: Double, pw: Double, ph: Double, cw: Int, ch: Int, density: Double) {
        val layer = videoLayer ?: return
        if (Build.VERSION.SDK_INT < 29) return
        fun placeIn(x: Double, y: Double, w: Double, h: Double, onGpu: Boolean = false) = VideoGeometry.place(
            x, y, w, h, cw, ch, bufferWidth, bufferHeight,
            displayScale = density, paddedLeft = decoderPaddedLeft, paddedTop = decoderPaddedTop,
            onGpu = onGpu, insetOverride = edgeInsetOverride,
        )
        var placement = placeIn(px, py, pw, ph)
        var gpu = placement != null && wantsGpu(placement)
        // On the GPU the crop reaches the buffer's right and bottom edges (VideoGeometry.place onGpu).
        if (gpu && !gpuUnavailable) placement = placeIn(px, py, pw, ph, onGpu = true)
        var contained = false
        if (gpu && gpuUnavailable) {
            // No GPU composition on this device: the whole picture contained (bars), never the noise.
            val box = VideoGeometry.containBox(cw, ch, bufferWidth, bufferHeight)
            placement = placeIn(box[0], box[1], box[2], box[3])
            gpu = false
            contained = true
        }
        if (placement == lastPlacement && gpu == layerGpu) return
        lastPlacement = placement
        val t = SurfaceControl.Transaction()
        if (placement == null) {
            t.setVisibility(layer, false).apply()
            return
        }
        if (gpu != layerGpu) {
            if (setLayerGpu(t, layer, gpu)) {
                layerGpu = gpu
            } else if (gpu) {
                gpuUnavailable = true
                lastPlacement = null
                Log.w(TAG, "GPU composition unavailable: contained instead of a scaled offset crop")
                placeVideoLayer(px, py, pw, ph, cw, ch, density)
                return
            }
        }
        val s = placement.source
        val d = placement.destination
        t.setBufferSize(layer, bufferWidth, bufferHeight)
            .setGeometry(layer, android.graphics.Rect(s.left, s.top, s.right, s.bottom), android.graphics.Rect(d.left, d.top, d.right, d.bottom), Surface.ROTATION_0)
            .setVisibility(layer, true)
            .apply()
        Log.i(TAG, "video layer: source ${s.left},${s.top}-${s.right},${s.bottom} of ${bufferWidth}x$bufferHeight -> ${d.left},${d.top}-${d.right},${d.bottom} (root ${cw}x$ch, box ${"%.1f".format(px)},${"%.1f".format(py)} ${"%.1f".format(pw)}x${"%.1f".format(ph)}) composition ${if (layerGpu) "GPU" else "overlay"}${if (contained) " (contained: no GPU composition)" else ""}")
    }

    /**
     * Playback thread: a video decoder's output format (ResumeClockRenderersFactory). Logs the
     * buffer it hands the composer (crop, stride, slice height) and, when its crop starts past 0,
     * keeps the layer's crop away from the padding before the picture too (VideoGeometry).
     */
    private fun onDecoderOutputFormat(format: MediaFormat) {
        fun int(key: String) = if (format.containsKey(key)) format.getInteger(key) else -1
        val cropLeft = int("crop-left")
        val cropTop = int("crop-top")
        val text = "${int("width")}x${int("height")} crop=$cropLeft,$cropTop-${int("crop-right")},${int("crop-bottom")}" +
            " stride=${int("stride")} slice-height=${int("slice-height")} color-format=${int("color-format")}" +
            " ${format.getString(MediaFormat.KEY_MIME).orEmpty()}"
        main.post {
            if (text != lastOutputFormat) {
                lastOutputFormat = text
                Log.i(TAG, "${request?.videoId}: decoder output format $text")
            }
            val left = cropLeft > 0
            val top = cropTop > 0
            if (left != decoderPaddedLeft || top != decoderPaddedTop) {
                decoderPaddedLeft = left
                decoderPaddedTop = top
                layoutSurface("decoder crop $cropLeft,$cropTop")
            }
        }
    }

    // defaultDisplay below API 30; getSupportedHdrTypes is the API 26 way (its API 34 replacement
    // is not on the SHIELD).
    @Suppress("DEPRECATION")
    private fun displayHdr10(): Boolean {
        val display: Display? = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
        return display?.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
    }

    private companion object {
        const val TAG = StreamResolver.TAG
        /** A corner radius nobody sees (display px) that still sends the layer to the GPU. */
        const val GPU_CORNER_RADIUS = 0.01f

        /** A system property (hidden android.os.SystemProperties, by reflection); "" when unreadable. */
        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
        } catch (_: Throwable) {
            ""
        }

        const val STATE_UNSTARTED = -1
        const val STATE_ENDED = 0
        const val STATE_PLAYING = 1
        const val STATE_PAUSED = 2
        const val STATE_BUFFERING = 3

        const val TICK_MS = 250L
        const val FIRST_FRAME_TIMEOUT_MS = 10_000L
        const val EXTRACTION_TIMEOUT_MS = 20_000L
        const val HANDOVER_MS = 30_000L

        /** A nativeState() read during a hand-over hold keeps it at least this long, once (not a take-over). */
        const val STATE_READ_GRACE_MS = 10_000L

        /**
         * A resume after a pause: the source ready, the clock held this long. 0: the drops it was
         * meant for were the audio clock's jump, which ResumeClock.kt removes (SHIELD 2026-11-10:
         * 0 drops at 0 and at 150 ms, and 150 ms left a larger real gap to slew in: 100-130 ms vs 30-60).
         */
        const val RESUME_PREROLL_MS = 0L

        /** See ensurePlayer's load control. */
        const val BACK_BUFFER_MS = 2_000
        const val FORWARD_BUFFER_MS = 30_000

        /** A reposition that has not reached ready by then is given up on (the resume plays anyway). */
        const val REPOSITION_TIMEOUT_MS = 8_000L

        /** Drops within this long after a resume are logged one by one. */
        const val RESUME_WINDOW_MS = 3_000L

        /** S5: the sideload APK's license notice (tv-android/app/src/sideload/assets). */
        const val NOTICE_ASSET = "NOTICE.json"

        /** A BCP 47-ish language tag a captions command may name ("en", "zh-TW", "zh-Hant-TW"). */
        val LANGUAGE_TAG = Regex("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8}){0,3}$")
        val TRUE_WORDS = setOf("true", "on", "1", "yes")
        val FLAG_WORDS = setOf("true", "false", "on", "off", "yes", "no")

        fun colorSpaceName(colour: ColorInfo?): String = when (colour?.colorSpace) {
            C.COLOR_SPACE_BT2020 -> "bt2020"
            C.COLOR_SPACE_BT709 -> "bt709"
            C.COLOR_SPACE_BT601 -> "bt601"
            null, Format.NO_VALUE -> ""
            else -> "other"
        }

        fun colorTransferName(colour: ColorInfo?): String = when (colour?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "pq"
            C.COLOR_TRANSFER_HLG -> "hlg"
            C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_GAMMA_2_2, C.COLOR_TRANSFER_SRGB -> "sdr"
            null, Format.NO_VALUE -> ""
            else -> "other"
        }

        /** After the first frame, before the clock starts: the decoder's output queue fills (start-up drops). */
        const val PREROLL_MS = 400L

        /** After a refresh-rate switch lands, before the clock starts: the new vsync settles. */
        const val MODE_SETTLE_MS = 400L

        /** The longest the start waits for a refresh-rate switch (HDMI re-sync is 1-3 s). */
        const val MODE_WAIT_MAX_MS = 4_000L

        /** Drops within this long after the play start are logged one by one. */
        const val STARTUP_WINDOW_MS = 10_000L

        fun isHdr(transfer: Int?): Boolean = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG

        /** YouTube's rung names (YOUTUBE_QUALITY_RUNGS on the page), by the picture's short side. */
        fun qualityLabel(shortSide: Int): String = when {
            shortSide >= 4320 -> "highres"
            shortSide >= 2160 -> "hd2160"
            shortSide >= 1440 -> "hd1440"
            shortSide >= 1080 -> "hd1080"
            shortSide >= 720 -> "hd720"
            shortSide >= 480 -> "large"
            shortSide >= 360 -> "medium"
            shortSide >= 240 -> "small"
            else -> "tiny"
        }

        /** Hardware decoders before software ones, each group in the platform's order. */
        val HARDWARE_FIRST = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                .sortedBy { if (it.hardwareAccelerated && !it.softwareOnly) 0 else 1 }
        }
    }
}
