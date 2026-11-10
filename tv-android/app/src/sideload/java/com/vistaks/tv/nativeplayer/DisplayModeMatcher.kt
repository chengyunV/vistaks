package com.vistaks.tv.nativeplayer

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Frame-rate matching for the native player (ADR-0100 §5, slice S3). Main thread only.
 *
 * The SHIELD is API 30, where `Surface.setFrameRate` only makes seamless switches; 60 Hz to 24 Hz
 * is not seamless, so the window asks for a display mode instead: [match] picks, among the modes
 * with the same physical resolution as the mode the TV was in before the first switch, one whose
 * refresh rate is a whole multiple of the video's frame rate, and sets its id as the window's
 * `preferredDisplayModeId`. The resolution never changes (the WebView would lay out again and HDMI
 * would take longer to settle). On API 31+ the video Surface also gets
 * `setFrameRate(fps, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)`.
 *
 * [restore] puts `preferredDisplayModeId` back to 0 (no preference: the system's own mode) and
 * clears the Surface's rate. The host calls it whenever the player lets go (release, stop, a
 * fallback, onPause, a hand-over nobody took) and when a load does not ask for matching; forgetting
 * it would leave the TV showing the UI at 24 Hz.
 *
 * A switch is not in force until the display reports it (the HDMI link re-syncs for 1-3 s, picture
 * and sound dark): [match] says whether one is still landing, [switchPending] keeps saying so and
 * [onSwitched] fires when it lands, so the host can hold the start of playback until then.
 *
 * Every decision is logged (tag VistaksNative) with the mode id, resolution and refresh rate before
 * and after, and the mode the display actually reports once it changed (a DisplayListener), so the
 * switch can be checked on the device against `dumpsys display` without seeing the screen.
 */
internal class DisplayModeMatcher(private val activity: Activity) {

