package com.vistaks.tv.nativeplayer

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where the decoder's picture goes on the screen, in whole pixels, and whether the composer may
 * scale it itself (docs/adr/0100, "影像邊緣雜訊").
 *
 * The visible part of the page's box is mapped back to the buffer and rounded inward to even
 * buffer pixels (4:2:0 chroma). When the composer has to scale, the crop keeps [EDGE_INSET] pixels
 * from the buffer's right and bottom edges (padding or nothing lies past them), and from the left
 * and top when the decoder's own crop starts past 0 there. The destination is the visible box in
 * whole pixels, never outside the screen, and an edge less than a pixel short of the screen's edge
 * is the screen's edge (no sliver of the black background beside the picture).
 *
 * The SHIELD's hardware composer (Tegra) draws a band of noise along the right and bottom edges of
 * its output when it scales a layer whose source crop does not start at the buffer's top-left
 * corner (labelled A/B rounds on the TV, 2026-11-10): the whole buffer scaled (×2, ×1.5, ×0.75)
 * and a crop that only cuts the right and bottom are clean; a left offset with a scale is noisy
 * (wider as the scale grows) whatever the resolution, frame rate, inset or layer path; a top
 * offset alone was clean on a 3840-wide buffer with no row padding but noisy at the bottom on
 * fssOk_tBlaY (3240 wide, rows of 3328, top offset 170); GPU composition of the same crops is
 * clean. [needsGpu] is that rule: a scale with any left or top offset.
 */
internal object VideoGeometry {
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** [scaled]: the composer scales the source to the destination (not a 1:1 copy on the display). */
    data class Placement(val source: Rect, val destination: Rect, val scaled: Boolean)

    /** Buffer pixels kept from a padded edge of the picture while the composer scales it. */
    const val EDGE_INSET = 2

    /** A box edge less than this short of the screen's edge (root px) is put on it. */
    private const val SNAP = 1.0

    /**
     * [boxX]..[boxH]: where the whole picture would be (root px, may overflow the screen);
     * [screenW] x [screenH]: the screen (root px); [bufferW] x [bufferH]: the decoder's picture
     * (after its own crop); [displayScale]: display px per root px (the SHIELD shows its 1920x1080
     * window at 3840x2160); [paddedLeft] / [paddedTop]: the decoder's crop starts past 0 there, so
     * padding lies before the picture on that side too; [onGpu]: SurfaceFlinger composes it on the
     * GPU, which reads nothing past the crop's right and bottom (no inset there: with 2 px kept, the
     * GPU left the screen's last column black on the SHIELD); [insetOverride]: a debug A/B inset.
     * Null when nothing of it is on the screen.
     */
    fun place(
        boxX: Double, boxY: Double, boxW: Double, boxH: Double,
        screenW: Int, screenH: Int, bufferW: Int, bufferH: Int,
        displayScale: Double = 1.0, paddedLeft: Boolean = false, paddedTop: Boolean = false,
        onGpu: Boolean = false, insetOverride: Int? = null,
    ): Placement? {
        if (boxW <= 0 || boxH <= 0 || screenW <= 0 || screenH <= 0 || bufferW <= 0 || bufferH <= 0) return null
        val visL = maxOf(boxX, 0.0)
        val visT = maxOf(boxY, 0.0)
        val visR = minOf(boxX + boxW, screenW.toDouble())
        val visB = minOf(boxY + boxH, screenH.toDouble())
        if (visR - visL < 1 || visB - visT < 1) return null
        // The destination: the visible box in whole root px, its edges on the screen's when they
        // fall short of it by less than a pixel (2026-11-10, QcY6Ygp3ahA: the box ended at 1919.5
        // and the rounded 1919 left a 2-display-px column of the black background on the right).
        val dL = if (visL < SNAP) 0 else visL.roundToInt()
        val dT = if (visT < SNAP) 0 else visT.roundToInt()
        val dR = if (screenW - visR < SNAP) screenW else visR.roundToInt()
        val dB = if (screenH - visB < SNAP) screenH else visB.roundToInt()
        if (dR - dL < 1 || dB - dT < 1) return null
        val destination = Rect(dL, dT, dR, dB)
        // The source: the destination mapped back into the buffer, rounded inward to even pixels.
        val sx = bufferW / boxW
        val sy = bufferH / boxH
        var left = evenUp((dL - boxX) * sx).coerceIn(0, bufferW)
        var top = evenUp((dT - boxY) * sy).coerceIn(0, bufferH)
        var right = evenDown((dR - boxX) * sx).coerceIn(left, bufferW)
        var bottom = evenDown((dB - boxY) * sy).coerceIn(top, bufferH)
        if (right - left < 2 || bottom - top < 2) return null
        // 1:1 on the display (a 4K picture on the 4K screen, a 3240-wide one in a 1620-root-px box)
        // is a copy, no filtering, no inset: decided on the integer rectangles the composer gets.
        val oneToOne = kotlin.math.abs(destination.width * displayScale - (right - left)) < 1e-6 &&
            kotlin.math.abs(destination.height * displayScale - (bottom - top)) < 1e-6
        if (!oneToOne) {
            val inset = insetOverride ?: EDGE_INSET
            val far = insetOverride ?: if (onGpu) 0 else EDGE_INSET
            right = minOf(right, bufferW - far)
            bottom = minOf(bottom, bufferH - far)
            if (paddedLeft) left = maxOf(left, inset)
            if (paddedTop) top = maxOf(top, inset)
            if (right - left < 2 || bottom - top < 2) return null
        }
        return Placement(Rect(left, top, right, bottom), destination, scaled = !oneToOne)
    }

