package com.vistaks.tv.nativeplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem

/** HDR candidates from the player answer, and the one rule that ranks them with the extractor's tracks. */
class StreamResolverHdrTest {

    private fun hdr337(url: String? = "https://rr1.googlevideo.com/videoplayback?itag=337&n=abc", cipher: String? = null) = HdrFormat(
        itag = 337,
        url = url,
        signatureCipher = cipher,
        mimeType = "video/webm; codecs=\"vp09.02.51.10.01.09.16.09.00\"",
        codec = "vp09.02.51.10.01.09.16.09.00",
        bitrate = 28_900_000,
        width = 3840,
        height = 2160,
        fps = 60,
        initStart = 0,
        initEnd = 219,
        indexStart = 220,
        indexEnd = 1500,
        contentLength = 123_456_789L,
        approxDurationMs = 215_000L,
        qualityLabel = "2160p60 HDR",
    )

    private fun extractorTrack(itag: Int, codec: String, width: Int, height: Int, fps: Int, bitrate: Int) = VideoCandidate(
        itag = itag,
        codecString = codec,
        codec = StreamResolver.codecOf(codec)!!,
        width = width,
        height = height,
        fps = fps,
        bitrate = bitrate,
        hdr = StreamResolver.isHdr(codec),
        source = StreamResolver.SOURCE_EXTRACTOR,
    )

    /** What the extractor listed for wcPo6Soc_CU on the SHIELD (SDR only, highest 315). */
    private val extractorTracks = listOf(
        extractorTrack(315, "vp9", 3840, 2160, 60, 26_600_000),
        extractorTrack(308, "vp9", 2560, 1440, 60, 13_000_000),
        extractorTrack(303, "vp9", 1920, 1080, 60, 4_400_000),
        extractorTrack(299, "avc1.64002a", 1920, 1080, 60, 6_000_000),
    )

    private val everything: (VideoCandidate) -> Boolean = { true }

    @Test
    fun capturedFormatBecomesAnHdrVp9Candidate() {
        val c = StreamResolver.hdrCandidateOf(hdr337())
        assertNotNull(c)
        c!!
        assertEquals(337, c.itag)
        assertEquals(Codec.VP9, c.codec)
        assertEquals("vp09.02.51.10.01.09.16.09.00", c.codecString)
        assertTrue(c.hdr)
        assertEquals(2160, c.shortEdge)
        assertEquals(60, c.fps)
        assertEquals(StreamResolver.SOURCE_PLAYER_RESPONSE, c.source)
    }

    @Test
    fun itagItemCarriesWhatTheManifestCreatorNeeds() {
        val item = StreamResolver.hdrItagItem(hdr337())
        assertEquals(337, item.id)
        assertEquals(ItagItem.ItagType.VIDEO_ONLY, item.itagType)
        assertEquals(MediaFormat.WEBM, item.mediaFormat)
        assertEquals("vp09.02.51.10.01.09.16.09.00", item.codec)
        assertEquals(3840, item.width)
        assertEquals(2160, item.height)
        assertEquals(60, item.getFps())
        assertEquals(28_900_000, item.bitrate)
        assertEquals(0, item.initStart)
        assertEquals(219, item.initEnd)
        assertEquals(220, item.indexStart)
        assertEquals(1500, item.indexEnd)
        assertEquals(123_456_789L, item.contentLength)
        assertEquals(215_000L, item.approxDurationMs)
        assertEquals("2160p60", item.resolutionString)  // the " HDR" suffix is stripped: the extractor's labels read "2160p60"
    }

    @Test
    fun plainUrlGoesThroughThrottlingThenCpn() {
        val url = StreamResolver.hdrUrlOf(
            "wcPo6Soc_CU",
            hdr337(),
            cpn = "CPN",
            poToken = null,
            deobfuscateSignature = { _, _ -> error("no cipher here") },
            deobfuscateThrottling = { id, u -> assertEquals("wcPo6Soc_CU", id); u.replace("n=abc", "n=ABC") },
        )
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=337&n=ABC&cpn=CPN", url)
    }

    @Test
    fun cipherIsSignedIntoSpBeforeThrottling() {
        val f = hdr337(url = null, cipher = "s=SIGNED%3D%3D&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fitag%3D337")
        val seen = mutableListOf<String>()
        val url = StreamResolver.hdrUrlOf(
            "wcPo6Soc_CU",
            f,
            cpn = "CPN",
            poToken = "PoT",
            deobfuscateSignature = { _, s -> seen += "sig:$s"; s.reversed() },
            deobfuscateThrottling = { _, u -> seen += "n:$u"; u },
        )
        assertEquals(listOf("sig:SIGNED==", "n:https://rr1.googlevideo.com/videoplayback?itag=337&sig===DENGIS"), seen)
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=337&sig===DENGIS&cpn=CPN&pot=PoT", url)
    }

    @Test
    fun withoutHdrAllowedTheHdrTrackIsNeverChosen() {
        val all = extractorTracks + StreamResolver.hdrCandidateOf(hdr337())!!
        val ranked = StreamResolver.rankVideoCandidates(all, maxHeight = 2160, maxFps = 60, allowHdr = false, supports = everything)
        assertEquals(315, ranked.first().itag)
        assertFalse(ranked.any { it.hdr })
    }

    @Test
    fun withHdrAllowed337BeatsTheSdr315() {
        val all = extractorTracks + StreamResolver.hdrCandidateOf(hdr337())!!
        val ranked = StreamResolver.rankVideoCandidates(all, maxHeight = 2160, maxFps = 60, allowHdr = true, supports = everything)
        assertEquals(listOf(337, 315, 308, 303, 299), ranked.map { it.itag })
        assertEquals(StreamResolver.SOURCE_PLAYER_RESPONSE, ranked.first().source)
    }

    @Test
    fun hdrStillObeysTheCapsAndTheHardware() {
        val all = extractorTracks + StreamResolver.hdrCandidateOf(hdr337())!!
        // A 1080 cap: the 2160 HDR track is out like 315.
        assertEquals(303, StreamResolver.rankVideoCandidates(all, 1080, 60, true, everything).first().itag)
        // A decoder without VP9 Profile 2: the SDR track wins.
        val noProfile2: (VideoCandidate) -> Boolean = { !it.hdr }
        assertEquals(315, StreamResolver.rankVideoCandidates(all, 2160, 60, true, noProfile2).first().itag)
    }
}
