package com.vistaks.tv.nativeplayer

import android.content.Context
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.ThrowingRunnable
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import com.vistaks.tv.BuildConfig
import kotlin.math.min

/**
 * Renderers whose audio output keeps the media clock continuous across pause→play (the SHIELD's
 * HDMI resume jump, docs/qa/round17/tv-resume-drops-plan.md). Everything else is
 * DefaultRenderersFactory's: the provider below is built with the same defaults
 * DefaultAudioSink.Builder.build() fills in (offload support provider, DEFAULT buffer sizes).
 */
@OptIn(UnstableApi::class)
internal class ResumeClockRenderersFactory(
    context: Context,
    private val policy: () -> ResumeClock.Policy,
    /** Every output format a video decoder reports (playback thread): its crop, stride and slice height. */
    private val onVideoOutputFormat: ((MediaFormat) -> Unit)? = null,
) : DefaultRenderersFactory(context) {
    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory {
        val base = super.getCodecAdapterFactory()
        val listener = onVideoOutputFormat ?: return base
        return MediaCodecAdapter.Factory { configuration ->
            val inner = base.createAdapter(configuration)
            if (configuration.format.sampleMimeType?.startsWith("video/") != true) inner
            else OutputFormatTap(inner, listener)
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val base = AudioTrackAudioOutputProvider.Builder(context).build()
        val provider = object : ForwardingAudioOutputProvider(base) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                val inner = super.getAudioOutput(config)
                // Offload and tunneling run their own clocks (the platform's); never wrapped.
                return if (config.isOffload || config.isTunneling) inner else ResumeClockAudioOutput(inner, policy)
            }
        }
        return DefaultAudioSink.Builder(context)
            .setAudioOutputProvider(provider)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
    }
}

/** A video decoder whose output format changes are reported ([ResumeClockRenderersFactory]). */
@OptIn(UnstableApi::class)
private class OutputFormatTap(
    private val inner: MediaCodecAdapter,
    private val listener: (MediaFormat) -> Unit,
) : MediaCodecAdapter by inner {
    override fun getOutputFormat(): MediaFormat = inner.outputFormat.also { listener(it) }
    override fun <E : Exception> useBuffer(runnable: ThrowingRunnable<E>) = inner.useBuffer(runnable)
    override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener): Boolean =
        inner.registerOnBufferAvailableListener(listener)
}

/**
 * One AudioTrack's output with a [ResumeClock] over its position. DefaultAudioSink releases the
 * output on every flush (seek, new source), so each instance only ever sees one track: its first
 * play is a fresh start (guarded when the policy says so), every later play a resume of the same
 * track. All calls arrive on the playback thread.
 */
@OptIn(UnstableApi::class)
internal class ResumeClockAudioOutput(
    inner: AudioOutput,
    private val policy: () -> ResumeClock.Policy,
) : ForwardingAudioOutput(inner) {
    private val clock = ResumeClock(trace = BuildConfig.DEBUG) { Log.i(ResumeClock.TAG, it) }

    private val tap = object : AudioOutput.Listener {
        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) =
            clock.advancing(System.nanoTime(), playoutStartSystemTimeMs)
        override fun onUnderrun() = clock.underrun(System.nanoTime())
        override fun onOffloadDataRequest() {}
        override fun onOffloadPresentationEnded() {}
        override fun onReleased() {}
    }

    init {
        inner.addListener(tap)
    }

    override fun play() {
        val p = policy()
        // The track's own start (before play() the tracker reports the head, i.e. its first sample).
        val startUs = if (p.freshGuard && clock.lastOutUs == ResumeClock.UNSET) super.getPositionUs() else ResumeClock.UNSET
        clock.play(System.nanoTime(), System.currentTimeMillis(), p.enabled && !isOffloadedPlayback, startUs, p.freshGuard)
        super.play()
    }

    override fun pause() {
        clock.pause(System.nanoTime())
        super.pause()
    }

    override fun flush() {
        clock.flush()
        super.flush()
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
        super.setPlaybackParameters(playbackParams)
        clock.speedChanged(System.nanoTime())
    }

    override fun getPositionUs(): Long =
        clock.position(super.getPositionUs(), System.nanoTime(), playbackParameters.speed, policy().enabled)
}