    /** A display mode, decoupled from [Display.Mode] so [pick] stays a pure function. */
    data class ModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float) {
        override fun toString(): String = "id=$id ${width}x$height@${"%.3f".format(refreshRate)}"
    }

    private val main = Handler(Looper.getMainLooper())
    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** The mode before our first switch (the baseline the resolution is kept to); null = nothing switched. */
    private var baseline: ModeInfo? = null
    /** The mode id we asked for (0 = none). */
    private var requestedModeId = 0
    /**
     * The mode id the display has yet to reach (0 = none): set by a switch (or a switch back) the
     * display has not reported yet, cleared by the DisplayListener when it does. A request is not
     * in force until then: the HDMI link re-syncs for 1-3 s.
     */
    private var pendingModeId = 0
    private var pendingSince = 0L
    /** The frame rate the Surface was given on API 31+ (0 = none). */
    private var surfaceRate = 0f
    private var surface: Surface? = null
    private var listening = false

    /** Called on the main thread when a pending switch lands: the mode reached and how long it took. */
    var onSwitched: ((ModeInfo, Long) -> Unit)? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val display = display() ?: return
            if (displayId != display.displayId) return
            val now = infoOf(display.mode)
            Log.i(TAG, "display mode now $now (asked for ${if (requestedModeId == 0) "none" else "id=$requestedModeId"}, awaiting ${if (pendingModeId == 0) "nothing" else "id=$pendingModeId"})")
            // Landed: the mode we asked for, or (after a restore, no preference) whatever the
            // system settled on.
            if (pendingModeId != 0 && (now.id == pendingModeId || requestedModeId == 0)) {
                val waited = SystemClock.elapsedRealtime() - pendingSince
                pendingModeId = 0
                Log.i(TAG, "display mode switch landed on $now after $waited ms")
                onSwitched?.invoke(now, waited)
            }
            if (requestedModeId == 0 && pendingModeId == 0) stopListening()
        }
    }

    /** True while a switch we asked for is in force. */
    val active: Boolean get() = requestedModeId != 0 || surfaceRate > 0f

    /** True while the display has yet to reach a mode we asked for (or switched back to). */
    val switchPending: Boolean get() = pendingModeId != 0

    /** The display's current mode, for stats (main thread). */
    fun current(): ModeInfo? = display()?.mode?.let(::infoOf)

    /**
     * The video plays at [fps] frames per second: ask for a matching refresh rate. [surface] is the
     * video Surface when it is valid (API 31+ only; [onSurface] covers one created later).
     *
     * The decision is taken against the mode the display is heading to (a request still landing),
     * not the one it shows: a 60p video right after a 24p one whose switch has not landed yet must
     * take the 24 Hz request back, not find 59.94 Hz "already fits" and leave it. Returns
     * [switchPending]: true when the display has yet to reach the mode this video plays in (the
     * host holds the start until it lands).
     */
    fun match(fps: Float, surface: Surface?, videoId: String): Boolean {
        val display = display() ?: return false
        val currentMode = infoOf(display.mode)
        val modes = display.supportedModes.map(::infoOf)
        val target = targetOf(modes, currentMode)
        Log.i(TAG, "$videoId: match ${"%.3f".format(fps)} fps; current $currentMode, heading to $target, baseline ${baseline ?: "none"}; supported [${modes.joinToString()}]")
        when (val decision = decide(modes, baseline, target, requestedModeId, fps)) {
            Decision.Keep -> Log.i(TAG, "$videoId: mode $target already fits ${"%.3f".format(fps)} fps (or nothing to take back); no switch")
            Decision.Restore -> {
                Log.i(TAG, "$videoId: ${if (fps > 0f && fps.isFinite()) "the TV's own mode fits ${"%.3f".format(fps)} fps, or none does" else "frame rate unknown ($fps)"}; taking the request for id=$requestedModeId back")
                restoreMode("$videoId: the request does not fit")
            }
            is Decision.Switch -> {
                val chosen = decision.mode
                if (baseline == null) baseline = currentMode
                requestedModeId = chosen.id
                awaitMode(chosen.id, currentMode)
                setPreferredMode(chosen.id)
                Log.i(TAG, "$videoId: display mode $currentMode -> $chosen (preferredDisplayModeId=${chosen.id})")
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (fps > 0f && fps.isFinite()) {
                surfaceRate = fps
                this.surface = surface
                applySurfaceRate(videoId)
            } else {
                restore("$videoId: frame rate unknown")
            }
        }
        if (pendingModeId != 0) Log.i(TAG, "$videoId: waiting for the display to reach id=$pendingModeId")
        return pendingModeId != 0
    }

    /** The mode the display is heading to: our request, else a switch back still landing, else what it shows. */
    private fun targetOf(modes: List<ModeInfo>, currentMode: ModeInfo): ModeInfo {
        val id = if (requestedModeId != 0) requestedModeId else pendingModeId
        return if (id == 0) currentMode else modes.firstOrNull { it.id == id } ?: currentMode
    }

    /** The display has to reach [id] (nothing to wait for when it already shows it). */
    private fun awaitMode(id: Int, currentMode: ModeInfo) {
        if (id == currentMode.id) {
            pendingModeId = 0
            return
        }
        pendingModeId = id
        pendingSince = SystemClock.elapsedRealtime()
        startListening()
    }

    /** The video Surface was created (or replaced): API 31+ gives it the rate [match] asked for. */
    fun onSurface(surface: Surface?) {
        this.surface = surface
        if (Build.VERSION.SDK_INT >= 31 && surfaceRate > 0f) applySurfaceRate("surface created")
    }

    /** Back to the system's own mode (no preference) and no Surface rate. Idempotent. */
    fun restore(reason: String) {
        if (Build.VERSION.SDK_INT >= 31 && surfaceRate > 0f) {
            val s = surface
            if (s != null && s.isValid) {
                try {
                    s.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
                } catch (e: Exception) {
                    Log.w(TAG, "clearing the surface frame rate failed: ${e.message}")
                }
            }
            Log.i(TAG, "surface frame rate cleared ($reason)")
        }
        surfaceRate = 0f
        surface = null
        restoreMode(reason)
    }

    /** preferredDisplayModeId back to 0 (the system's own mode); the Surface rate is left alone. */
    private fun restoreMode(reason: String) {
        if (requestedModeId == 0) return
        val before = current()
        requestedModeId = 0
        setPreferredMode(0)
        Log.i(TAG, "display mode restored ($reason): $before -> preferredDisplayModeId=0 (baseline was $baseline)")
        // The system goes back to the TV's own mode (the baseline): a switch to wait for unless
        // the display still shows it (a request that never landed).
        val base = baseline
        if (base != null && before != null) awaitMode(base.id, before) else pendingModeId = 0
        baseline = null
        // The listener logs the mode the display settles on, then stops.
        if (!listening) startListening()
    }

    private fun applySurfaceRate(why: String) {
        if (Build.VERSION.SDK_INT < 31) return
        val s = surface
        if (s == null || !s.isValid) {
            Log.i(TAG, "surface frame rate ${"%.3f".format(surfaceRate)} waits for a valid surface ($why)")
            return
        }
        try {
            s.setFrameRate(surfaceRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
            Log.i(TAG, "surface frame rate ${"%.3f".format(surfaceRate)} (FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS) ($why)")
        } catch (e: Exception) {
            Log.w(TAG, "surface frame rate ${"%.3f".format(surfaceRate)} refused: ${e.message}")
        }
    }

    private fun setPreferredMode(id: Int) {
        val window = activity.window ?: return
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId == id) return
        attributes.preferredDisplayModeId = id
        window.attributes = attributes
    }

    private fun startListening() {
        if (listening) return
        listening = true
        displayManager.registerDisplayListener(listener, main)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        displayManager.unregisterDisplayListener(listener)
    }

    @Suppress("DEPRECATION") // defaultDisplay below API 30.
    private fun display(): Display? = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay

    companion object {
        private const val TAG = StreamResolver.TAG

        fun infoOf(mode: Display.Mode): ModeInfo = ModeInfo(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)

        /** What [match] does with the window's preference. */
        sealed class Decision {
            /** The mode the display is heading to fits (or nothing was asked for): leave it. */
            object Keep : Decision()
            /** Take our request back (preferredDisplayModeId 0, the TV's own mode). */
            object Restore : Decision()
            /** Ask for [mode]. */
            data class Switch(val mode: ModeInfo) : Decision()
        }

        /**
         * The state transition behind [match]. Pure. [target] is the mode the display is heading
         * to (our pending request when there is one, not the mode it still shows), [requestedModeId]
         * our request (0 = none), [baseline] the mode before our first switch (null = none).
         * A video that needs no switch never inherits the previous video's request: an unknown
         * frame rate, no fitting mode, or the TV's own mode fitting all take it back.
         */
        fun decide(modes: List<ModeInfo>, baseline: ModeInfo?, target: ModeInfo, requestedModeId: Int, fps: Float): Decision {
            val takeBack = if (requestedModeId != 0) Decision.Restore else Decision.Keep
            if (!(fps > 0f) || !fps.isFinite()) return takeBack
            val chosen = pick(modes, baseline ?: target, target, fps) ?: return takeBack
            if (baseline != null && chosen.id == baseline.id) return takeBack
            if (chosen.id == target.id) return Decision.Keep
            return Decision.Switch(chosen)
        }

        /**
         * How well [refresh] Hz shows [fps]: 2 = a whole multiple of the exact rate, 1 = of its
         * NTSC twin (x1000/1001: 24 <-> 23.976, 30 <-> 29.97, 60 <-> 59.94; either direction),
         * 0 = neither. Tolerance 0.05 % (24 vs 23.976 is 0.1 %, so they stay apart).
         */
        fun fit(refresh: Float, fps: Float): Int {
            fun multiple(r: Double, f: Double): Boolean {
                if (f <= 0.0) return false
                val k = (r / f).roundToInt()
                return k >= 1 && abs(r - k * f) <= r * 0.0005
            }
            val r = refresh.toDouble()
            val f = fps.toDouble()
            if (multiple(r, f)) return 2
            if (multiple(r, f * 1000.0 / 1001.0) || multiple(r, f * 1001.0 / 1000.0)) return 1
            return 0
        }

        /**
         * The mode for [fps], or null (nothing fits): the current mode when it already fits, even
         * only as the NTSC twin (no HDMI re-sync for nothing); else among [modes] at [baseline]'s resolution, the best fit
         * (exact before NTSC), then the highest refresh rate (30p on 60 Hz rather than 30 Hz, so
         * the page's UI stays smooth). Pure.
         */
        fun pick(modes: List<ModeInfo>, baseline: ModeInfo, current: ModeInfo, fps: Float): ModeInfo? {
            if (!(fps > 0f)) return null
            val sameSize = modes.filter { it.width == baseline.width && it.height == baseline.height }
            val best = sameSize
                .map { it to fit(it.refreshRate, fps) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<ModeInfo, Int>> { it.second }.thenByDescending { it.first.refreshRate })
                .firstOrNull()
                ?: return null
            val currentFit = if (current.width == baseline.width && current.height == baseline.height) fit(current.refreshRate, fps) else 0
            // Any fit of the current mode wins (59.94 Hz for a "60" fps video): a switch costs a
            // 1-3 s HDMI blackout for at most one repeated frame every ~17 s.
            if (currentFit > 0) return current
            // Back to the TV's own mode whenever it fits (a 60p video after a 24p one).
            if (fit(baseline.refreshRate, fps) > 0) return baseline
            return best.first
        }
    }
}
