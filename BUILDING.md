# Vistaks TV 0.2.0 — source (sideload flavor)

This archive is the complete source of the Vistaks TV Android shell, version 0.2.0,
taken from commit 71620f66b5d8c39b4ff2799c0843a5dc29ecd83b. The sideload APK of this version is licensed under the
GNU General Public License, version 3 or (at your option) any later version: see
tv-android/LICENSE. Third-party components and their licenses are listed in
tv-android/app/src/sideload/assets/NOTICE.json.

The TV user interface the shell loads (https://vistaks.com/tv) is a separate program that
talks to the shell only through its JavaScript bridge; it is not part of this archive.

## Build

Needs JDK 17 or newer and the Android SDK (platform 36). Point tv-android/local.properties
at the SDK (sdk.dir=...), then in tv-android/:

    ./gradlew assembleSideloadDebug      (Windows: .\gradlew.bat assembleSideloadDebug)

The APK is app/build/outputs/apk/sideload/debug/app-sideload-debug.apk. Gradle downloads the
dependencies (Google Maven, Maven Central, JitPack for NewPipeExtractor) on the first build.

A release build (assembleSideloadRelease) is signed with your own key: configure one through
tv-android/keystore.properties or the VISTAKS_TV_UPLOAD_* environment variables
(tv-android/README.md). An APK signed with a different key cannot update the one already
installed: uninstall it first (pairing is then done again; the library lives in the cloud).

---

# Vistaks TV 0.2.0 原始碼（側載版）

這是 Vistaks TV 電視殼 0.2.0 版的完整原始碼（commit 71620f66b5d8c39b4ff2799c0843a5dc29ecd83b）。側載版 APK 以 GPL-3.0-or-later
釋出，全文見 tv-android/LICENSE；第三方元件與授權見 tv-android/app/src/sideload/assets/NOTICE.json。
電視介面（https://vistaks.com/tv）是另外載入的程式，只透過 JS 橋接溝通，不在這個原始碼包裡。
建置方法同上：JDK 17 以上、Android SDK platform 36，在 tv-android/ 執行 gradlew assembleSideloadDebug。
自己簽的 APK 不能覆蓋已安裝的版本，要先解除安裝。