/**
 * The resume guard's arithmetic, free of Android and Media3 so the JVM tests drive it with their
 * own clock.
 *
 * After a pause the position tracker drops its timestamp sync and reports head−latency, which on
 * the SHIELD's HDMI path ran ~150 ms ahead of what was output; the video renderer then dropped
 * 7-11 frames to catch a clock that was wrong. A resume here reports min(raw, P + elapsed×speed),
 * P being the last position handed out before the pause (what the video saw), until the tracker
 * is back on advancing timestamps ([SETTLE_MS] after onPositionAdvancing) and agrees within
 * [HANDOFF_US] ("converged": the jump was only reported, H1). If it still disagrees
 * [SLEW_AFTER_MS] after advancing (the tracker's own 10% smoothing needs ~1.5 s for 150 ms), the
 * audio really jumped (H3): the gap is slewed in at [SLEW] of real time (faster when it would
 * not make the cap: [slewRateFor]), so the video skips at most single frames ("slewed").
 * [GIVE_UP_MS] caps the whole guard. The reported value never exceeds raw, so an underrun cannot
 * push it ahead of the sound.
 *
 * A fresh track (a new AudioTrack: a load, a seek, the host's reposition while paused) has no real
 * gap, only the report: its sound comes out L after play() (SHIELD HDMI: 160-270 ms), the
 * head-based report runs ~140 ms ahead of that sound until timestamps arrive, and the
 * onPositionAdvancing callback (which names the playout start) comes 100-200 ms after the fact.
 * With the fresh guard the picture holds P, then follows report − [learnedHeadLeadUs] (never
 * faster than real time), then P + time since the measured playout start, until the report has
 * smoothed back onto that line ("converged", residual ≈ 0).
 *
 * Positions are in the output's own time (DefaultAudioSink maps them to media time afterwards),
 * so the speed is the output's (1.0 unless the AudioTrack itself plays at a rate).
 */
internal class ResumeClock(private val trace: Boolean = false, private val log: (String) -> Unit) {
    private var lastTraceNs = 0L
    private var traceBaseUs = UNSET
    private var traceUntilNs = 0L
    private var playing = false
    /** The last value handed out while playing (= P at the pause); [UNSET] before the first play. */
    var lastOutUs = UNSET
        private set
    /** != [UNSET]: the guard is armed. */
    private var baseUs = UNSET
    private var t0Ns = 0L
    private var playWallMs = 0L
    private var advancingAtNs = 0L
    private var slewFromNs = 0L
    private var slewRate = SLEW
    private var freshTrack = false
    /** play() to the playout start onPositionAdvancing reported (wall clock), in us. */
    private var latencyUs = 0L
    private var lastOutNs = 0L
    /** How long after play() a fresh track's line starts (the measured L; 0 for the same track). */
    private var delayUs = 0L
    /** A fresh track's lead (report - line) is still to be measured at the first read after advancing. */
    private var headLeadPending = false
    private var firstGapUs = Long.MIN_VALUE
    private var maxGapUs = 0L

    /** "converged" / "slewed" / "timeout" / "underrun" / "paused" / "speed" of the last guard (tests, log). */
    var lastEnd: String? = null
        private set

    val armed: Boolean get() = baseUs != UNSET

    /**
     * [freshGuard] arms the guard on a track's first play too, anchored at [rawUs] (the track's own
     * start, what the tracker reports before play()).
     */
    fun play(nowNs: Long, wallMs: Long, enabled: Boolean, rawUs: Long = UNSET, freshGuard: Boolean = false) {
        traceBaseUs = UNSET
        val fresh = lastOutUs == UNSET
        val anchor = if (!fresh) lastOutUs else if (freshGuard && rawUs != UNSET) rawUs else UNSET
        if (enabled && anchor != UNSET) {
            baseUs = anchor
            t0Ns = nowNs
            playWallMs = wallMs
            advancingAtNs = 0L
            latencyUs = 0L
            slewFromNs = 0L
            firstGapUs = Long.MIN_VALUE
            maxGapUs = 0L
            lastEnd = null
            freshTrack = fresh
            // A fresh track's sound comes out L after play() (the HDMI path wakes up). Until the
            // tracker says when (onPositionAdvancing, ~100-200 ms after the fact), its head-based
            // report runs a near-constant [learnedHeadLeadUs] ahead of the sound: the picture
            // follows report - lead, then the measured line (P + time since the sound came out).
            delayUs = 0L
            headLeadPending = fresh
            if (fresh) {
                lastOutUs = anchor
                lastOutNs = nowNs
            }
            log("resume: guard armed at P=$baseUs us (${if (fresh) "fresh track" else "same track"})")
        }
        playing = true
    }

    fun pause(nowNs: Long) {
        playing = false
        if (armed) end("paused", 0, nowNs)
    }

    fun flush() {
        lastOutUs = UNSET
        baseUs = UNSET
        traceBaseUs = UNSET
    }

