package com.vistaks.tv.nativeplayer

import com.vistaks.tv.nativeplayer.VideoGeometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The video layer's integer geometry (VideoGeometry): never outside the screen, 2 px from a padded
 * edge while scaled, and which placements the SHIELD's composer cannot scale cleanly (needsGpu).
 */
class VideoGeometryTest {

    @Test
    fun aFullScreen4KPictureIsOneToOneAndWhole() {
        val p = VideoGeometry.place(0.0, 0.0, 3840.0, 2160.0, 3840, 2160, 3840, 2160)!!
        assertEquals(Rect(0, 0, 3840, 2160), p.source)
        assertEquals(Rect(0, 0, 3840, 2160), p.destination)
        assertFalse(p.scaled)
        assertFalse("a copy: the overlay", VideoGeometry.needsGpu(p))
    }

    @Test
    fun a1440pPictureScaledToTheScreenKeepsTwoPixelsFromItsPaddedEdges() {
        val p = VideoGeometry.place(0.0, 0.0, 3840.0, 2160.0, 3840, 2160, 2560, 1440)!!
        assertEquals(Rect(0, 0, 2558, 1438), p.source)
        assertEquals("no black edge", Rect(0, 0, 3840, 2160), p.destination)
        assertTrue(p.scaled)
        assertFalse("the whole buffer scaled (A/B L, L2): clean on the overlay", VideoGeometry.needsGpu(p))
    }

    @Test
    fun allNightsFillBoxReachesTheRightEdgeAndGoesToTheGpu() {
        // SHIELD 2026-11-10, QcY6Ygp3ahA (3840x2160, itag 315) in the page's fill box (contentBox
        // x=0.074): root box -153.4,-44.0 2072.9x1166.0, scaled x1.08 with a left crop of 286.
        val p = VideoGeometry.place(-153.4, -44.0, 2072.9, 1166.0, 1920, 1080, 3840, 2160, displayScale = 2.0)!!
        assertEquals("the whole screen", Rect(0, 0, 1920, 1080), p.destination)
        assertEquals(Rect(286, 82, 3838, 2082), p.source)
        assertTrue("A/B A: a scaled left crop is noisy on the overlay", VideoGeometry.needsGpu(p))
        // On the GPU the crop reaches the buffer's right edge: with 2 px kept the GPU left the
        // screen's last column black (screencap 2026-11-10); with 0 it is picture to the edge.
        val g = VideoGeometry.place(-153.4, -44.0, 2072.9, 1166.0, 1920, 1080, 3840, 2160, displayScale = 2.0, onGpu = true)!!
        assertEquals(Rect(286, 82, 3840, 2082), g.source)
        assertEquals(Rect(0, 0, 1920, 1080), g.destination)
        assertTrue("still a GPU placement", VideoGeometry.needsGpu(g))
    }

    @Test
    fun aFillBoxCroppedAtTheTopGoesToTheGpu() {
        // fssOk_tBlaY: 3240x2160 in the page's fill box 3840x2560 at y=-200 (device px).
        val p = VideoGeometry.place(0.0, -200.0, 3840.0, 2560.0, 3840, 2160, 3240, 2160)!!
        val d = p.destination
        assertEquals("the whole screen", Rect(0, 0, 3840, 2160), d)
        val s = p.source
        assertTrue("even source: $s", listOf(s.left, s.top, s.right, s.bottom).all { it % 2 == 0 })
        assertEquals(Rect(0, 170, 3238, 1990), s)
        assertEquals(d.width.toDouble() / s.width, d.height.toDouble() / s.height, 0.005)
        // R–V round (2026-11-10), T: this crop on the overlay was noisy along the bottom (a padded
        // 3240-wide buffer, rows of 3328), though O's top offset on 3840 (no padding) was clean.
        assertTrue("T: a scaled top offset goes to the GPU", VideoGeometry.needsGpu(p))
    }

