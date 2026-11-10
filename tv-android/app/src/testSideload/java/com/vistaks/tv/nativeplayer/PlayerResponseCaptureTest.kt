package com.vistaks.tv.nativeplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The player answer's VP9.2 HDR formats (itag 330–337), which NewPipeExtractor v0.27.0 drops
 * (2026-11-10 SHIELD run, wcPo6Soc_CU): what [PlayerResponseCapture] keeps, per video, for how long.
 */
class PlayerResponseCaptureTest {

    private val playerUrl = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false&t=abc&id=wcPo6Soc_CU"

    private val request = """
        {"context":{"client":{"clientName":"VISIONOS","clientVersion":"0.1"}},
         "videoId":"wcPo6Soc_CU","cpn":"AbCdEfGhIjKlMnOp","contentCheckOk":true}
    """.trimIndent().toByteArray()

    private fun format(itag: Int, codecs: String, width: Int, height: Int, fps: Int, extra: String) = """
        {"itag":$itag,$extra,"mimeType":"video/webm; codecs=\"$codecs\"","bitrate":${itag * 1000},
         "width":$width,"height":$height,"fps":$fps,"qualityLabel":"${height}p$fps HDR",
         "initRange":{"start":"0","end":"219"},"indexRange":{"start":"220","end":"1500"},
         "contentLength":"123456789","approxDurationMs":"215000"}
    """.trimIndent()

    private fun response(videoId: String = "wcPo6Soc_CU", details: Boolean = true) = """
        {${if (details) "\"videoDetails\":{\"videoId\":\"$videoId\"}," else ""}
         "streamingData":{"adaptiveFormats":[
           ${format(315, "vp9", 3840, 2160, 60, "\"url\":\"https://rr1.googlevideo.com/videoplayback?itag=315\"")},
           ${format(337, "vp09.02.51.10.01.09.16.09.00", 3840, 2160, 60, "\"url\":\"https://rr1.googlevideo.com/videoplayback?itag=337&n=abc\"")},
           ${format(336, "vp09.02.50.10.01.09.16.09.00", 2560, 1440, 60, "\"signatureCipher\":\"s=SIGNED%3D%3D&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fitag%3D336\"")},
           ${format(330, "vp09.02.10.10.01.09.16.09.00", 256, 144, 60, "\"other\":1")},
           {"itag":251,"url":"https://rr1.googlevideo.com/videoplayback?itag=251","mimeType":"audio/webm; codecs=\"opus\"","bitrate":140000}
         ]}}
    """.trimIndent()

    @Test
    fun keepsOnlyHdrItagsWithAUrlOrACipher() {
        val kept = PlayerResponseCapture.parse(request, response(), now = 0)
        assertNotNull(kept)
        kept!!
        assertEquals("wcPo6Soc_CU", kept.videoId)
        // 315 (SDR, the extractor lists it), 251 (audio) ignored; 330 has neither url nor cipher.
        assertEquals(listOf(337, 336), kept.formats.map { it.itag })
        val f337 = kept.formats[0]
        assertEquals("vp09.02.51.10.01.09.16.09.00", f337.codec)
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=337&n=abc", f337.url)
        assertNull(f337.signatureCipher)
        assertEquals(3840, f337.width)
        assertEquals(2160, f337.height)
        assertEquals(60, f337.fps)
        assertEquals(337000, f337.bitrate)
        assertEquals(0, f337.initStart)
        assertEquals(219, f337.initEnd)
        assertEquals(220, f337.indexStart)
        assertEquals(1500, f337.indexEnd)
        assertEquals(123456789L, f337.contentLength)
        assertEquals(215000L, f337.approxDurationMs)
        assertEquals("2160p60 HDR", f337.qualityLabel)
    }

    @Test
    fun keepsTheCipherWhenThereIsNoUrl() {
        val f336 = PlayerResponseCapture.parse(request, response(), now = 0)!!.formats.single { it.itag == 336 }
        assertNull(f336.url)
        assertEquals("s=SIGNED%3D%3D&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fitag%3D336", f336.signatureCipher)
        val cipher = PlayerResponseCapture.parseCipher(f336.signatureCipher!!)
        assertEquals("SIGNED==", cipher["s"])
        assertEquals("sig", cipher["sp"])
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=336", cipher["url"])
    }

