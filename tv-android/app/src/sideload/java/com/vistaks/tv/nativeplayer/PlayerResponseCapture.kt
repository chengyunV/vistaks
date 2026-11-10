package com.vistaks.tv.nativeplayer

import java.net.URLDecoder

/**
 * One VP9.2 HDR adaptive format from a `/youtubei/v1/player` answer (ADR-0100 §2, "HDR 軌的來源").
 * Field for field what NewPipeExtractor reads for its own adaptive formats; [url] is set when the
 * client answered a plain URL, [signatureCipher] when the URL still needs its signature.
 */
internal data class HdrFormat(
    val itag: Int,
    val url: String?,
    val signatureCipher: String?,
    /** e.g. `video/webm; codecs="vp09.02.51.10.01.09.16.09.00"`. */
    val mimeType: String,
    /** The `codecs` part of [mimeType] (`vp09.02.51.10…`); "" when the mime type names none. */
    val codec: String,
    val bitrate: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val initStart: Int,
    val initEnd: Int,
    val indexStart: Int,
    val indexEnd: Int,
    val contentLength: Long,
    val approxDurationMs: Long,
    val qualityLabel: String,
)

/** The HDR formats one client's player answer listed for [videoId], with what its URLs need appended. */
internal data class CapturedHdr(
    val videoId: String,
    /** `context.client.clientName` of the request (log only). */
    val clientName: String,
    /** The request's content playback nonce: the extractor appends `&cpn=` to every stream URL. */
    val cpn: String?,
    /** `serviceIntegrityDimensions.poToken` when the request carried one (appended as `&pot=`). */
    val poToken: String?,
    val formats: List<HdrFormat>,
    /** [PlayerResponseCapture]'s clock (monotonic ms) when it was kept. */
    val capturedAt: Long,
)

/**
 * NewPipeExtractor v0.27.0 drops YouTube's VP9.2 HDR formats (itag 330–337): its `ItagItem` table
 * does not know them, so `StreamInfo.getVideoOnlyStreams()` never lists an HDR track (S3/S4 device
 * run, 2026-11-10, wcPo6Soc_CU). The player answer itself still has them: [NativeDownloader] hands
 * every `/youtubei/v1/player` exchange to [offer], which keeps only the HDR adaptive formats per
 * video, and [StreamResolver] turns them into candidates next to the extractor's tracks.
 *
 * Pure (its own [MiniJson], no Android types): unit-checked on the JVM. Thread-safe; bounded to
 * [maxVideos] videos and [ttlMs] per entry (recycle rule, ADR-0100 §7).
 */
