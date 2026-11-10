package com.vistaks.tv.nativeplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The resume decision (ResumePlan.kt): which path a play after a pause takes. */
class ResumePlanTest {

    private val reseek = ResumeConfig.DEFAULT

    @Test
    fun theDefaultRepositionsAfterTwoSecondsWithTheFreshGuard() {
        assertEquals(ResumeMode.RESEEK, reseek.mode)
        assertEquals(2_000L, ResumePlanner.repositionDelay(reseek))
        assertEquals(true, reseek.freshGuard)
    }

    @Test
    fun noRepositionAboveTheSafeRate() {
        assertEquals(2_000L, ResumePlanner.repositionDelay(reseek, 1.25f))
        assertNull(ResumePlanner.repositionDelay(reseek, 1.5f))
    }

    @Test
    fun slewModeNeverRepositions() {
        val slew = ResumeConfig(mode = ResumeMode.SLEW)
        assertNull(ResumePlanner.repositionDelay(slew))
        assertEquals(ResumeAction.PlayNow, ResumePlanner.onPlay(slew, Reposition.NONE))
    }

    @Test
    fun aPlayDuringTheRepositionWaitsForItAndOneAfterItStartsAtOnce() {
        assertEquals(ResumeAction.WaitForReposition, ResumePlanner.onPlay(reseek, Reposition.IN_FLIGHT))
        assertEquals(ResumeAction.PlayNow, ResumePlanner.onPlay(reseek, Reposition.DONE))
    }

    @Test
    fun aShortPauseResumesTheOldTrack() {
        assertEquals(ResumeAction.PlayNow, ResumePlanner.onPlay(reseek, Reposition.NONE))
        assertEquals(ResumeAction.Preroll(150), ResumePlanner.onPlay(reseek.copy(resumePrerollMs = 150), Reposition.NONE))
    }

    @Test
    fun onlyADebugBuildReadsTheExtras() {
        // org.json is a stub on the JVM: the release gate is checked without touching it.
        assertEquals(ResumeConfig.DEFAULT, ResumeConfig.parse(null, debug = true))
        val extra = mapOf<String, Any>("resumeMode" to "slew", "repositionAfterMs" to 600, "freshGuard" to false, "resumePrerollMs" to 150)
        val parsed = ResumeConfig.parse { extra[it] }
        assertEquals(ResumeConfig(ResumeMode.SLEW, 600, false, 150), parsed)
        // Unknown values keep the default.
        assertEquals(ResumeConfig.DEFAULT.mode, ResumeConfig.parse { if (it == "resumeMode") "warp" else null }.mode)
        assertEquals(ResumeConfig.DEFAULT, ResumeConfig.parse { null })
        assertEquals(60_000L, ResumeConfig.parse { if (it == "repositionAfterMs") 10_000_000 else null }.repositionAfterMs)
    }

    @Test
    fun aHoldIsAPauseThatBeginsAtTheHold() {
        val boot = 208_471_286L // elapsedRealtime on the SHIELD when the rebuild held the player
        // Playing when the WebView was rebuilt: the pause begins at the hold, not at boot (pausedAt 0).
        assertEquals(boot, ResumePlanner.pauseStart(wasPlaying = true, previous = 0L, now = boot))
        assertEquals(boot, ResumePlanner.pauseStart(wasPlaying = true, previous = 1_000L, now = boot))
        // Already paused by the viewer: the pause keeps its own start.
        assertEquals(1_000L, ResumePlanner.pauseStart(wasPlaying = false, previous = 1_000L, now = boot))
        // Never paused before and not playing (prepared paused): from now.
        assertEquals(boot, ResumePlanner.pauseStart(wasPlaying = false, previous = 0L, now = boot))
        // A start in the future is not a start.
        assertEquals(boot, ResumePlanner.pauseStart(wasPlaying = false, previous = boot + 5, now = boot))
        // The resume after it measures a sane duration.
        val resumeAt = boot + 4_000L
        assertEquals(4_000L, resumeAt - ResumePlanner.pauseStart(wasPlaying = true, previous = 0L, now = boot))
    }
}
