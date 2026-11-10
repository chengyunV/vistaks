package com.vistaks.tv.nativeplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The resume guard's arithmetic (ResumeClock), driven by a fake clock in 10 ms steps. */
class ResumeClockTest {

    private val logs = mutableListOf<String>()
    private val clock = ResumeClock { logs += it }
    private var nowNs = 1_000_000_000L
    private var rawUs = 0L

    private fun advance(ms: Long, rawStepUs: Long = ms * 1_000): Long {
        nowNs += ms * 1_000_000
        rawUs += rawStepUs
        return clock.position(rawUs, nowNs, 1f, true)
    }

    /** Plays [ms] from a fresh track with raw = real time, then pauses: P is what was handed out. */
    private fun playThenPause(ms: Long): Long {
        clock.play(nowNs, 0, true)
        var out = 0L
        repeat((ms / 10).toInt()) { out = advance(10) }
        clock.pause(nowNs)
        return out
    }

    @Test
    fun firstPlayOfATrackIsNeverGuarded() {
        clock.play(nowNs, 0, true)
        assertFalse(clock.armed)
        assertEquals(10_000L, advance(10))
        assertEquals(20_000L, advance(10))
    }

    @Test
    fun pausedHoldsWhatTheVideoSawLast() {
        val p = playThenPause(1_000)
        // The tracker's paused value may already carry the jump; the video keeps P.
        assertEquals(p, clock.position(p + 150_000, nowNs, 1f, true))
    }

    @Test
    fun aReportedJumpIsHeldBackAndHandedOffOnceTheTrackerAgrees() {
        val p = playThenPause(1_000)
        nowNs += 3_000_000_000L
        clock.play(nowNs, 0, true)
        assertTrue(clock.armed)
        // Raw jumps 150 ms ahead on the first read after play().
        rawUs = p + 150_000
        assertEquals(p, clock.position(rawUs, nowNs, 1f, true))
        var last = p
        var t = 0L
        clock.advancing(nowNs + 100_000_000, 0)
        // The tracker smooths the jump away at 10% of real time (H1): raw advances at 0.9x.
        while (clock.armed && t < 4_000) {
            t += 10
            val target = p + t * 1_000 + maxOf(0L, 150_000 - 100 * t)
            val out = advance(10, rawStepUs = target - rawUs)
            assertTrue("never above raw", out <= rawUs)
            assertTrue("never backwards", out >= last)
            if (clock.armed) assertEquals("P + elapsed while guarded", p + t * 1_000, out)
            last = out
        }
        assertEquals("converged", clock.lastEnd)
        // Passthrough afterwards.
        assertEquals(rawUs + 10_000, advance(10))
    }

    @Test
    fun aRealJumpIsSlewedInAfterTwoSeconds() {
        val p = playThenPause(1_000)
        clock.play(nowNs, 0, true)
        rawUs = p + 60_000 // the audio really is 60 ms ahead (H3), and stays so
        clock.position(rawUs, nowNs, 1f, true)
        clock.advancing(nowNs, 0)
        var last = p
        var maxStepUs = 0L
        var t = 0L
        while (clock.armed && t < 6_500) {
            val out = advance(10)
            t += 10
            assertTrue(out <= rawUs)
            assertTrue(out >= last)
            maxStepUs = maxOf(maxStepUs, out - last)
            last = out
            if (t < ResumeClock.SLEW_AFTER_MS) assertEquals(p + t * 1_000, out)
        }
        assertEquals("slewed", clock.lastEnd)
        // 60 ms at 6%: ~1 s of slewing after the 2 s wait.
        assertTrue("ended at $t ms", t in 2_900..3_200)
        // At most 6% faster than real time (plus one step of rounding).
        assertTrue("step $maxStepUs", maxStepUs <= 10_600 + 1)
        assertTrue(logs.any { it.contains("slewing") })
    }

    @Test
    fun aLargeRealJumpIsSlewedFasterAndStillMakesTheCap() {
        val p = playThenPause(1_000)
        clock.play(nowNs, 0, true)
        rawUs = p + 290_000 // SHIELD worst case in the 50-resume soak
        clock.position(rawUs, nowNs, 1f, true)
        clock.advancing(nowNs + 200_000_000, 0)
        var t = 0L
        var last = p
        var maxStepUs = 0L
        while (clock.armed && t < 7_000) {
            val out = advance(10)
            t += 10
            assertTrue(out <= rawUs)
            maxStepUs = maxOf(maxStepUs, out - last)
            last = out
        }
        assertEquals("slewed", clock.lastEnd)
        assertTrue("ended at $t ms", t <= ResumeClock.GIVE_UP_MS - ResumeClock.SLEW_MARGIN_MS + 20)
        assertTrue("step $maxStepUs", maxStepUs <= (10_000 * (1 + ResumeClock.SLEW_MAX)).toLong() + 1)
    }

    @Test
    fun slewRateIsTheDefaultUnlessTheCapNeedsMore() {
        assertEquals(ResumeClock.SLEW, ResumeClock.slewRateFor(60_000, 2_200), 1e-9)
        assertEquals(290.0 / 3_300, ResumeClock.slewRateFor(290_000, 2_200), 1e-9)
        assertEquals(ResumeClock.SLEW_MAX, ResumeClock.slewRateFor(2_000_000, 2_200), 1e-9)
    }

