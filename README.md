# Vistaks TV — 電視殼原始碼（側載版）

Vistaks TV 是 Android TV／Google TV 上的原生外殼：單一 Activity 以全螢幕 WebView 載入電視介面，並在側載版提供原生 4K 播放器。本 repo 是側載版的完整原始碼，以 **GPL-3.0-or-later** 釋出（全文見 `LICENSE`）。第三方元件及其授權列於 `tv-android/app/src/sideload/assets/NOTICE.json`。

- 版本對應：每個版本一個 release `tv-<版本>`，附 `vistaks-tv-src-<版本>.tar.gz`（該版本的原始碼包）；APK 內的授權頁會顯示對應的網址。
- 建置方式：見 `BUILDING.md`。
- 範圍：電視介面（`https://vistaks.com/tv`）是另外載入的程式，只透過 JS 橋接溝通；桌面 App 與網站是獨立的程式，皆不在本 repo。

Vistaks TV is the Android TV shell of Vistaks. This repository holds the complete source of the sideload flavor, released under GPL-3.0-or-later. Each release `tv-<version>` carries `vistaks-tv-src-<version>.tar.gz`; see `BUILDING.md` to build.
