package com.vistaks.tv.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val manifestUrl = "https://downloads.example.test/tv/channel/sideload.json"
    private val sha = "ab".repeat(32)

    private fun body(
        schema: Any = 1,
        versionName: Any = "0.2.1",
        versionCode: Any = 450_000,
        url: Any = "https://downloads.example.test/tv/channel/vistaks-tv-0.2.1-sideload.apk",
        sha256: Any = sha,
        sizeBytes: Any = 12_345_678,
        extra: (JSONObject) -> Unit = {},
    ): String = JSONObject()
        .put("schema", schema)
        .put("versionName", versionName)
        .put("versionCode", versionCode)
        .put("apk", JSONObject().put("url", url).put("sha256", sha256).put("sizeBytes", sizeBytes))
        .put("publishedAt", "2026-11-10T12:00:00Z")
        .put("sourceUrl", "https://github.com/example/vistaks/releases/download/tv-0.2.1/vistaks-tv-src-0.2.1.tar.gz")
        .also(extra)
        .toString()

    @Test
    fun parsesTheSchemaOneManifest() {
        val manifest = UpdateManifest.parse(body(extra = { it.put("notes", "  修正  ") }), manifestUrl)
        assertNotNull(manifest)
        manifest!!
        assertEquals("0.2.1", manifest.versionName)
        assertEquals(450_000L, manifest.versionCode)
        assertEquals(sha, manifest.sha256)
        assertEquals(12_345_678L, manifest.sizeBytes)
        assertEquals("2026-11-10T12:00:00Z", manifest.publishedAt)
        assertTrue(manifest.sourceUrl.startsWith("https://github.com/"))
        assertEquals("修正", manifest.notes)
    }

    @Test
    fun upperCaseHashIsReadAsLowerCase() {
        assertEquals(sha, UpdateManifest.parse(body(sha256 = sha.uppercase()), manifestUrl)?.sha256)
    }

    @Test
    fun refusesAnotherSchema() {
        assertNull(UpdateManifest.parse(body(schema = 2), manifestUrl))
        assertNull(UpdateManifest.parse(body(schema = "1"), manifestUrl))
        assertNull(UpdateManifest.parse(JSONObject(body()).apply { remove("schema") }.toString(), manifestUrl))
    }

    @Test
    fun refusesBadFields() {
        assertNull("not JSON", UpdateManifest.parse("<html>", manifestUrl))
        assertNull("versionCode 0", UpdateManifest.parse(body(versionCode = 0), manifestUrl))
        assertNull("versionCode past Play's ceiling", UpdateManifest.parse(body(versionCode = 2_100_000_001L), manifestUrl))
        assertNull("versionCode not a number", UpdateManifest.parse(body(versionCode = "abc"), manifestUrl))
        assertNull("versionName with a space", UpdateManifest.parse(body(versionName = "0.2 beta"), manifestUrl))
        assertNull("sha256 short", UpdateManifest.parse(body(sha256 = "ab".repeat(31)), manifestUrl))
        assertNull("sha256 not hex", UpdateManifest.parse(body(sha256 = "zz".repeat(32)), manifestUrl))
        assertNull("size 0", UpdateManifest.parse(body(sizeBytes = 0), manifestUrl))
        assertNull("size past the ceiling", UpdateManifest.parse(body(sizeBytes = UpdateManifest.MAX_APK_BYTES + 1), manifestUrl))
        assertNull("too large", UpdateManifest.parse(body(extra = { it.put("pad", "x".repeat(UpdateManifest.MAX_MANIFEST_BYTES)) }), manifestUrl))
    }

    @Test
    fun theApkMustBeHttpsOnTheManifestsHost() {
        assertNull("http", UpdateManifest.parse(body(url = "http://downloads.example.test/tv/channel/a.apk"), manifestUrl))
        assertNull("another host", UpdateManifest.parse(body(url = "https://evil.example.test/tv/channel/a.apk"), manifestUrl))
        assertNull("a look-alike host", UpdateManifest.parse(body(url = "https://downloads.example.test.evil.test/a.apk"), manifestUrl))
        assertNull("user info", UpdateManifest.parse(body(url = "https://x@downloads.example.test/a.apk"), manifestUrl))
        assertNull("another port", UpdateManifest.parse(body(url = "https://downloads.example.test:8443/a.apk"), manifestUrl))
        assertNull("not an .apk", UpdateManifest.parse(body(url = "https://downloads.example.test/tv/channel/a.zip"), manifestUrl))
        assertNotNull("host case does not matter", UpdateManifest.parse(body(url = "https://DOWNLOADS.example.test/tv/channel/a.apk"), manifestUrl))
    }

    @Test
    fun aSourceUrlThatIsNotHttpsIsDropped() {
        val manifest = UpdateManifest.parse(body(extra = { it.put("sourceUrl", "javascript:alert(1)") }), manifestUrl)
        assertEquals("", manifest?.sourceUrl)
    }

    @Test
    fun httpsChecks() {
        assertTrue(UpdateManifest.isHttps("https://a.test/x"))
        assertFalse(UpdateManifest.isHttps("ftp://a.test/x"))
        assertFalse(UpdateManifest.isHttps("not a url"))
        assertFalse(UpdateManifest.sameHttpsHost("https://a.test/x.apk", "http://a.test/m.json"))
    }
}