    @Test
    fun withoutAdvancingTheGuardGivesUp() {
        val p = playThenPause(500)
        clock.play(nowNs, 0, true)
        rawUs = p + 150_000
        var t = 0L
        while (clock.armed && t < 7_000) {
            advance(10)
            t += 10
        }
        assertEquals("timeout", clock.lastEnd)
        assertTrue(t > ResumeClock.GIVE_UP_MS)
    }

    @Test
    fun anUnderrunOrAPauseEndsTheGuard() {
        val p = playThenPause(500)
        clock.play(nowNs, 0, true)
        rawUs = p + 150_000
        advance(10)
        clock.underrun(nowNs)
        assertFalse(clock.armed)
        assertEquals("underrun", clock.lastEnd)
        assertEquals(rawUs + 10_000, advance(10))

        clock.pause(nowNs)
        clock.play(nowNs, 0, true)
        assertTrue(clock.armed)
        clock.pause(nowNs)
        assertEquals("paused", clock.lastEnd)
    }

    @Test
    fun aFlushMeansANewTrackThatIsNotGuarded() {
        playThenPause(500)
        clock.flush()
        clock.play(nowNs, 0, true)
        assertFalse(clock.armed)
    }

    @Test
    fun disabledIsAPassthrough() {
        clock.play(nowNs, 0, false)
        advance(500)
        clock.pause(nowNs)
        assertEquals(rawUs + 150_000, clock.position(rawUs + 150_000, nowNs, 1f, false))
        clock.play(nowNs, 0, false)
        assertFalse(clock.armed)
        rawUs += 150_000
        assertEquals(rawUs, clock.position(rawUs, nowNs, 1f, false))
        assertNull(clock.lastEnd)
    }

    @Test
    fun aSpeedChangeEndsAFreshGuard() {
        clock.flush()
        clock.play(nowNs, 0, true, rawUs = 0, freshGuard = true)
        assertTrue(clock.armed)
        advance(10)
        clock.speedChanged(nowNs)
        assertFalse(clock.armed)
        assertEquals("speed", clock.lastEnd)
    }

    @Test
    fun aFreshTrackNeverRunsAheadOfRealTime() {
        ResumeClock.learnedHeadLeadUs = 135_000L
        clock.flush()
        clock.play(nowNs, 0, true, rawUs = 0, freshGuard = true)
        // The head-based report leaps 60 ms in one 10 ms step as the HDMI path starts pulling.
        rawUs = 135_000L
        advance(10, rawStepUs = 0)
        val before = clock.position(rawUs, nowNs, 1f, true)
        val after = advance(10, rawStepUs = 60_000)
        assertTrue("moved ${after - before} us in 10 ms", after - before <= 10_000)
    }

    @Test
    fun aFreshTrackFollowsTheSoundNotTheReport() {
        ResumeClock.learnedHeadLeadUs = 135_000L
        clock.flush()
        rawUs = 0
        clock.play(nowNs, 0, true, rawUs = 0, freshGuard = false)
        assertFalse(clock.armed)
        clock.pause(nowNs)
        clock.flush()
        clock.play(nowNs, 0, true, rawUs = 0, freshGuard = true)
        assertTrue(clock.armed)
        assertTrue(logs.any { it.contains("fresh track") })
        // SHIELD 2026-11-10: the sound comes out 250 ms after play(); the tracker's head-based report
        // runs 140 ms ahead of it until timestamps arrive (callback ~150 ms later), then Media3 takes
        // the lead back at 10% of real time.
        val soundAt = 250L
        val callbackAt = 400L
        var t = 0L
        var last = 0L
        while (clock.armed && t < 4_000) {
            t += 10
            val truth = maxOf(0L, t - soundAt) * 1_000
            val lead = if (t < callbackAt) 140_000L else maxOf(0L, 140_000L - 100L * (t - callbackAt))
            val report = maxOf(0L, (t - soundAt) * 1_000 + lead)
            if (t == callbackAt) clock.advancing(nowNs + 10_000_000, soundAt)
            val out = advance(10, rawStepUs = maxOf(report, rawUs) - rawUs)
            assertTrue(out <= rawUs)
            assertTrue("never backwards", out >= last)
            if (clock.armed) assertTrue("t=$t out=$out truth=$truth", out - truth in 0..6_000)
            last = out
        }
        assertEquals("converged", clock.lastEnd)
        assertTrue("the report's lead is smoothed away first (t=$t)", t >= 1_700)
        assertEquals("the lead is learned", 140_000.0, ResumeClock.learnedHeadLeadUs.toDouble(), 2_000.0)
        assertTrue(logs.any { it.contains("guard off (converged)") && it.contains("L=250 ms") && it.contains("fresh track") })
    }

    @Test
    fun aFreshGuardNeedsTheTracksStart() {
        clock.play(nowNs, 0, true, freshGuard = true)
        assertFalse("no start position given", clock.armed)
    }

    @Test
    fun aSpeedChangeReanchorsAtTheLastValueHandedOut() {
        val p = playThenPause(500)
        clock.play(nowNs, 0, true)
        rawUs = p + 500_000
        nowNs += 100_000_000
        val before = clock.position(rawUs, nowNs, 1f, true)
        assertEquals(p + 100_000, before)
        clock.speedChanged(nowNs)
        nowNs += 100_000_000
        assertEquals(before + 200_000, clock.position(rawUs, nowNs, 2f, true))
    }
}