    @Test
    fun theAbRoundsGeometriesSortAsTheOwnerSawThem() {
        // Round 3 (2026-11-10), QcY6Ygp3ahA 3840x2160 on the 1920x1080 root at display x2.
        fun at(x: Double, y: Double, w: Double, h: Double, bw: Int = 3840, bh: Int = 2160) =
            VideoGeometry.place(x, y, w * 2, h * 2, 1920, 1080, bw, bh, displayScale = 2.0, insetOverride = null)!!
        // CSS rects from the A/B (×2 to root px), x and y too.
        fun css(x: Double, y: Double, w: Double, h: Double, bw: Int = 3840, bh: Int = 2160) = at(x * 2, y * 2, w, h, bw, bh)
        val clean = mapOf(
            "B 1:1" to css(0.0, 0.0, 960.0, 540.0),
            "K cap 1080 whole" to css(0.0, 0.0, 960.0, 540.0, 1920, 1080),
            "L cap 1440 whole" to css(0.0, 0.0, 960.0, 540.0, 2560, 1440),
            "M top-left crop" to css(0.0, 0.0, 1039.0, 584.4),
            "P shrunk, centred" to css(120.0, 67.5, 720.0, 405.0),
        )
        val noisy = mapOf(
            "A page frame" to css(-77.0, -22.0, 1037.0, 583.0),
            "D cap 1440" to css(-77.0, -22.0, 1037.0, 583.0, 2560, 1440),
            "E cap 1080" to css(-77.0, -22.0, 1037.0, 583.0, 1920, 1080),
            "F centred x1.08" to css(-38.5, -21.5, 1037.0, 583.0),
            "G centred x1.33" to css(-160.0, -90.0, 1280.0, 720.0),
            "N left offset" to css(-77.0, 0.0, 1037.0, 583.0),
            // Clean on a 3840 buffer without padding, but T (fssOk, padded) showed it: any top offset goes.
            "O top offset" to css(0.0, -43.0, 1037.0, 583.0),
        )
        for ((name, p) in clean) assertFalse("$name: ${p.source} stays on the overlay", VideoGeometry.needsGpu(p))
        for ((name, p) in noisy) assertTrue("$name: ${p.source} goes to the GPU", VideoGeometry.needsGpu(p))
    }

    @Test
    fun a1080pPictureOnThe4KScreenKeepsTwoPixelsFromRightAndBottom() {
        val p = VideoGeometry.place(0.0, 0.0, 1920.0, 1080.0, 1920, 1080, 1920, 1080, displayScale = 2.0)!!
        assertEquals(Rect(0, 0, 1918, 1078), p.source)
        assertEquals(Rect(0, 0, 1920, 1080), p.destination)
        assertFalse(VideoGeometry.needsGpu(p))
    }

    @Test
    fun aContainedPictureAtOneToOneIsCopiedWhole() {
        // fssOk_tBlaY contained: 3240x2160 in a 1620x1080 root box = 3240x2160 display px.
        val p = VideoGeometry.place(150.0, 0.0, 1620.0, 1080.0, 1920, 1080, 3240, 2160, displayScale = 2.0)!!
        assertEquals(Rect(0, 0, 3240, 2160), p.source)
        assertEquals(Rect(150, 0, 1770, 1080), p.destination)
        assertFalse(VideoGeometry.needsGpu(p))
    }

    @Test
    fun aLetterboxedPortraitPictureStaysWhereItWas() {
        // 2160x3840 contained in the 3840x2160 screen: 1215x2160 centred, shrunk x0.5625.
        val p = VideoGeometry.place(1312.5, 0.0, 1215.0, 2160.0, 3840, 2160, 2160, 3840)!!
        assertEquals(Rect(1313, 0, 2528, 2160), p.destination)
        // The box starts at 1312.5: the destination's 1313 maps to buffer column 0.89 -> 2.
        assertEquals(Rect(2, 0, 2158, 3838), p.source)
        assertTrue("a 2 px left crop under a scale: the GPU", VideoGeometry.needsGpu(p))
    }

    @Test
    fun aDecoderCropPastZeroKeepsTheInsetOnThatSideToo() {
        val p = VideoGeometry.place(0.0, 0.0, 1920.0, 1080.0, 1920, 1080, 1920, 1080, displayScale = 2.0, paddedLeft = true, paddedTop = true)!!
        assertEquals(Rect(2, 2, 1918, 1078), p.source)
        assertTrue(VideoGeometry.needsGpu(p))
    }

    @Test
    fun aDebugInsetOverrideReplacesTheDefault() {
        val p = VideoGeometry.place(0.0, 0.0, 1920.0, 1080.0, 1920, 1080, 2560, 1440, displayScale = 2.0, insetOverride = 0)!!
        assertEquals("A/B L2: the whole buffer", Rect(0, 0, 2560, 1440), p.source)
        assertEquals(Rect(0, 0, 1920, 1080), p.destination)
    }

    @Test
    fun theShieldsWindowScaleCountsAsOneToOne() {
        // root 1920x1080 shown at 3840x2160: a 4K buffer in the whole root is 1:1 on the display.
        val p = VideoGeometry.place(0.0, 0.0, 1920.0, 1080.0, 1920, 1080, 3840, 2160, displayScale = 2.0)!!
        assertEquals(Rect(0, 0, 3840, 2160), p.source)
        assertFalse(p.scaled)
    }

    @Test
    fun aSlightlyOffOneToOneBoxIsScaledAndGetsTheInset() {
        // 0.25 root px off: the composer scales (and filters), so it is not a copy.
        val p = VideoGeometry.place(0.0, 0.0, 1920.25, 1080.0, 1920, 1080, 3840, 2160, displayScale = 2.0)!!
        assertTrue(p.scaled)
        assertTrue("${p.source}", p.source.right <= 3840 - VideoGeometry.EDGE_INSET)
    }

