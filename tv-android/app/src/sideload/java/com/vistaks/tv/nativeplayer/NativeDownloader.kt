package com.vistaks.tv.nativeplayer

import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * NewPipeExtractor's HTTP layer on OkHttp (ADR-0100 §2), modelled on NewPipe's own DownloaderImpl.
 * Only the extractor uses it (page / player API requests); media segments go through Media3's
 * data sources ([YoutubeDataSource]). Every call blocks: callers are on the resolver's worker
 * thread, never on the main thread or a JS bridge thread.
 */
internal class NativeDownloader private constructor(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()
        val body = data?.toRequestBody() ?: if (method in METHODS_WITH_BODY) ByteArray(0).toRequestBody() else null

        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(url)
            .header("User-Agent", USER_AGENT)
        // The extractor's own headers win (some clients send their own User-Agent).
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (value in values) builder.addHeader(name, value)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha challenge requested (HTTP 429)", url)
            }
            val text = response.body?.string()
            if (response.isSuccessful) capturePlayerResponse(url, data, text)
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                text,
                response.request.url.toString(),
            )
        }
    }

    /**
     * A `/youtubei/v1/player` answer also goes to [PlayerResponseCapture]: the extractor drops the
     * VP9.2 HDR formats (itag 330–337) it has no ItagItem for, the resolver adds them back from here.
     * Never fails the request: the extractor gets the same answer either way.
     */
    private fun capturePlayerResponse(url: String, requestBody: ByteArray?, responseBody: String?) {
        if (!PlayerResponseCapture.isPlayerUrl(url)) return
        try {
            val kept = PlayerResponseCapture.instance.offer(url, requestBody, responseBody) ?: return
            Log.i(
                StreamResolver.TAG,
                "${kept.videoId}: player response (${kept.clientName.ifEmpty { "?" }}) lists HDR itag(s) " +
                    kept.formats.joinToString { "${it.itag} ${it.width}x${it.height}@${it.fps} ${it.codec}${if (it.url == null) " (cipher)" else ""}" },
            )
        } catch (e: Exception) {
            Log.w(StreamResolver.TAG, "player response capture failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        /** Desktop Firefox ESR, as NewPipe sends for the web client's requests. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH")

        val instance: NativeDownloader by lazy {
            NativeDownloader(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
