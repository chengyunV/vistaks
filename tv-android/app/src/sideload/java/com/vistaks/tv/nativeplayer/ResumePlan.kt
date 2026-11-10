package com.vistaks.tv.nativeplayer

import org.json.JSONObject

/**
 * How a resume after a pause starts (docs/adr/0100, "續播重新定位實測"). Free of Android and Media3
 * so the JVM tests drive it.
 *
 * - [ResumeMode.RESEEK] (the default): once paused for [ResumeConfig.repositionAfterMs], the
 *   player seeks to where it is, still paused. The seek flushes the audio output (a new AudioTrack
 *   from the paused position) and restarts the decoders there, so the resume is a fresh start: no
 *   sound was lost in the paused HDMI path, and [ResumeClock]'s fresh-track guard holds the
 *   picture until that sound comes out. A shorter pause resumes the old track under the guard's
 *   slew (its real gap is small then: SHIELD 2026-11-10, 12-63 ms for 0.3-2 s).
 * - [ResumeMode.SLEW]: never reposition (2026-11-10's fix alone; `resumeMode: "slew"` in a debug
 *   build's nativeLoad, for comparisons).
 */
internal enum class ResumeMode { SLEW, RESEEK }

internal data class ResumeConfig(
    val mode: ResumeMode = ResumeMode.RESEEK,
    /**
     * A reposition decodes from the previous keyframe (SHIELD, 4K60 VP9: 80-3000 ms, median
     * ~750); a play that comes meanwhile waits for it. 2 s keeps that wait off the short pauses.
     */
    val repositionAfterMs: Long = 2_000,
    /** Arm the guard on a new track's first play too (a reposition, a seek, a load). */
    val freshGuard: Boolean = true,
    /** The old track's resume pre-roll (2026-11-10: no effect; kept for measurements). */
    val resumePrerollMs: Long = 0,
) {
    companion object {
        val DEFAULT = ResumeConfig()

        /** nativeLoad's extras; only a debug build reads them, a release build plays [DEFAULT]. */
        fun parse(extra: JSONObject?, debug: Boolean): ResumeConfig =
            if (!debug || extra == null) DEFAULT else parse { key -> if (extra.has(key)) extra.opt(key) else null }

        /** [get] returns the extra's value for a key, or null when it is not there. */
        fun parse(get: (String) -> Any?): ResumeConfig {
            val mode = (get("resumeMode") as? String)?.trim()
            fun long(key: String, fallback: Long, max: Long): Long = ((get(key) as? Number)?.toLong() ?: fallback).coerceIn(0, max)
            return ResumeConfig(
                mode = ResumeMode.values().firstOrNull { it.name.equals(mode, ignoreCase = true) } ?: DEFAULT.mode,
                repositionAfterMs = long("repositionAfterMs", DEFAULT.repositionAfterMs, 60_000),
                freshGuard = get("freshGuard") as? Boolean ?: DEFAULT.freshGuard,
                resumePrerollMs = long("resumePrerollMs", DEFAULT.resumePrerollMs, 1_000),
            )
        }
    }
}

internal enum class Reposition { NONE, IN_FLIGHT, DONE }

internal sealed class ResumeAction {
    /** Start the clock now. */
    object PlayNow : ResumeAction() {
        override fun toString() = "PlayNow"
    }
    /** Wait this long more (the source paused and ready), then play. */
    data class Preroll(val ms: Long) : ResumeAction()
    /** A reposition is under way: play when it is done. */
    object WaitForReposition : ResumeAction() {
        override fun toString() = "WaitForReposition"
    }
}

internal object ResumePlanner {
    /** The page said play after a pause; [state] is the reposition's. */
    fun onPlay(config: ResumeConfig, state: Reposition): ResumeAction = when (state) {
        Reposition.IN_FLIGHT -> ResumeAction.WaitForReposition
        // A fresh start: the decoder had the whole pause to fill its queue.
        Reposition.DONE -> ResumeAction.PlayNow
        // The old track (a short pause, or SLEW): the guard's slew.
        Reposition.NONE -> if (config.resumePrerollMs > 0) ResumeAction.Preroll(config.resumePrerollMs) else ResumeAction.PlayNow
    }

    /**
     * When a pause should reposition (ms after the pause), or null: never. Not above
     * [MAX_REPOSITION_RATE]: at 1.5x a 4K60 decoder runs at 90 fps, and once (of 7 resumes, SHIELD
     * 2026-11-10) the restart from the keyframe fell behind for good (307 drops); the old track's
     * slew never did.
     */
    fun repositionDelay(config: ResumeConfig, rate: Float = 1f): Long? =
        if (config.mode == ResumeMode.SLEW || rate > MAX_REPOSITION_RATE) null else config.repositionAfterMs

    /**
     * When the pause that a resume ends began (elapsedRealtime), after the player was paused at
     * [now]: a player that was playing ([wasPlaying]) starts its pause now; one already paused
     * keeps the pause it had ([previous]); none known (0) starts now. The WebView-rebuild hold is
     * such a pause: without it the first resume after a rebuild measured "time since boot"
     * (2026-11-10 log: `resume (paused 208471286 ms)`).
     */
    fun pauseStart(wasPlaying: Boolean, previous: Long, now: Long): Long =
        if (wasPlaying || previous <= 0L || previous > now) now else previous

    const val MAX_REPOSITION_RATE = 1.25f
}