    /**
     * A placement the SHIELD's composer cannot scale cleanly (see the class comment): it scales and
     * its source starts past the buffer's left or top edge. Composed on the GPU instead, it is clean.
     */
    fun needsGpu(placement: Placement): Boolean = placement.scaled && (placement.source.left > 0 || placement.source.top > 0)

    /**
     * The picture contained in the screen, centred (root px): the box when the GPU cannot take a
     * [needsGpu] placement — the whole buffer is scaled, no offset, bars instead of noise.
     */
    fun containBox(screenW: Int, screenH: Int, bufferW: Int, bufferH: Int): DoubleArray {
        val scale = minOf(screenW.toDouble() / bufferW, screenH.toDouble() / bufferH)
        val w = bufferW * scale
        val h = bufferH * scale
        return doubleArrayOf((screenW - w) / 2, (screenH - h) / 2, w, h)
    }

    private fun evenUp(v: Double): Int {
        val i = ceil(v - 1e-6).toInt()
        return if (i % 2 == 0) i else i + 1
    }

    private fun evenDown(v: Double): Int {
        val i = floor(v + 1e-6).toInt()
        return if (i % 2 == 0) i else i - 1
    }
}

/**
 * The devices whose hardware composer shows [VideoGeometry.needsGpu]'s noise: NVIDIA Tegra (the
 * SHIELD TV family). Other TVs keep the picture on a hardware overlay (better quality and power).
 */
internal object ComposerQuirks {
    /** SHIELD TV hardware names: foster (2015), darcy (2017, and the 2019 Pro whose product is mdarcy), sif (2019 tube). */
    private val TEGRA_BOARDS = setOf("foster", "darcy", "mdarcy", "sif")

    /** [hardware] Build.HARDWARE, [board] Build.BOARD, [platform] ro.board.platform ("" when unreadable). */
    fun tegraComposer(hardware: String, board: String, platform: String): Boolean {
        val words = listOf(hardware, board, platform).map { it.trim().lowercase() }
        return words.any { it == "tegra" || it.startsWith("tegra") || it in TEGRA_BOARDS }
    }
}