internal class PlayerResponseCapture(
    private val maxVideos: Int = MAX_VIDEOS,
    private val ttlMs: Long = TTL_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val entries = object : LinkedHashMap<String, CapturedHdr>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CapturedHdr>?): Boolean = size > maxVideos
    }

    /**
     * One player exchange: [url] the request URL, [requestBody] what was posted, [responseBody] the
     * answer. Keeps the answer's HDR formats unless this video already has a live entry with some
     * (several clients may answer during one extraction: the first with HDR formats wins).
     * Returns what was kept, or null when nothing was (not a player URL, no HDR formats, unparsable).
     */
    fun offer(url: String, requestBody: ByteArray?, responseBody: String?): CapturedHdr? {
        if (!isPlayerUrl(url) || responseBody.isNullOrEmpty()) return null
        val captured = parse(requestBody, responseBody, clock()) ?: return null
        // TV-S4H review: only the clients the extractor streams with. The web metadata answer
        // (fetched after the stream client, mostly signatureCipher URLs, another cpn, no poToken)
        // would be kept when the stream client listed no HDR and then be refused with a 403.
        if (isMetadataClient(captured.clientName)) return null
        synchronized(entries) {
            val have = liveEntry(captured.videoId)
            if (have != null) return null
            entries[captured.videoId] = captured
        }
        return captured
    }

    /** The HDR formats kept for [videoId], or null (none, or older than [ttlMs]). */
    fun hdrFormats(videoId: String): CapturedHdr? = synchronized(entries) { liveEntry(videoId) }

    /** Drops [videoId]'s entry: a new extraction starts from its own answers. */
    fun forget(videoId: String) {
        synchronized(entries) { entries.remove(videoId) }
    }

    fun size(): Int = synchronized(entries) {
        purgeExpired()
        entries.size
    }

    private fun liveEntry(videoId: String): CapturedHdr? {
        val have = entries[videoId] ?: return null
        if (clock() - have.capturedAt > ttlMs) {
            entries.remove(videoId)
            return null
        }
        return have
    }

    private fun purgeExpired() {
        val now = clock()
        entries.values.removeAll { now - it.capturedAt > ttlMs }
    }

    companion object {
        /** YouTube's VP9.2 (profile 2, 10-bit, HDR) adaptive formats: 144p … 2160p60. */
        val HDR_ITAGS = 330..337

        const val MAX_VIDEOS = 8
        const val TTL_MS = 10 * 60 * 1000L

        /** The one capture the downloader feeds and the resolver reads. */
        val instance: PlayerResponseCapture by lazy { PlayerResponseCapture() }

        /** WEB, WEB_EMBEDDED_PLAYER, MWEB, WEB_REMIX, WEB_CREATOR…: metadata answers, never the stream client. */

        internal fun isMetadataClient(clientName: String): Boolean = clientName.uppercase().let { it == "WEB" || it.startsWith("WEB_") || it == "MWEB" }


        fun isPlayerUrl(url: String): Boolean = url.contains("/youtubei/v1/player")

        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val CODECS = Regex("codecs=\"([^\"]+)\"")

        /** The answer's HDR formats, or null when it has none (or is not JSON). */
        fun parse(requestBody: ByteArray?, responseBody: String, now: Long = 0L): CapturedHdr? {
            // Cheap gate before parsing a few hundred KB: no HDR itag named, nothing to keep.
            if (HDR_ITAGS.none { responseBody.contains("\"itag\":$it") || responseBody.contains("\"itag\": $it") }) return null
            val response = MiniJson.parseObject(responseBody) ?: return null
            val request = requestBody?.takeIf { it.isNotEmpty() }?.let { MiniJson.parseObject(String(it, Charsets.UTF_8)) }
            val videoId = strOf(objOf(response, "videoDetails"), "videoId")
                ?: request?.let { strOf(it, "videoId") }
                ?: return null
            if (!VIDEO_ID.matches(videoId)) return null
            val adaptive = objOf(response, "streamingData")?.get("adaptiveFormats") as? List<*> ?: return null
            val formats = adaptive.mapNotNull { f -> asObj(f)?.let(::formatOf) }
            if (formats.isEmpty()) return null
            return CapturedHdr(
                videoId = videoId,
                clientName = strOf(objOf(objOf(request, "context"), "client"), "clientName").orEmpty(),
                cpn = strOf(request, "cpn")?.takeIf { it.isNotBlank() },
                poToken = strOf(objOf(request, "serviceIntegrityDimensions"), "poToken")?.takeIf { it.isNotBlank() },
                formats = formats,
                capturedAt = now,
            )
        }

        /** One adaptive format when it is an HDR itag with a URL or a cipher; null otherwise. */
        private fun formatOf(o: Map<String, Any?>): HdrFormat? {
            val itag = intOf(o, "itag", -1)
            if (itag !in HDR_ITAGS) return null
            val url = strOf(o, "url")?.takeIf { it.isNotBlank() }
            val cipher = (strOf(o, "signatureCipher") ?: strOf(o, "cipher"))?.takeIf { it.isNotBlank() }
            if (url == null && cipher == null) return null
            val mimeType = strOf(o, "mimeType").orEmpty()
            val initRange = objOf(o, "initRange")
            val indexRange = objOf(o, "indexRange")
            return HdrFormat(
                itag = itag,
                url = url,
                signatureCipher = if (url == null) cipher else null,
                mimeType = mimeType,
                codec = codecOf(mimeType),
                bitrate = intOf(o, "bitrate", 0),
                width = intOf(o, "width", 0),
                height = intOf(o, "height", 0),
                fps = intOf(o, "fps", 0),
                initStart = intOf(initRange, "start", -1),
                initEnd = intOf(initRange, "end", -1),
                indexStart = intOf(indexRange, "start", -1),
                indexEnd = intOf(indexRange, "end", -1),
                contentLength = longOf(o, "contentLength", -1L),
                approxDurationMs = longOf(o, "approxDurationMs", -1L),
                qualityLabel = strOf(o, "qualityLabel").orEmpty(),
            )
        }

        /** `video/webm; codecs="vp09.02.51.10.01.09.16.09.00"` -> `vp09.02.51.10.01.09.16.09.00`. */
        fun codecOf(mimeType: String): String = CODECS.find(mimeType)?.groupValues?.get(1)?.trim().orEmpty()

        /** `s=…&sp=sig&url=…` (form-encoded), as the extractor's `Parser.compatParseMap` reads it. */
        fun parseCipher(cipher: String): Map<String, String> =
            cipher.split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) {
                    null
                } else {
                    val key = part.substring(0, eq)
                    val value = try {
                        URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                    } catch (_: Exception) {
                        return@mapNotNull null
                    }
                    key to value
                }
            }.toMap()

        @Suppress("UNCHECKED_CAST")
        private fun asObj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

        private fun objOf(o: Map<String, Any?>?, key: String): Map<String, Any?>? = asObj(o?.get(key))

        private fun strOf(o: Map<String, Any?>?, key: String): String? = o?.get(key) as? String

        // YouTube answers numbers both as JSON numbers and as strings ("contentLength": "123").
        private fun intOf(o: Map<String, Any?>?, key: String, fallback: Int): Int =
            when (val v = o?.get(key)) {
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull() ?: fallback
                else -> fallback
            }

        private fun longOf(o: Map<String, Any?>?, key: String, fallback: Long): Long =
            when (val v = o?.get(key)) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull() ?: fallback
                else -> fallback
            }
    }
}

/**
 * A small strict JSON reader (objects -> Map, arrays -> List, numbers -> Long or Double), so the
 * capture needs neither Android's org.json (a stub in JVM tests) nor the extractor's nanojson (a
 * runtime-only dependency of the extractor, not on our compile classpath). Null on any syntax error.
 */
internal object MiniJson {
    fun parseObject(text: String): Map<String, Any?>? = try {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipSpace()
        @Suppress("UNCHECKED_CAST")
        if (reader.atEnd()) value as? Map<String, Any?> else null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(depth: Int = 0): Any? {
            require(depth < 200) { "too deep" }
            skipSpace()
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipSpace()
            if (s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                require(s[i] == '"') { "key expected at $i" }
                val key = str()
                skipSpace()
                require(s[i] == ':') { "':' expected at $i" }
                i++
                out[key] = value(depth + 1)
                skipSpace()
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> throw IllegalArgumentException("',' or '}' expected at $i")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipSpace()
            if (s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out += value(depth + 1)
                skipSpace()
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> throw IllegalArgumentException("',' or ']' expected at $i")
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape at $i")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw IllegalArgumentException("bad number '$text'")
        }
    }
}
