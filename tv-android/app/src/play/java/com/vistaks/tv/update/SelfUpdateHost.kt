package com.vistaks.tv.update

import android.app.Activity

/**
 * The `play` flavor's self-update: none. Play updates the app itself, so the play APK has no
 * install permission, no downloader and no bridge (`bridge()` is null: the page sees no
 * `window.VistaksTvUpdate` and shows no update entry).
 *
 * The public signature must stay identical to `src/sideload/.../SelfUpdateHost.kt`, since
 * `src/main` compiles against both (scripts/test_tv_self_update_contract.mjs pins it).
 */
@Suppress("UNUSED_PARAMETER", "unused")
class SelfUpdateHost(
    activity: Activity,
    emit: (event: String, json: String) -> Unit, // main thread; MainActivity forwards to window.VistaksTv.native
) {
    fun bridge(): Any? = null

    fun onCreate(forceCheck: Boolean) {}

    fun onResume() {}

    fun setPlaybackActive(active: Boolean) {}

    fun release() {}
}