    fun advancing(nowNs: Long, playoutStartWallMs: Long) {
        if (!armed) return
        advancingAtNs = nowNs
        latencyUs = ((playoutStartWallMs - playWallMs) * 1_000).coerceAtLeast(0)
        if (freshTrack) delayUs = latencyUs.coerceAtMost(MAX_LATENCY_US)
        log("resume: position advancing, L=${playoutStartWallMs - playWallMs} ms after play()")
    }

    fun underrun(nowNs: Long) {
        if (armed) end("underrun", 0, nowNs)
    }

    /** A new speed while guarded: re-anchor at what was handed out last, at the new speed. */
    fun speedChanged(nowNs: Long) {
        // A fresh track's line is anchored on its playout start; a new speed in the first seconds
        // after a start is rare enough to simply let the report through.
        if (armed && freshTrack) {
            end("speed", 0, nowNs)
            return
        }
        if (armed && lastOutUs != UNSET) {
            baseUs = lastOutUs
            t0Ns = nowNs
            slewFromNs = 0L
        }
    }

    fun position(rawUs: Long, nowNs: Long, speed: Float, enabled: Boolean): Long {
        if (!playing) {
            // Paused: hold what the video saw last (the tracker's paused value may already carry the jump).
            return if (enabled && lastOutUs != UNSET) lastOutUs else rawUs
        }
        if (!armed) {
            if (trace && traceBaseUs != UNSET && nowNs < traceUntilNs && nowNs - lastTraceNs >= TRACE_EVERY_NS) {
                // After the hand-off: does raw keep coming back to P + elapsed (H1) or stay ahead (H3)?
                lastTraceNs = nowNs
                val v = traceBaseUs + (((nowNs - t0Ns) / 1_000.0 - delayUs).coerceAtLeast(0.0) * speed).toLong()
                log("resume: trace-after t=${(nowNs - t0Ns) / 1_000_000} ms raw-virtual=${rawUs - v} us")
            }
            lastOutUs = rawUs
            return rawUs
        }
        val virtualUs = baseUs + (((nowNs - t0Ns) / 1_000.0 - delayUs).coerceAtLeast(0.0) * speed).toLong()
        val gap = rawUs - virtualUs
        if (firstGapUs == Long.MIN_VALUE && rawUs != baseUs) firstGapUs = gap
        if (gap > maxGapUs) maxGapUs = gap
        val sinceAdvMs = if (advancingAtNs != 0L) (nowNs - advancingAtNs) / 1_000_000 else -1
        if (trace && nowNs - lastTraceNs >= TRACE_EVERY_NS) {
            lastTraceNs = nowNs
            log("resume: trace t=${(nowNs - t0Ns) / 1_000_000} ms sinceAdv=$sinceAdvMs ms gap=$gap us")
        }
        if (freshTrack && advancingAtNs == 0L && (nowNs - t0Ns) / 1_000_000 <= GIVE_UP_MS) {
            // Fresh, the sound's start not known yet: the report less its usual lead, never before P.
            val out = maxOf(lastOutUs, minOf(maxOf(baseUs, rawUs - learnedHeadLeadUs), realTimeCap(nowNs, speed, 1.0)))
            if (trace && out > baseUs && lastOutUs == baseUs) {
                log("resume: picture clock leaves P at t=${(nowNs - t0Ns) / 1_000_000} ms by ${out - baseUs} us (report-lead ${rawUs - learnedHeadLeadUs - baseUs} us, last read ${(nowNs - lastOutNs) / 1_000_000} ms ago)")
            }
            lastOutUs = out
            lastOutNs = nowNs
            return out
        }
        if (freshTrack && headLeadPending) {
            headLeadPending = false
            val lead = gap
            if (lead in MIN_HEAD_LEAD_US..MAX_HEAD_LEAD_US) learnedHeadLeadUs = lead
            log("resume: head lead ${lead} us at the hand-over to the measured line (next prediction ${learnedHeadLeadUs} us)")
        }
        val out = when {
            (nowNs - t0Ns) / 1_000_000 > GIVE_UP_MS -> {
                end("timeout", gap, nowNs)
                rawUs
            }
            // gap <= 0 is a raw that lags: min() already hands raw out, so the hand-off is seamless.
            sinceAdvMs >= SETTLE_MS && gap <= HANDOFF_US -> {
                end("converged", gap, nowNs)
                rawUs
            }
            sinceAdvMs >= SLEW_AFTER_MS -> {
                if (slewFromNs == 0L) {
                    slewFromNs = nowNs
                    slewRate = slewRateFor(gap, (nowNs - t0Ns) / 1_000_000)
                    log("resume: gap $gap us persists $sinceAdvMs ms after advancing (audio really jumped); slewing at ${"%.3f".format(slewRate)}")
                }
                val slewed = virtualUs + ((nowNs - slewFromNs) / 1_000.0 * speed * slewRate).toLong()
                if (slewed >= rawUs) {
                    end("slewed", rawUs - slewed, nowNs)
                    rawUs
                } else {
                    slewed
                }
            }
            else -> min(rawUs, virtualUs)
        }
        // Never backwards; a fresh track's picture never runs faster than its sound can (the
        // head-based report overshoots as the HDMI path starts pulling).
        val handed = when {
            !armed || lastOutUs == UNSET -> out
            freshTrack -> maxOf(lastOutUs, minOf(out, realTimeCap(nowNs, speed, FRESH_CATCH_UP)))
            else -> maxOf(out, lastOutUs)
        }
        lastOutUs = handed
        lastOutNs = nowNs
        return handed
    }