    @Test
    fun readsTheRequestsClientCpnAndPoToken() {
        val kept = PlayerResponseCapture.parse(request, response(), now = 0)!!
        assertEquals("VISIONOS", kept.clientName)
        assertEquals("AbCdEfGhIjKlMnOp", kept.cpn)
        assertNull(kept.poToken)
        val withPot = """{"videoId":"wcPo6Soc_CU","cpn":"x","serviceIntegrityDimensions":{"poToken":"PoT"}}""".toByteArray()
        assertEquals("PoT", PlayerResponseCapture.parse(withPot, response(), now = 0)!!.poToken)
    }

    @Test
    fun videoIdFromTheRequestWhenTheAnswerHasNoDetails() {
        val kept = PlayerResponseCapture.parse(request, response(details = false), now = 0)
        assertEquals("wcPo6Soc_CU", kept?.videoId)
    }

    @Test
    fun answersWithoutHdrFormatsOrNotJsonAreIgnored() {
        val sdrOnly = """{"videoDetails":{"videoId":"wcPo6Soc_CU"},"streamingData":{"adaptiveFormats":[${format(315, "vp9", 3840, 2160, 60, "\"url\":\"u\"")}]}}"""
        assertNull(PlayerResponseCapture.parse(request, sdrOnly))
        assertNull(PlayerResponseCapture.parse(request, "not json"))
        // The web client's metadata request ($fields filter) has no streamingData at all.
        assertNull(PlayerResponseCapture.parse(request, """{"videoDetails":{"videoId":"wcPo6Soc_CU"}}"""))
    }

    @Test
    fun offerOnlyTakesPlayerUrlsAndTheFirstAnswerWithHdr() {
        val capture = PlayerResponseCapture(clock = { 0L })
        assertNull(capture.offer("https://www.youtube.com/youtubei/v1/next", request, response()))
        assertNull(capture.hdrFormats("wcPo6Soc_CU"))

        val first = capture.offer(playerUrl, request, response())
        assertNotNull(first)
        val otherClient = """{"context":{"client":{"clientName":"WEB"}},"videoId":"wcPo6Soc_CU","cpn":"second"}""".toByteArray()
        assertNull(capture.offer(playerUrl, otherClient, response()))
        assertEquals("AbCdEfGhIjKlMnOp", capture.hdrFormats("wcPo6Soc_CU")?.cpn)

        // A new extraction forgets the old answers first. A web metadata client's answer is never
        // kept (its URLs belong to another cpn and would 403); the stream client's is.
        capture.forget("wcPo6Soc_CU")
        assertNull(capture.offer(playerUrl, otherClient, response()))
        assertNull(capture.hdrFormats("wcPo6Soc_CU"))
        val iosClient = """{"context":{"client":{"clientName":"IOS"}},"videoId":"wcPo6Soc_CU","cpn":"second"}""".toByteArray()
        assertNotNull(capture.offer(playerUrl, iosClient, response()))
        assertEquals("second", capture.hdrFormats("wcPo6Soc_CU")?.cpn)
        assertTrue(PlayerResponseCapture.isMetadataClient("WEB_EMBEDDED_PLAYER"))
        assertFalse(PlayerResponseCapture.isMetadataClient("VISIONOS"))
        assertFalse(PlayerResponseCapture.isMetadataClient(""))
    }

    @Test
    fun bucketsByVideoIdAndExpires() {
        var now = 0L
        val capture = PlayerResponseCapture(maxVideos = 8, ttlMs = 10 * 60 * 1000L, clock = { now })
        val ids = (0 until 9).map { "video%04d__".format(it).take(11) }
        for (id in ids) {
            capture.offer(playerUrl, """{"videoId":"$id","cpn":"c$id"}""".toByteArray(), response(videoId = id))
        }
        // At most 8 videos: the oldest went.
        assertEquals(8, capture.size())
        assertNull(capture.hdrFormats(ids[0]))
        assertEquals("c${ids[8]}", capture.hdrFormats(ids[8])?.cpn)
        assertEquals(ids[3], capture.hdrFormats(ids[3])?.videoId)

        // 10 minutes later, nothing is served.
        now += 10 * 60 * 1000L + 1
        assertNull(capture.hdrFormats(ids[8]))
        assertEquals(0, capture.size())
    }
}
