package com.vistaks.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class UpdateVerifierTest {
    private val bytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(size: Long = bytes.size.toLong(), sha256: String = sha, code: Long = 10) = UpdateManifest(
        versionName = "0.2.1", versionCode = code, apkUrl = "https://a.test/a.apk", sha256 = sha256,
        sizeBytes = size, publishedAt = "", sourceUrl = "", notes = "",
    )

    @Test
    fun copiesAndHashes() {
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Long>()
        val result = UpdateVerifier.copyHashed(ByteArrayInputStream(bytes), out, bytes.size.toLong()) { seen += it }
        assertEquals(bytes.size.toLong(), result.sizeBytes)
        assertEquals(sha, result.sha256)
        assertTrue(out.toByteArray().contentEquals(bytes))
        assertEquals(bytes.size.toLong(), seen.last())
        assertTrue("progress grows", seen.zipWithNext().all { (a, b) -> b > a })
        assertNull(UpdateVerifier.check(result, manifest()))
    }

    @Test
    fun stopsAtTheFirstByteTooMany() {
        val out = ByteArrayOutputStream()
        try {
            UpdateVerifier.copyHashed(ByteArrayInputStream(bytes), out, bytes.size - 1L)
            fail("an oversized download must stop")
        } catch (e: UpdateException) {
            assertEquals(UpdateFailure.SIZE, e.failure)
        }
        assertTrue("it never wrote past the limit", out.size() <= bytes.size - 1)
    }

    @Test
    fun sizeAndHashMismatches() {
        val result = DownloadResult(bytes.size.toLong(), sha)
        assertEquals(UpdateFailure.SIZE, UpdateVerifier.check(result, manifest(size = bytes.size + 1L)))
        assertEquals(UpdateFailure.CHECKSUM, UpdateVerifier.check(result, manifest(sha256 = "00".repeat(32))))
        val short = DownloadResult(bytes.size - 10L, sha)
        assertEquals(UpdateFailure.SIZE, UpdateVerifier.check(short, manifest()))
    }

    @Test
    fun signersMustBeTheSame() {
        val a = UpdateVerifier.certificateFingerprint(byteArrayOf(1, 2, 3))
        val b = UpdateVerifier.certificateFingerprint(byteArrayOf(4, 5, 6))
        assertEquals(64, a.length)
        assertTrue(UpdateVerifier.sameSigners(setOf(a), setOf(a)))
        assertFalse(UpdateVerifier.sameSigners(setOf(a), setOf(b)))
        assertFalse("an extra signer", UpdateVerifier.sameSigners(setOf(a), setOf(a, b)))
        assertFalse("nothing installed known", UpdateVerifier.sameSigners(emptySet(), emptySet()))
        assertFalse("an unsigned archive", UpdateVerifier.sameSigners(setOf(a), emptySet()))
    }

    @Test
    fun theArchiveIsOurPackageAtTheManifestsVersion() {
        val a = setOf(UpdateVerifier.certificateFingerprint(byteArrayOf(9)))
        val b = setOf(UpdateVerifier.certificateFingerprint(byteArrayOf(8)))
        val m = manifest(code = 42)
        assertNull(UpdateVerifier.checkArchive("com.vistaks.tv", 42, a, "com.vistaks.tv", m, a))
        assertEquals(UpdateFailure.PACKAGE, UpdateVerifier.checkArchive(null, -1, emptySet(), "com.vistaks.tv", m, a))
        assertEquals(UpdateFailure.PACKAGE, UpdateVerifier.checkArchive("com.other", 42, a, "com.vistaks.tv", m, a))
        assertEquals(UpdateFailure.PACKAGE, UpdateVerifier.checkArchive("com.vistaks.tv", 41, a, "com.vistaks.tv", m, a))
        assertEquals(UpdateFailure.SIGNATURE, UpdateVerifier.checkArchive("com.vistaks.tv", 42, b, "com.vistaks.tv", m, a))
    }
}