    @Test
    fun theContainedFallbackHasNoLeftCrop() {
        for ((bw, bh) in listOf(3840 to 2160, 3240 to 2160, 2160 to 3840, 1920 to 800)) {
            val b = VideoGeometry.containBox(1920, 1080, bw, bh)
            val p = VideoGeometry.place(b[0], b[1], b[2], b[3], 1920, 1080, bw, bh, displayScale = 2.0)!!
            assertTrue("contained $bw x $bh is on the screen", p.destination.left >= 0 && p.destination.right <= 1920)
            assertFalse("contained $bw x $bh: ${p.source} needs no GPU", VideoGeometry.needsGpu(p))
        }
    }

    @Test
    fun noBoxEverPutsTheLayerOutsideThePictureOrTheScreen() {
        val rnd = Random(20261110)
        val buffers = listOf(3840 to 2160, 3240 to 2160, 2160 to 3840, 2560 to 1440, 1920 to 1080, 3840 to 1608, 3840 to 1920, 1280 to 720)
        repeat(20_000) {
            val (bw, bh) = buffers[rnd.nextInt(buffers.size)]
            val zoom = 0.3 + rnd.nextDouble() * 1.5
            val aspect = bw.toDouble() / bh
            val w = 1920 * zoom
            val h = w / aspect * (0.995 + rnd.nextDouble() * 0.01)
            val x = (1920 - w) / 2 + (rnd.nextDouble() - 0.5) * 200
            val y = (1080 - h) / 2 + (rnd.nextDouble() - 0.5) * 200
            val p = VideoGeometry.place(x, y, w, h, 1920, 1080, bw, bh, displayScale = 2.0) ?: return@repeat
            val s = p.source
            val d = p.destination
            assertTrue("dest on screen $d", d.left >= 0 && d.top >= 0 && d.right <= 1920 && d.bottom <= 1080 && d.width > 0 && d.height > 0)
            assertTrue("even $s", listOf(s.left, s.top, s.right, s.bottom).all { it % 2 == 0 })
            assertTrue("source in picture $s", s.left >= 0 && s.top >= 0 && s.right <= bw && s.bottom <= bh)
            if (p.scaled) {
                assertTrue("right $s of $bw x $bh", s.right <= bw - VideoGeometry.EDGE_INSET)
                assertTrue("bottom $s of $bw x $bh", s.bottom <= bh - VideoGeometry.EDGE_INSET)
            }
            assertEquals("needsGpu is a scale with a left or top crop", p.scaled && (s.left > 0 || s.top > 0), VideoGeometry.needsGpu(p))
            // No sliver: a box reaching (or nearly reaching) a screen edge covers it.
            if (x + w > 1919) assertEquals(1920, d.right)
            if (y + h > 1079) assertEquals(1080, d.bottom)
            if (x < 1) assertEquals(0, d.left)
            if (y < 1) assertEquals(0, d.top)
        }
    }

    @Test
    fun nothingOnTheScreenIsNull() {
        assertNull(VideoGeometry.place(4000.0, 0.0, 100.0, 100.0, 3840, 2160, 1920, 1080))
        assertNull(VideoGeometry.place(0.0, 0.0, 0.0, 100.0, 3840, 2160, 1920, 1080))
        assertNotNull(VideoGeometry.place(0.0, 0.0, 100.0, 100.0, 3840, 2160, 1920, 1080))
    }

    @Test
    fun onlyTegraComposersAreSentToTheGpu() {
        // This SHIELD TV Pro (2019, product mdarcy): Build.HARDWARE darcy, Build.BOARD darcy, ro.board.platform tegra.
        assertTrue(ComposerQuirks.tegraComposer("darcy", "darcy", "tegra"))
        assertTrue("platform unreadable", ComposerQuirks.tegraComposer("darcy", "darcy", ""))
        assertTrue("SHIELD 2019", ComposerQuirks.tegraComposer("sif", "sif", ""))
        assertTrue("SHIELD 2015", ComposerQuirks.tegraComposer("foster", "foster", ""))
        assertTrue("another Tegra board", ComposerQuirks.tegraComposer("odin", "odin", "tegra"))
        assertFalse("Chromecast with Google TV (Amlogic)", ComposerQuirks.tegraComposer("sabrina", "sabrina", "sm1"))
        assertFalse("a MediaTek TV", ComposerQuirks.tegraComposer("mt5895", "m7642", "mt5895"))
        assertFalse("the emulator", ComposerQuirks.tegraComposer("ranchu", "goldfish_x86", ""))
    }
}