    /** The furthest a fresh track's picture may be now: the last value plus real time × speed × [factor]. */
    private fun realTimeCap(nowNs: Long, speed: Float, factor: Double): Long =
        if (lastOutNs == 0L) Long.MAX_VALUE else lastOutUs + ((nowNs - lastOutNs) / 1_000.0 * speed * factor).toLong()

    private fun end(reason: String, residualUs: Long, nowNs: Long) {
        log("resume: guard off ($reason) residual=$residualUs us firstJump=${if (firstGapUs == Long.MIN_VALUE) "none" else "$firstGapUs us"} maxGap=$maxGapUs us L=${latencyUs / 1_000} ms ${if (freshTrack) "fresh" else "same"} track after ${(nowNs - t0Ns) / 1_000_000} ms")
        lastEnd = reason
        if (trace && reason != "paused") {
            traceBaseUs = baseUs
            traceUntilNs = nowNs + TRACE_AFTER_NS
        } else {
            traceBaseUs = UNSET
        }
        baseUs = UNSET
    }

    /** What the host asks of the guard (read at every play(); `enabled` at every position read). */
    data class Policy(val enabled: Boolean = true, val freshGuard: Boolean = false)

    companion object {
        /**
         * [SLEW], or faster when the gap would not be in by [GIVE_UP_MS] (less [SLEW_MARGIN_MS]);
         * at most [SLEW_MAX]. SHIELD 2026-11-10: real gaps of 35-290 ms (longer pauses, larger
         * gaps); 290 ms at 6% after the 2 s wait ran into the cap.
         */
        fun slewRateFor(gapUs: Long, elapsedMs: Long): Double {
            val budgetMs = (GIVE_UP_MS - SLEW_MARGIN_MS - elapsedMs).coerceAtLeast(1)
            return (gapUs / 1_000.0 / budgetMs).coerceIn(SLEW, SLEW_MAX)
        }

        /**
         * How far a fresh track's head-based report runs ahead of the sound before the tracker has
         * timestamps (the output latency the HAL does not report). SHIELD HDMI 2026-11-10: 134-147 ms
         * while L itself (play() to sound) spread 157-267 ms. Learned from every fresh track;
         * shared by the process.
         */
        @Volatile var learnedHeadLeadUs = 135_000L
        /** After the measured line is known, a fresh track's picture may catch up at most this fast. */
        const val FRESH_CATCH_UP = 1.1
        const val MIN_HEAD_LEAD_US = 0L
        const val MAX_HEAD_LEAD_US = 400_000L
        const val MAX_LATENCY_US = 1_000_000L

        const val TAG = "ResumeClock"
        const val UNSET = Long.MIN_VALUE
        const val HANDOFF_US = 3_000L
        const val SETTLE_MS = 200L
        /** The tracker's own 10% smoothing needs ~1.5 s to take back 150 ms. */
        const val SLEW_AFTER_MS = 2_000L
        /** 150 ms in ~2.5 s: at most one drop per ~17 frames. */
        const val SLEW = 0.06
        /** The fastest slew: at most one frame in ~7 (a 400 ms gap from ~2.3 s still makes the cap). */
        const val SLEW_MAX = 0.15
        const val SLEW_MARGIN_MS = 500L
        const val GIVE_UP_MS = 6_000L
        /** Debug builds log the gap this often while guarded (H1 / H3 on the device). */
        const val TRACE_EVERY_NS = 250_000_000L
        const val TRACE_AFTER_NS = 4_000_000_000L
    }
}
