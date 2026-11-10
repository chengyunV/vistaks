package com.vistaks.tv.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMachineTest {
    private fun manifest(code: Long = 100, size: Long = 1000) = UpdateManifest(
        versionName = "0.2.$code", versionCode = code, apkUrl = "https://a.test/a.apk", sha256 = "ab".repeat(32),
        sizeBytes = size, publishedAt = "", sourceUrl = "", notes = "n",
    )

    @Test
    fun onlyAHigherVersionCodeIsAnUpdate() {
        assertTrue(UpdatePolicy.isNewer(manifest(101), 100))
        assertFalse(UpdatePolicy.isNewer(manifest(100), 100))
        assertFalse(UpdatePolicy.isNewer(manifest(99), 100))
        assertFalse(UpdatePolicy.isNewer(null, 100))
        assertEquals(UpdatePhase.AVAILABLE, UpdateMachine.onManifest(UpdateState(), manifest(101), 100).phase)
        assertEquals(UpdatePhase.NONE, UpdateMachine.onManifest(UpdateState(), manifest(100), 100).phase)
        assertEquals(UpdatePhase.NONE, UpdateMachine.onManifest(UpdateState(), null, 100).phase)
    }

    @Test
    fun checkTiming() {
        val hour = 60 * 60 * 1000L
        assertTrue("never checked", UpdatePolicy.checkDue(0, 10 * hour))
        assertFalse("an hour ago", UpdatePolicy.checkDue(9 * hour, 10 * hour))
        assertFalse("five hours ago", UpdatePolicy.checkDue(5 * hour, 10 * hour))
        assertTrue("six hours ago", UpdatePolicy.checkDue(4 * hour, 10 * hour))
        assertTrue("the clock went back", UpdatePolicy.checkDue(20 * hour, 10 * hour))
        assertTrue(UpdatePolicy.FIRST_CHECK_DELAY_MS >= 30_000)
    }

    @Test
    fun spaceAndThrottle() {
        assertTrue(UpdatePolicy.hasSpace(100L * 1024 * 1024, 50L * 1024 * 1024))
        assertFalse(UpdatePolicy.hasSpace(60L * 1024 * 1024, 50L * 1024 * 1024))
        val rate = UpdatePolicy.PLAYING_BYTES_PER_SECOND
        assertEquals("on pace", 0L, UpdatePolicy.throttleDelayMs(rate, 0, 1000, rate))
        assertEquals("ahead by half a second", 500L, UpdatePolicy.throttleDelayMs(rate, 0, 500, rate))
        assertEquals("never more than a second at once", 1000L, UpdatePolicy.throttleDelayMs(10 * rate, 0, 0, rate))
        assertEquals("no ceiling", 0L, UpdatePolicy.throttleDelayMs(10 * rate, 0, 0, 0))
    }

    @Test
    fun theWholeWay() {
        var s = UpdateMachine.onManifest(UpdateState(), manifest(101), 100)
        s = UpdateMachine.onStart(s)
        assertEquals(UpdatePhase.DOWNLOADING, s.phase)
        s = UpdateMachine.onProgress(s, 400, throttled = true)
        assertEquals(400L, s.downloadedBytes)
        assertTrue(s.throttled)
        assertEquals(0.4, JSONObject(UpdateMachine.toJson(s, "0.2.0")).getDouble("progress"), 1e-9)
        s = UpdateMachine.onVerified(s, playing = false)
        assertEquals(UpdatePhase.INSTALLING, s.phase)
        assertFalse(s.throttled)
    }

    @Test
    fun verifiedWhilePlayingWaitsForTheViewer() {
        var s = UpdateMachine.onStart(UpdateMachine.onManifest(UpdateState(), manifest(101), 100))
        s = UpdateMachine.onVerified(s, playing = true)
        assertEquals(UpdatePhase.READY, s.phase)
        assertTrue(s.waitingForPlayback)
        s = UpdateMachine.onStart(s)
        assertEquals("安裝 on READY installs", UpdatePhase.INSTALLING, s.phase)
        assertFalse(s.waitingForPlayback)
    }

    @Test
    fun failuresAreStatesAndCanBeRetried() {
        val downloading = UpdateMachine.onStart(UpdateMachine.onManifest(UpdateState(), manifest(101), 100))
        for (failure in listOf(UpdateFailure.CHECKSUM, UpdateFailure.SIZE, UpdateFailure.SIGNATURE, UpdateFailure.SPACE, UpdateFailure.NETWORK)) {
            val failed = UpdateMachine.onFailure(downloading, failure)
            assertEquals(UpdatePhase.FAILED, failed.phase)
            assertEquals(failure.wire, failed.reason)
            assertEquals(0L, failed.downloadedBytes)
            val again = UpdateMachine.onStart(failed)
            assertEquals("更新 again after $failure", UpdatePhase.DOWNLOADING, again.phase)
            assertEquals("", again.reason)
        }
    }

    @Test
    fun cancelledIsNotAnErrorAndIsNotAskedAgain() {
        val installing = UpdateMachine.onVerified(UpdateMachine.onStart(UpdateMachine.onManifest(UpdateState(), manifest(101), 100)), false)
        val cancelled = UpdateMachine.onFailure(installing, UpdateFailure.CANCELLED)
        assertEquals(UpdatePhase.AVAILABLE, cancelled.phase)
        assertTrue("Home does not ask again this launch", cancelled.dismissed)
        assertEquals("cancelled", cancelled.reason)
        // The same manifest read again (a cached answer) keeps the dismissal.
        assertTrue(UpdateMachine.onManifest(cancelled, manifest(101), 100).dismissed)
        // A newer version is offered afresh.
        assertFalse(UpdateMachine.onManifest(cancelled, manifest(102), 100).dismissed)
    }

    @Test
    fun aDownloadUnderWayIsNotDisturbedByAManifest() {
        val downloading = UpdateMachine.onStart(UpdateMachine.onManifest(UpdateState(), manifest(101), 100))
        assertSame(downloading, UpdateMachine.onManifest(downloading, null, 100))
        assertSame(downloading, UpdateMachine.onManifest(downloading, manifest(102), 100))
    }

    @Test
    fun dismissAndPermission() {
        val available = UpdateMachine.onManifest(UpdateState(), manifest(101), 100)
        assertTrue(UpdateMachine.onDismiss(available).dismissed)
        val none = UpdateState()
        assertSame("nothing to dismiss", none, UpdateMachine.onDismiss(none))
        val blocked = UpdateMachine.onFailure(available, UpdateFailure.PERMISSION)
        assertEquals(UpdatePhase.FAILED, blocked.phase)
        val granted = UpdateMachine.onPermissionGranted(blocked)
        assertEquals(UpdatePhase.AVAILABLE, granted.phase)
        assertEquals("", granted.reason)
        val otherFailure = UpdateMachine.onFailure(available, UpdateFailure.CHECKSUM)
        assertSame(otherFailure, UpdateMachine.onPermissionGranted(otherFailure))
    }

    @Test
    fun eventsDoNothingInTheWrongPhase() {
        val none = UpdateState()
        assertSame(none, UpdateMachine.onStart(none))
        assertSame(none, UpdateMachine.onProgress(none, 10, false))
        assertSame(none, UpdateMachine.onVerified(none, false))
        assertSame(none, UpdateMachine.onFailure(none, UpdateFailure.NETWORK))
    }

    @Test
    fun theWire() {
        val json = JSONObject(UpdateMachine.toJson(UpdateMachine.onManifest(UpdateState(), manifest(101, 2048), 100), "0.2.0"))
        assertEquals("available", json.getString("state"))
        assertEquals("0.2.0", json.getString("currentVersion"))
        assertEquals("0.2.101", json.getString("versionName"))
        assertEquals(101L, json.getLong("versionCode"))
        assertEquals(2048L, json.getLong("sizeBytes"))
        assertEquals(0.0, json.getDouble("progress"), 0.0)
        assertEquals("", json.getString("reason"))
        assertFalse(json.getBoolean("dismissed"))
        val none = JSONObject(UpdateMachine.toJson(UpdateState(), "0.2.0"))
        assertEquals("none", none.getString("state"))
        assertEquals("", none.getString("versionName"))
        val keys = json.keys().asSequence().toSortedSet()
        assertEquals(sortedSetOf("currentVersion", "dismissed", "downloadedBytes", "notes", "progress", "reason", "sizeBytes", "state", "throttled", "versionCode", "versionName", "waitingForPlayback"), keys)
        assertEquals(UpdatePhase.values().map { it.wire }.toSet(), setOf("none", "available", "downloading", "ready", "installing", "failed"))
    }
}
