package com.vistaks.tv.nativeplayer

import android.app.Activity
import android.widget.FrameLayout

/**
 * The `play` flavor's native player: none (ADR-0100 §6). Play builds never extract streams or
 * bypass the YouTube player, and carry no GPL code; the page always plays YouTube in an iframe.
 *
 * The public signature must stay identical to `src/sideload/.../NativePlayerHost.kt`, since
 * `src/main` compiles against both (scripts/test_tv_native_bridge_contract.mjs pins it). It never
 * calls [emit] or [overlay]: the WebView stays opaque and the bridge's native* methods do nothing.
 */
@Suppress("UNUSED_PARAMETER", "unused")
class NativePlayerHost(
    activity: Activity,
    emit: (event: String, json: String) -> Unit, // main thread; MainActivity forwards to window.VistaksTv.native
    overlay: (transparent: Boolean) -> Unit, // main thread; the WebView turns transparent while the surface shows
) {
    /** 0: no native player, the page keeps the iframe path. */
    fun version(): Int = 0

    fun attach(root: FrameLayout) {}

    fun load(json: String) {}

    fun command(json: String) {}

    fun rect(json: String) {}

    fun state(): String = "{}"

    fun stats(): String = "{}"

    fun release() {}

    fun licenseInfo(): String = "{}"

    fun onHostPause() {}

    fun onWebViewRebuilt() {}
}
