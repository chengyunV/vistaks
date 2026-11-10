package com.vistaks.tv.nativeplayer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Media3 data source for googlevideo `/videoplayback` URLs, shaped the way NewPipe's own
 * YoutubeHttpDataSource asks for them (ADR-0100 §2): a POST with the two-byte body YouTube's
 * clients send, a request number `rn`, the byte range as a `range` query parameter instead of a
 * Range header ([rangeParameter], DASH only: progressive sources need the header), the visionOS
 * User-Agent for URLs the extractor got from that client, web Origin / Referer for web URLs.
 * Anything else passes through unchanged. Runs on Media3's loader threads.
 */
@OptIn(UnstableApi::class)
internal class YoutubeDataSource(
    private val upstream: HttpDataSource,
    private val rangeParameter: Boolean,
) : DataSource {

    private var requestNumber = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.path?.startsWith("/videoplayback") != true) return upstream.open(dataSpec)

        var url = uri.toString()
        if (!url.contains("&rn=")) {
            url += "&rn=$requestNumber"
            requestNumber++
        }

        val headers = HashMap(dataSpec.httpRequestHeaders)
        headers["User-Agent"] = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            NativeDownloader.USER_AGENT
        }
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            headers["Origin"] = YOUTUBE
            headers["Referer"] = YOUTUBE
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Site"] = "cross-site"
        }

        val builder = dataSpec.buildUpon()
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(POST_BODY)
            .setHttpRequestHeaders(headers)
        if (rangeParameter) {
            val position = dataSpec.position
            val length = dataSpec.length
            if (position != 0L || length != C.LENGTH_UNSET.toLong()) {
                url += "&range=$position-" + if (length != C.LENGTH_UNSET.toLong()) "${position + length - 1}" else ""
            }
            // The range is in the URL: the server answers with exactly those bytes, from 0.
            builder.setUriPositionOffset(0).setPosition(0).setLength(C.LENGTH_UNSET.toLong())
        }
        builder.setUri(Uri.parse(url))
        return upstream.open(builder.build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }

    class Factory(private val rangeParameter: Boolean) : DataSource.Factory {
        private val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)

        override fun createDataSource(): DataSource = YoutubeDataSource(http.createDataSource(), rangeParameter)
    }

    private companion object {
        const val YOUTUBE = "https://www.youtube.com"

        /** The body NewPipe's YoutubeHttpDataSource posts to /videoplayback. */
        val POST_BODY = byteArrayOf(0x78, 0)
    }
}
