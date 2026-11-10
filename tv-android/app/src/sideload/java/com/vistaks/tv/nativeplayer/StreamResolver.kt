package com.vistaks.tv.nativeplayer

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

/** What the page asked for (`nativeLoad`'s JSON, ADR-0100 §3), parsed on the bridge thread. */
internal data class LoadRequest(
    val videoId: String,
    val startSeconds: Double,
    val maxHeight: Int,
    val maxFps: Int,
    val hdr: Boolean,
    val autoplay: Boolean,
    val muted: Boolean,
    val rate: Float,
    /** S3: switch the display's refresh rate to the video's frame rate (the TV's local switch; off when absent). */
    val matchFrameRate: Boolean = false,
    /** S4: captions on (true) / off (false) from the load; null = keep what the last command said. */
    val captions: Boolean? = null,
) {
    companion object {
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        /** null when the JSON is not a usable request (the player is never touched then). */
        fun parse(json: String): LoadRequest? = try {
            val o = JSONObject(json)
            val id = o.optString("videoId").trim()
            if (!VIDEO_ID.matches(id)) {
                null
            } else {
                LoadRequest(
                    videoId = id,
                    startSeconds = o.optDouble("startSeconds", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0,
                    maxHeight = o.optInt("maxHeight", 2160).coerceIn(144, 2160),
                    maxFps = o.optInt("maxFps", 60).coerceIn(1, 60),
                    hdr = o.optBoolean("hdr", false),
                    autoplay = o.optBoolean("autoplay", true),
                    muted = o.optBoolean("muted", false),
                    rate = o.optDouble("rate", 1.0).toFloat().takeIf { it.isFinite() && it in 0.25f..2f } ?: 1f,
                    matchFrameRate = o.optBoolean("matchFrameRate", false),
                    captions = when (val c = o.opt("captions")) {
                        is Boolean -> c
                        is Number -> c.toDouble() != 0.0
                        else -> null
                    },
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** A chosen elementary stream: its URL and, when the extractor could build one, its DASH manifest. */
internal data class ResolvedTrack(
    val itag: Int,
    val codec: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val hdr: Boolean,
    val url: String,
    /** DASH MPD text from the extractor's manifest creator; null = progressive fallback (ADR §2). */
    val manifest: String?,
    /** Where the track came from: [StreamResolver.SOURCE_EXTRACTOR] or [StreamResolver.SOURCE_PLAYER_RESPONSE]. */
    val source: String = StreamResolver.SOURCE_EXTRACTOR,
)

/**
 * A video-only stream before the choice: the extractor's tracks and the HDR formats added back
 * from the player answer ([PlayerResponseCapture]) in one shape, so one rule ranks them all.
 * [payload] is the extractor's `VideoStream` or the captured [HdrFormat].
 */
internal data class VideoCandidate(
    val itag: Int,
    /** The stream's codec string (`vp09.02.51.10…`, `vp9`, `avc1.640033`…). */
    val codecString: String,
    val codec: Codec,
    val width: Int,
    val height: Int,
    /** Frames per second; 30 when the source names none. */
    val fps: Int,
    val bitrate: Int,
    val hdr: Boolean,
    val source: String,
    val payload: Any? = null,
) {
    /** The rung edge: the shorter side, or the height when the width is unknown. */
    val shortEdge: Int get() = if (width > 0 && height > 0) minOf(width, height) else height
}

/** One caption track the extractor listed (the `captions` event; rendered by S4's SubtitleView). */
internal data class CaptionTrack(
    val languageCode: String,
    val languageName: String,
    /** "asr" for YouTube's automatic captions, "" otherwise (the embed's tracklist uses the same). */
    val kind: String,
    /** The timedtext URL of the best format the extractor offered (WebVTT first). */
    val url: String = "",
    /** Media3 MIME type of [url]'s format (text/vtt, application/ttml+xml, application/x-subrip). */
    val mimeType: String = "",
)

internal data class Resolved(
    val videoId: String,
    val durationSeconds: Long,
    val video: ResolvedTrack,
    val audio: ResolvedTrack,
    val captions: List<CaptionTrack>,
    /** elapsedRealtime after which the URLs must not be used (the `expire` parameter less 10 min). */
    val usableUntil: Long,
)

/** The extraction failed; [code] goes to the page in `error{code, message, fallback}`. */
internal class ResolveException(val code: String, message: String) : Exception(message)

/** A newer nativeLoad (or a release) superseded this extraction: dropped without a word to the page. */
internal class StaleResolveException : Exception("superseded")

internal enum class Codec(val mime: String, val label: String) {
    VP9("video/x-vnd.on2.vp9", "vp9"),
    H264("video/avc", "h264"),
    AV1("video/av01", "av1"),
}

/**
 * NewPipeExtractor on a background thread (ADR-0100 §2): videoId -> one video-only and one audio
 * stream the device can hardware-decode, with DASH manifests from the extractor's creators.
 *
 * Track choice:
 *  - video: only codecs with a hardware decoder (MediaCodecList, not `c2.android.` / `OMX.google.`,
 *    `isHardwareAccelerated` from API 29) that supports the stream's size and frame rate; a
 *    software AV1 decoder therefore never gets a 4K (or any) AV1 stream. Within maxHeight / maxFps,
 *    the highest height wins, then the highest fps, then the codec order VP9 -> H.264 -> AV1, then
 *    HDR (only offered when allowed), then bitrate. HDR streams are dropped unless the request
 *    allows HDR and the display reports HDR10. The extractor (v0.27.0) lists no VP9.2 HDR stream
 *    (itag 330–337 are missing from its ItagItem table); those come from the player answer
 *    ([PlayerResponseCapture]) and are ranked by the same rule ([rankVideoCandidates]). A video
 *    whose HDR track failed ([markHdrFailed], or a 403 through [invalidate]) resolves without HDR
 *    for the rest of the session: the SDR tracks, not the iframe.
 *  - audio: the original track (not dubbed / descriptive); Opus first, AAC otherwise; highest
 *    bitrate.
 *
 * Every call blocks and must run off the main thread (checked). Results are cached per request
 * shape (LRU, [CACHE_SIZE]) until 10 minutes before the URLs' `expire`.
 */
internal class StreamResolver {

    private val cache = object : LinkedHashMap<String, Resolved>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Resolved>?): Boolean = size > CACHE_SIZE
    }

    /** Hardware decoders by codec, read once (MediaCodecList is costly). */
    private val hardwareDecoders: Map<Codec, List<MediaCodecInfo>> by lazy { findHardwareDecoders() }

    /** Videos whose player-answer HDR track failed in this session: resolved without HDR from now on. */
    private val hdrFailed = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > HDR_FAILED_SIZE
    }

    /** Videos whose last resolve chose an HDR track from the player answer (not the extractor). */
    private val lastChoseCapturedHdr = LinkedHashSet<String>()

    private val capture = PlayerResponseCapture.instance

    /**
     * The host's 403 path (one fresh resolve per load) calls this before resolving again. When the
     * refused track was an HDR one added from the player answer, the retry goes without HDR: an
     * HDR candidate that fails falls back to the SDR tracks, never to the iframe (ADR-0100 §2).
     */
    fun invalidate(videoId: String) {
        val wasCapturedHdr = synchronized(cache) {
            cache.keys.removeAll { it.startsWith("$videoId|") }
            lastChoseCapturedHdr.remove(videoId)
        }
        if (wasCapturedHdr) markHdrFailed(videoId)
    }

    /** No HDR track for [videoId] for the rest of this session (403 or a decoder refusing 10-bit). */
    fun markHdrFailed(videoId: String) {
        synchronized(cache) {
            hdrFailed[videoId] = true
            cache.keys.removeAll { it.startsWith("$videoId|") }
            lastChoseCapturedHdr.remove(videoId)
        }
        Log.w(TAG, "$videoId: HDR track failed; resolving without HDR for this session (hdrFailed)")
    }

    fun hdrFailed(videoId: String): Boolean = synchronized(cache) { hdrFailed.containsKey(videoId) }

    @Throws(ResolveException::class)
    fun resolve(request: LoadRequest, displayHdr10: Boolean, stale: () -> Boolean = { false }): Resolved {
        check(Looper.myLooper() != Looper.getMainLooper()) { "StreamResolver.resolve on the main thread" }
        val allowHdr = request.hdr && displayHdr10 && !hdrFailed(request.videoId)
        val key = "${request.videoId}|${request.maxHeight}|${request.maxFps}|$allowHdr"
        synchronized(cache) {
            val hit = cache[key]
            if (hit != null && SystemClock.elapsedRealtime() < hit.usableUntil) {
                Log.i(TAG, "${request.videoId}: cached itag ${hit.video.itag}+${hit.audio.itag}")
                noteChoice(hit)
                return hit
            }
            cache.remove(key)
        }
        val resolved = extract(request, allowHdr, stale)
        synchronized(cache) {
            cache[key] = resolved
            noteChoice(resolved)
        }
        return resolved
    }

    /** Remembers whether [result] plays a player-answer HDR track (read by [invalidate]); under the cache lock. */
    private fun noteChoice(result: Resolved) {
        if (result.video.source == SOURCE_PLAYER_RESPONSE) {
            lastChoseCapturedHdr.add(result.videoId)
            if (lastChoseCapturedHdr.size > HDR_FAILED_SIZE) lastChoseCapturedHdr.remove(lastChoseCapturedHdr.first())
        } else {
            lastChoseCapturedHdr.remove(result.videoId)
        }
    }

    private fun extract(request: LoadRequest, allowHdr: Boolean, stale: () -> Boolean): Resolved {
        ensureInit()
        if (stale()) throw StaleResolveException()
        val started = SystemClock.elapsedRealtime()
        // This extraction's own player answers fill the capture (an older entry may hold URLs
        // from another cpn or a refused URL).
        capture.forget(request.videoId)
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${request.videoId}")
        } catch (e: AgeRestrictedContentException) {
            throw ResolveException("age-restricted", e.message ?: "age-restricted")
        } catch (e: ContentNotAvailableException) {
            throw ResolveException("unavailable", "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: ReCaptchaException) {
            throw ResolveException("recaptcha", e.message ?: "reCaptcha")
        } catch (e: ExtractionException) {
            throw ResolveException("extraction", "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: IOException) {
            throw ResolveException("network", "${e.javaClass.simpleName}: ${e.message}")
        }
        Log.i(TAG, "${request.videoId}: extracted in ${SystemClock.elapsedRealtime() - started} ms, type=${info.streamType}, duration=${info.duration}s, ageLimit=${info.ageLimit}")

        if (info.streamType != StreamType.VIDEO_STREAM) {
            // LIVE_STREAM / POST_LIVE_STREAM / audio: the first version plays these in the iframe.
            throw ResolveException("live", "stream type ${info.streamType} is not played natively")
        }

        // The page moved on while YouTube answered: skip the manifests (the OTF creator fetches).
        if (stale()) throw StaleResolveException()
        logStreams(request.videoId, info)

        val captured = if (allowHdr) capture.hdrFormats(request.videoId) else null
        logCaptured(request.videoId, allowHdr, captured)
        val video = chooseVideo(info, request, allowHdr, captured)
            ?: throw ResolveException(
                "no-video-track",
                "no hardware-decodable video-only stream within ${request.maxHeight}p${request.maxFps}" +
                    " (made-for-kids videos get no separate tracks; hardware: ${hardwareSummary()})",
            )
        val audio = chooseAudio(info)
            ?: throw ResolveException("no-audio-track", "no Opus / AAC audio-only stream")
        Log.i(
            TAG,
            "${request.videoId}: chose video itag ${video.itag} ${video.codec} ${video.width}x${video.height}@${video.fps}" +
                " hdr=${video.hdr} ${video.bitrate / 1000} kbps ${if (video.manifest != null) "dash" else "progressive"} source=${video.source};" +
                " audio itag ${audio.itag} ${audio.codec} ${audio.bitrate / 1000} kbps ${if (audio.manifest != null) "dash" else "progressive"}",
        )
        return Resolved(
            videoId = request.videoId,
            durationSeconds = info.duration,
            video = video,
            audio = audio,
            captions = captionsOf(info),
            usableUntil = usableUntil(video.url),
        )
    }

    /**
     * The extractor's subtitle list, one entry per language and kind, in the extractor's order; of
     * each entry's formats the one Media3 parses best (WebVTT, then TTML, then SRT).
     */
    private fun captionsOf(info: StreamInfo): List<CaptionTrack> = try {
        val byTrack = LinkedHashMap<String, CaptionTrack>()
        for (s in info.subtitles) {
            val code = s.languageTag?.trim().orEmpty()
            val mime = captionMimeOf(s.format) ?: continue
            val url = s.content?.takeIf { s.isUrl && it.startsWith("https://") } ?: continue
            if (code.isEmpty()) continue
            val kind = if (s.isAutoGenerated) "asr" else ""
            val key = "$code|$kind"
            val track = CaptionTrack(code, s.displayLanguageName ?: code, kind, url, mime)
            val have = byTrack[key]
            if (have == null || CAPTION_MIMES.indexOf(mime) < CAPTION_MIMES.indexOf(have.mimeType)) byTrack[key] = track
        }
        Log.i(TAG, "${info.id}: ${byTrack.size} caption track(s): ${byTrack.values.joinToString { "${it.languageCode}${if (it.kind.isNotEmpty()) "/" + it.kind else ""}(${it.mimeType})" }}")
        byTrack.values.toList()
    } catch (e: Exception) {
        Log.w(TAG, "${info.id}: no caption list (${e.javaClass.simpleName}: ${e.message})")
        emptyList()
    }

    private fun logStreams(videoId: String, info: StreamInfo) {
        val videos = info.videoOnlyStreams
        Log.i(TAG, "$videoId: ${videos.size} video-only stream(s)")
        for (s in videos) {
            Log.i(TAG, "  video itag=${s.itag} ${s.width}x${s.height} fps=${s.fps} codec=${s.codec} bitrate=${s.bitrate} delivery=${s.deliveryMethod} url=${s.isUrl}")
        }
        val audios = info.audioStreams
        Log.i(TAG, "$videoId: ${audios.size} audio stream(s)")
        for (s in audios) {
            Log.i(TAG, "  audio itag=${s.itag} codec=${s.codec} format=${s.format} bitrate=${s.averageBitrate} track=${s.audioTrackType} delivery=${s.deliveryMethod} url=${s.isUrl}")
        }
        // Muxed (video+audio) streams are never used, but their presence tells a made-for-kids answer apart.
        Log.i(TAG, "$videoId: ${info.videoStreams.size} muxed stream(s): ${info.videoStreams.joinToString { "${it.itag}" }}")
    }

    /** What the player answer added (or why not): the line the device run reads for HDR. */
    private fun logCaptured(videoId: String, allowHdr: Boolean, captured: CapturedHdr?) {
        when {
            !allowHdr -> Log.i(TAG, "$videoId: HDR not allowed; player-response HDR formats not used")
            captured == null -> Log.i(TAG, "$videoId: player response lists no HDR itag (${PlayerResponseCapture.HDR_ITAGS})")
            else -> for (f in captured.formats) {
                Log.i(TAG, "  video itag=${f.itag} ${f.width}x${f.height} fps=${f.fps} codec=${f.codec} bitrate=${f.bitrate} source=$SOURCE_PLAYER_RESPONSE client=${captured.clientName} cipher=${f.url == null}")
            }
        }
    }

    private fun chooseVideo(info: StreamInfo, request: LoadRequest, allowHdr: Boolean, captured: CapturedHdr?): ResolvedTrack? {
        val fromExtractor = info.videoOnlyStreams.mapNotNull { s ->
            if (s.itagItem == null) return@mapNotNull null
            val codec = codecOf(s.codec) ?: return@mapNotNull null
            VideoCandidate(
                itag = s.itag,
                codecString = s.codec.orEmpty(),
                codec = codec,
                width = s.width,
                height = s.height,
                fps = if (s.fps > 0) s.fps else 30,
                bitrate = s.bitrate,
                hdr = isHdr(s.codec),
                source = SOURCE_EXTRACTOR,
                payload = s,
            )
        }
        val known = fromExtractor.mapTo(HashSet()) { it.itag }
        // An extractor that one day knows these itags wins: its own track is used, not ours.
        val fromPlayerResponse = captured?.formats.orEmpty()
            .filter { it.itag !in known }
            .mapNotNull { hdrCandidateOf(it) }
        val ranked = rankVideoCandidates(
            fromExtractor + fromPlayerResponse,
            request.maxHeight,
            request.maxFps,
            allowHdr,
        ) { c -> hardwareSupports(c.codec, c.width, c.height, c.fps, c.hdr) }
        for (c in ranked) {
            val track = when (val p = c.payload) {
                is VideoStream -> trackOf(p, info.duration, c.codec.mime, c.width, c.height, c.fps, c.bitrate, c.hdr)
                is HdrFormat -> captured?.let { hdrTrackOf(request.videoId, it, p, c, info.duration) }
                else -> null
            }
            if (track != null) return track
            Log.w(TAG, "${request.videoId}: itag ${c.itag} (source=${c.source}) has no playable URL/manifest; next candidate")
        }
        return null
    }

    /**
     * A player-answer HDR format as a track: URL as the extractor builds its own (signature
     * deobfuscated into `sp`, throttling parameter deobfuscated, `&cpn=`), ItagItem built through
     * its public constructor and setters, DASH manifest from the same progressive creator.
     */
    private fun hdrTrackOf(videoId: String, captured: CapturedHdr, f: HdrFormat, c: VideoCandidate, durationSeconds: Long): ResolvedTrack? {
        val url = try {
            hdrUrlOf(
                videoId,
                f,
                captured.cpn ?: YoutubeParsingHelper.generateContentPlaybackNonce(),
                captured.poToken,
                YoutubeJavaScriptPlayerManager::deobfuscateSignature,
                YoutubeJavaScriptPlayerManager::getUrlWithThrottlingParameterDeobfuscated,
            )
        } catch (e: Exception) {
            Log.w(TAG, "$videoId: HDR itag ${f.itag}: no URL (${e.javaClass.simpleName}: ${e.message})")
            return null
        }
        val itagItem = hdrItagItem(f)
        return trackOfUrl(url, itagItem, DeliveryMethod.PROGRESSIVE_HTTP, isUrl = true, durationSeconds, c.codec.mime, c.width, c.height, c.fps, c.bitrate, c.hdr, f.codec, f.itag, SOURCE_PLAYER_RESPONSE)
    }

    private fun chooseAudio(info: StreamInfo): ResolvedTrack? {
        fun isOpus(s: AudioStream) = s.format == MediaFormat.WEBMA_OPUS || s.format == MediaFormat.OPUS ||
            s.codec?.lowercase()?.startsWith("opus") == true
        fun isAac(s: AudioStream) = s.format == MediaFormat.M4A || s.codec?.lowercase()?.startsWith("mp4a") == true
        val usable = info.audioStreams.filter { it.itagItem != null && (isOpus(it) || isAac(it)) }
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { usable }
        val ranked = original.sortedWith(
            compareByDescending<AudioStream> { isOpus(it) }.thenByDescending { it.averageBitrate },
        )
        for (s in ranked) {
            val mime = if (isOpus(s)) "audio/webm" else "audio/mp4"
            val track = trackOf(s, info.duration, mime, 0, 0, 0, s.averageBitrate.coerceAtLeast(s.bitrate), false)
            if (track != null) return track
        }
        return null
    }

    /** The stream's URL and DASH manifest; null when the stream cannot be played at all. */
    private fun trackOf(
        stream: Stream,
        durationSeconds: Long,
        mimeType: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        hdr: Boolean,
    ): ResolvedTrack? {
        val itagItem = stream.itagItem ?: return null
        val content = stream.content ?: return null
        val codec = when (stream) {
            is VideoStream -> stream.codec
            is AudioStream -> stream.codec
            else -> null
        } ?: itagItem.codec ?: ""
        val itag = when (stream) {
            is VideoStream -> stream.itag
            is AudioStream -> stream.itag
            else -> itagItem.id
        }
        return trackOfUrl(content, itagItem, stream.deliveryMethod, stream.isUrl, durationSeconds, mimeType, width, height, fps, bitrate, hdr, codec, itag, SOURCE_EXTRACTOR)
    }

    /** [trackOf] for a URL and ItagItem of any source (the player-answer HDR formats have no `Stream`). */
    private fun trackOfUrl(
        content: String,
        itagItem: ItagItem,
        delivery: DeliveryMethod,
        isUrl: Boolean,
        durationSeconds: Long,
        mimeType: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        hdr: Boolean,
        codec: String,
        itag: Int,
        source: String,
    ): ResolvedTrack? {
        val manifest = try {
            when (delivery) {
                DeliveryMethod.PROGRESSIVE_HTTP ->
                    YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(content, itagItem, durationSeconds)
                // OTF ("on the fly") streams have no index; the OTF creator builds a segment template.
                DeliveryMethod.DASH ->
                    YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(content, itagItem, durationSeconds)
                else -> return null
            }
        } catch (e: CreationException) {
            Log.w(TAG, "itag ${itagItem.id}: no DASH manifest (${e.message}); progressive fallback")
            null
        }
        // Progressive fallback needs a plain URL (an OTF stream without a manifest cannot play).
        if (manifest == null && (delivery != DeliveryMethod.PROGRESSIVE_HTTP || !isUrl)) return null
        return ResolvedTrack(itag, codec, mimeType, width, height, fps, bitrate, hdr, content, manifest, source)
    }

    private fun hardwareSupports(codec: Codec, width: Int, height: Int, fps: Int, hdr: Boolean): Boolean {
        val decoders = hardwareDecoders[codec].orEmpty()
        return decoders.any { info ->
            val caps = try {
                info.getCapabilitiesForType(codec.mime)
            } catch (_: IllegalArgumentException) {
                return@any false
            }
            val video = caps.videoCapabilities ?: return@any false
            val sized = if (width > 0) {
                video.areSizeAndRateSupported(width, height, fps.toDouble()) ||
                    // Portrait streams: decoders often list landscape limits only.
                    video.areSizeAndRateSupported(height, width, fps.toDouble())
            } else {
                video.supportedHeights.contains(height)
            }
            sized && (!hdr || supportsHdrProfile(codec, caps))
        }
    }

    /** 10-bit HDR needs the decoder to list the profile (VP9: Profile 2; SHIELD's OMX.Nvidia.vp9.decode does). */
    private fun supportsHdrProfile(codec: Codec, caps: MediaCodecInfo.CodecCapabilities): Boolean {
        val wanted = when (codec) {
            Codec.VP9 -> setOf(
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
                MediaCodecInfo.CodecProfileLevel.VP9Profile2,
            )
            Codec.AV1 -> setOf(
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
            )
            Codec.H264 -> return false
        }
        // S4 device run (2026-11-10, NVIDIA SHIELD TV Pro (2019) "mdarcy", Tegra X1+): OMX.Nvidia.vp9.decode declares
        // VP9Profile0 only, and fed itag 337 (vp09.02, 10-bit) it answers OMX_ErrorFormatNotDetected
        // (0x80001020): the declaration is truthful, this SHIELD decodes no 10-bit VP9. The declared profiles
        // decide; a decoder that lies the other way is caught by the SDR retry in NativePlayerHost.
        return caps.profileLevels.any { it.profile in wanted }
    }

    private fun findHardwareDecoders(): Map<Codec, List<MediaCodecInfo>> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        val found = Codec.entries.associateWith { codec ->
            all.filter { info -> info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) } && isHardware(info) }
        }
        Log.i(TAG, "hardware video decoders: ${found.entries.joinToString { (c, list) -> "${c.label}=[${list.joinToString { it.name }}]" }}")
        // What each decoder declares for 10-bit HDR (S4 device run): the profile list decides supportsHdrProfile.
        for ((codec, list) in found) for (info in list) {
            val levels = try { info.getCapabilitiesForType(codec.mime).profileLevels.map { it.profile }.distinct().sorted() } catch (_: IllegalArgumentException) { emptyList() }
            Log.i(TAG, "  ${info.name} profiles=${levels}")
        }
        return found
    }

    private fun hardwareSummary(): String =
        hardwareDecoders.entries.joinToString { (c, list) -> "${c.label}:${list.size}" }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        val name = info.name.lowercase()
        if (name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.startsWith("c2.google.")) return false
        return if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly else true
    }

    companion object {
        const val TAG = "VistaksNative"

        /** [ResolvedTrack.source]: a stream NewPipeExtractor listed. */
        const val SOURCE_EXTRACTOR = "extractor"

        /** [ResolvedTrack.source]: a VP9.2 HDR format added back from the player answer ([PlayerResponseCapture]). */
        const val SOURCE_PLAYER_RESPONSE = "player-response"

        /** At most this many videos remembered as hdrFailed (session-long, recycle rule §7). */
        private const val HDR_FAILED_SIZE = 200

        private val CODEC_ORDER = listOf(Codec.VP9, Codec.H264, Codec.AV1)

        /**
         * The video choice rule (pure): within maxHeight (on the short edge: a YouTube rung names
         * it, a portrait 2160x3840 is hd2160) and maxFps (59.94 is reported as 60), HDR only when
         * allowed, only what [supports] (hardware decode) accepts; then the highest short edge, the
         * highest fps, the codec order VP9 -> H.264 -> AV1, HDR before SDR, the highest bitrate.
         * Best first: the caller takes the first one that yields a playable track.
         */
        fun rankVideoCandidates(
            candidates: List<VideoCandidate>,
            maxHeight: Int,
            maxFps: Int,
            allowHdr: Boolean,
            supports: (VideoCandidate) -> Boolean,
        ): List<VideoCandidate> = candidates
            .filter { c ->
                when {
                    c.shortEdge <= 0 || c.shortEdge > maxHeight -> false
                    c.fps > maxFps + 1 -> false
                    c.hdr && !allowHdr -> false
                    else -> supports(c)
                }
            }
            .sortedWith(
                compareByDescending<VideoCandidate> { it.shortEdge }
                    .thenByDescending { it.fps }
                    .thenBy { CODEC_ORDER.indexOf(it.codec) }
                    .thenByDescending { it.hdr }
                    .thenByDescending { it.bitrate },
            )

        /** A captured HDR format as a candidate; null when its codec is none we decode. */
        fun hdrCandidateOf(f: HdrFormat): VideoCandidate? {
            val codec = codecOf(f.codec) ?: return null
            return VideoCandidate(
                itag = f.itag,
                codecString = f.codec,
                codec = codec,
                width = f.width,
                height = f.height,
                fps = if (f.fps > 0) f.fps else 30,
                bitrate = f.bitrate,
                hdr = isHdr(f.codec),
                source = SOURCE_PLAYER_RESPONSE,
                payload = f,
            )
        }

        /**
         * The stream URL of a captured format, built the way NewPipeExtractor v0.27.0 builds its own
         * (`YoutubeStreamExtractor.buildAndAddItagInfoToList`): a `signatureCipher` becomes
         * `url&<sp>=<deobfuscated s>`; then the throttling (`n`) parameter is deobfuscated; then
         * `&cpn=` and, when the request had one, `&pot=`. The two deobfuscators are passed in
         * (the extractor's public `YoutubeJavaScriptPlayerManager` methods in the app).
         */
        fun hdrUrlOf(
            videoId: String,
            f: HdrFormat,
            cpn: String?,
            poToken: String?,
            deobfuscateSignature: (String, String) -> String,
            deobfuscateThrottling: (String, String) -> String,
        ): String {
            val plain = f.url ?: run {
                val cipher = PlayerResponseCapture.parseCipher(
                    f.signatureCipher ?: throw IllegalArgumentException("itag ${f.itag}: neither url nor signatureCipher"),
                )
                val base = cipher["url"] ?: throw IllegalArgumentException("itag ${f.itag}: signatureCipher without url")
                val signature = deobfuscateSignature(videoId, cipher["s"].orEmpty())
                "$base&${cipher["sp"] ?: "signature"}=$signature"
            }
            var url = deobfuscateThrottling(videoId, plain)
            if (!cpn.isNullOrEmpty()) url += "&cpn=$cpn"
            if (!poToken.isNullOrEmpty()) url += "&pot=$poToken"
            return url
        }

        /**
         * The ItagItem the extractor's table lacks, through its public constructor and setters, so
         * `YoutubeProgressiveDashManifestCreator` can build the manifest (init / index ranges,
         * codec, size, fps, bitrate, length).
         */
        fun hdrItagItem(f: HdrFormat): ItagItem {
            val fps = if (f.fps > 0) f.fps else 30
            val format = if (f.mimeType.startsWith("video/mp4")) MediaFormat.MPEG_4 else MediaFormat.WEBM
            val shortEdge = if (f.width > 0 && f.height > 0) minOf(f.width, f.height) else f.height
            // The extractor's labels read "2160p60"; the player response says "2160p60 HDR" (review).
            val label = f.qualityLabel.replace(Regex("\\s*HDR\\s*$", RegexOption.IGNORE_CASE), "").ifEmpty { "${shortEdge}p${if (fps > 30) fps.toString() else ""}" }
            val item = ItagItem(f.itag, ItagItem.ItagType.VIDEO_ONLY, format, label, fps)
            item.setBitrate(f.bitrate)
            item.setWidth(f.width)
            item.setHeight(f.height)
            item.setFps(fps)
            item.setInitStart(f.initStart)
            item.setInitEnd(f.initEnd)
            item.setIndexStart(f.indexStart)
            item.setIndexEnd(f.indexEnd)
            item.setCodec(f.codec)
            if (f.contentLength > 0) item.setContentLength(f.contentLength)
            if (f.approxDurationMs > 0) item.setApproxDurationMs(f.approxDurationMs)
            return item
        }

        /** Caption formats Media3 parses, best first (index = preference). */
        private val CAPTION_MIMES = listOf("text/vtt", "application/ttml+xml", "application/x-subrip")

        /** A caption file larger than this is not a caption file. */
        private const val CAPTION_MAX_BYTES = 4 * 1024 * 1024

        private fun captionMimeOf(format: MediaFormat?): String? = when (format) {
            MediaFormat.VTT -> "text/vtt"
            MediaFormat.TTML -> "application/ttml+xml"
            MediaFormat.SRT -> "application/x-subrip"
            else -> null
        }

        /**
         * The track captions show in: the page's language first (a captions command may name one),
         * then Traditional Chinese like the iframe's cc_lang_pref=zh-TW; an exact tag before the
         * same language under another region, a person's track before YouTube's automatic one;
         * else the first person-made track, else the first track. Pure (unit-checked by the contract).
         */
        fun pickCaption(tracks: List<CaptionTrack>, preferred: String?): CaptionTrack? {
            if (tracks.isEmpty()) return null
            fun norm(tag: String) = tag.trim().replace('_', '-').lowercase()
            val wants = (listOfNotNull(preferred?.takeIf { it.isNotBlank() }) + DEFAULT_CAPTION_LANGUAGES).map(::norm).distinct()
            for (want in wants) for (asr in listOf(false, true)) {
                tracks.firstOrNull { norm(it.languageCode) == want && (it.kind == "asr") == asr }?.let { return it }
            }
            for (want in wants) for (asr in listOf(false, true)) {
                val primary = want.substringBefore('-')
                tracks.firstOrNull { norm(it.languageCode).substringBefore('-') == primary && (it.kind == "asr") == asr }?.let { return it }
            }
            return tracks.firstOrNull { it.kind != "asr" } ?: tracks.first()
        }

        /** zh-TW first, as the iframe's cc_lang_pref (TV_CAPTION_LANGUAGE on the page). */
        private val DEFAULT_CAPTION_LANGUAGES = listOf("zh-TW", "zh-Hant", "zh-HK", "zh")

        /** Worker thread: the caption file's bytes, or null (a caption failure never stops the video). */
        fun fetchCaption(track: CaptionTrack): ByteArray? {
            check(Looper.myLooper() != Looper.getMainLooper()) { "fetchCaption on the main thread" }
            return try {
                val response = NativeDownloader.instance.get(track.url)
                val body = response.responseBody()
                if (response.responseCode() !in 200..299 || body.isNullOrEmpty()) {
                    Log.w(TAG, "caption ${track.languageCode}: HTTP ${response.responseCode()}, ${body?.length ?: 0} chars; none shown")
                    null
                } else {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    if (bytes.size > CAPTION_MAX_BYTES) {
                        Log.w(TAG, "caption ${track.languageCode}: ${bytes.size} bytes, over the cap; none shown")
                        null
                    } else {
                        Log.i(TAG, "caption ${track.languageCode}${if (track.kind.isNotEmpty()) "/" + track.kind else ""}: ${bytes.size} bytes (${track.mimeType})")
                        bytes
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "caption ${track.languageCode}: ${e.javaClass.simpleName}: ${e.message}; none shown")
                null
            }
        }

        /** ADR-0100 §7: at most 50 resolved videos. */
        private const val CACHE_SIZE = 50

        /** URLs are dropped 10 minutes before their `expire` (ADR-0100 §2). */
        private const val EXPIRY_MARGIN_MS = 10 * 60 * 1000L

        /** When a URL names no `expire`: assume YouTube's usual ~6 h, less the margin. */
        private const val DEFAULT_LIFETIME_MS = 5 * 60 * 60 * 1000L

        private const val MANIFEST_CACHE_SIZE = 100

        @Volatile private var initialized = false

        private fun ensureInit() {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                NewPipe.init(NativeDownloader.instance, Localization("en", "US"), ContentCountry("US"))
                // The creators keep every manifest they built; bound them for long sessions (§7).
                YoutubeProgressiveDashManifestCreator.getCache().setMaximumSize(MANIFEST_CACHE_SIZE)
                YoutubeOtfDashManifestCreator.getCache().setMaximumSize(MANIFEST_CACHE_SIZE)
                initialized = true
            }
        }

        fun codecOf(codec: String?): Codec? {
            val c = codec?.lowercase() ?: return null
            return when {
                c.startsWith("vp9") || c.startsWith("vp09") -> Codec.VP9
                c.startsWith("avc1") || c.startsWith("avc3") -> Codec.H264
                c.startsWith("av01") -> Codec.AV1
                else -> null
            }
        }

        /** VP9 profile 2 (`vp09.02…`, `vp9.2`) and 10-bit AV1 are YouTube's HDR streams. */
        fun isHdr(codec: String?): Boolean {
            val c = codec?.lowercase() ?: return false
            if (c == "vp9.2" || c.startsWith("vp09.02")) return true
            if (c.startsWith("av01.")) {
                val parts = c.split('.')
                // av01.<profile>.<level+tier>.<bitDepth>[.<mono>.<chroma>.<primaries>.<transfer>…]
                val transfer = parts.getOrNull(7)
                return if (transfer != null) transfer == "16" || transfer == "18" else parts.getOrNull(3) == "10"
            }
            return false
        }

        private fun usableUntil(url: String): Long {
            val now = SystemClock.elapsedRealtime()
            val expireSeconds = try {
                Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()
            } catch (_: Exception) {
                null
            }
            if (expireSeconds == null) return now + DEFAULT_LIFETIME_MS
            val remaining = expireSeconds * 1000 - System.currentTimeMillis() - EXPIRY_MARGIN_MS
            return now + remaining.coerceAtLeast(0)
        }
    }
}
