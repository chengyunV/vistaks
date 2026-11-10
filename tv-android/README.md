# Vistaks TV（Android TV / Google TV 原生殼）

這是 Vistaks 在 Android TV / Google TV 上的原生外殼：一個 Kotlin 單一 Activity 的 App，全螢幕 WebView 載入託管的 TV 網頁殼（`https://vistaks.com/tv`）。配對、影庫、播放（YouTube / Twitch 嵌入 iframe）全部由網頁負責；原生殼只提供：

- Leanback 啟動器整合（電視主畫面出現 App 與橫幅）
- D-pad／返回鍵／媒體鍵處理（返回與媒體鍵轉給網頁）
- iframe 全螢幕影片支援（`onShowCustomView` / `onHideCustomView`）
- JS 橋接 `VistaksTvHost`
- 無法連線時的原生重試畫面
- 沉浸模式（隱藏系統列）
- 螢幕常亮租約：只在網頁說「正在播」時常亮，網頁每 4 分鐘續租，沒人續租的租約 10 分鐘後自動失效
- 生命週期轉給網頁（pause / resume / trim），背景時暫停所有 WebView 計時器
- renderer 被系統殺掉時原地重建 WebView、讓網頁接著播；網頁要求的計畫性回收走同一條路
- 上架 Play 所需的 TV manifest 中繼資料

| 項目 | 值 |
| --- | --- |
| applicationId | `com.vistaks.tv` |
| versionCode / versionName | release：建置當下的 UTC 分鐘數（見「上架 Play 內部測試」），debug：`1` / `0.1.0` |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| 工具鏈 | Gradle 8.14.3、AGP 8.11.0、Kotlin 2.0.21、JDK 17+（Android Studio 內建 JBR 21 可用） |
| 相依 | 共用：`androidx.core:core-ktx`、`androidx.activity:activity-ktx`、`androidx.webkit:webkit`、`desugar_jdk_libs_nio`（無 Compose、無 Leanback 函式庫）；只有 `sideload`：Media3（exoplayer、exoplayer-dash、ui）、OkHttp、NewPipeExtractor（JitPack） |
| flavor（`distribution`） | `sideload`：側載安裝的 APK，含原生 4K 播放器，以 GPL-3.0-or-later 釋出。`play`：上 Play 的版本，只有 iframe，不含抽取器與任何 GPL 程式碼。兩者 `applicationId` 都是 `com.vistaks.tv`；`BuildConfig.NATIVE_PLAYER` 分別是 `true`／`false` |

## 授權

- **`sideload` 版以 GPL-3.0-or-later 釋出。** 它連結 NewPipeExtractor（GPL-3.0-or-later），所以整個 APK 是 GPL-3.0-or-later。全文在 [`LICENSE`](LICENSE)（gnu.org 的 `gpl-3.0.txt` 原文）。
- **`play` 版不含 GPL 程式碼**：沒有抽取器、Media3、OkHttp，也沒有 `NOTICE.json`，只用 iframe 播放。
- **範圍只有 `tv-android/`。** 電視介面（`vistaks.com/tv`）是另外從網站載入的程式，只透過 JS 橋接溝通，不隨 APK 散布；桌面 App 與網站是獨立的程式。
- **第三方元件**：[`app/src/sideload/assets/NOTICE.json`](app/src/sideload/assets/NOTICE.json)，形狀是 `{ license, sourceUrl, components: [{ name, version, license, url }] }`，`NativePlayerHost.licenseInfo()` 回傳它，電視「設定 › 關於」顯示它（`play` 版回 `{}`）。清單照 Gradle 實際解析的相依樹列出，`license` 是 SPDX 表示式，`url` 是該專案的原始碼。改了相依就重跑下面的指令，對照後更新 NOTICE（`scripts/test_tv_license_contract.mjs` 會比對 `app/build.gradle.kts` 直接宣告的版本）：

  ```powershell
  .\gradlew.bat :app:dependencies --configuration sideloadReleaseRuntimeClasspath
  .\gradlew.bat :app:dependencies --configuration coreLibraryDesugaring
  ```

- **原始碼包**：若對外提供 `sideload` 版 APK，依 GPL-3.0-or-later 第 6 條，應提供同版本的完整原始碼。本專案的做法：每個版本在公開 repo `https://github.com/chengyunV/vistaks` 建立 release `tv-<版本>`，附上 `vistaks-tv-src-<版本>.tar.gz`（該版 commit 的 `tv-android/` 加 `BUILDING.md` 建置說明）；APK 內 `NOTICE.json` 的 `sourceUrl` 指向這個檔案，電視「設定 › 關於」會顯示。原始碼包由以下指令產生（只寫檔，不上傳）：

  ```powershell
  node scripts/tv_source_bundle.mjs --version=<版本> --out=<資料夾> [--ref=<commit 或 tag，預設 HEAD>]
  ```

  檔案取自 git 的那個 commit，不是工作目錄：發版時以同一個 commit（或 tag）建 APK 與原始碼包，兩者即可對應。`--ref` 是 `HEAD` 而 `tv-android/` 有未 commit 或未追蹤的檔案時，腳本拒絕產出並列出那些路徑（`--allow-dirty` 僅供試跑）。`build/`、`.gradle/`、`local.properties`、`keystore.properties`、`*.jks`／`*.keystore` 與任何金鑰或憑證一律排除。同一個 commit 與版本每次產出的位元組相同。`<版本>` 採用 APK 的 `versionName`；發新版前先在 `app/build.gradle.kts` 調高它。
- **`sourceUrl` 的格式**：`NOTICE.json` 的 `sourceUrl` 是版本化的檔案網址，以 `{version}` 佔位（目前為 `https://github.com/chengyunV/vistaks/releases/download/tv-{version}/vistaks-tv-src-{version}.tar.gz`），由 `licenseInfo()` 以 `BuildConfig.VERSION_NAME` 代換；`scripts/test_tv_license_contract.mjs` 檢查這個格式。
- **簽章金鑰**：GPLv3 第 6 條的「安裝資訊」適用於隨「使用者產品」（User Product，例如硬體裝置）一起轉讓的目的碼；單獨提供的 APK 屬於另一種情形，簽章金鑰留在發布者手上。自行建置的 APK 以建置者自己的金鑰簽署；與已安裝版本簽章不同時，安裝前先解除安裝既有版本。

## 建置

需要 Android SDK（platform 36）。`local.properties` 指向 SDK（此檔不進版控）：

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

```powershell
cd tv-android
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleSideloadDebug
# 產出：app\build\outputs\apk\sideload\debug\app-sideload-debug.apk
.\gradlew.bat assemblePlayDebug
# 產出：app\build\outputs\apk\play\debug\app-play-debug.apk
```

有兩個 flavor，所以 task 名稱都帶 flavor（不帶的 `assembleDebug` 會兩個都建）。

正式版：
- 上 Play 一律用 `.\gradlew.bat bundlePlayRelease`（啟用 R8 縮減，用上傳金鑰簽出 AAB，見下一節）。
- 側載版用 `.\gradlew.bat assembleSideloadRelease`：有上傳金鑰時用同一把簽出 APK；沒有時產出未簽章的 `app-sideload-release-unsigned.apk`，僅供本機檢查。Play 安裝的版本由 Google 以應用程式金鑰重簽，同一台電視從 Play 版換成側載版時先解除安裝。

## 上架 Play 內部測試

App 由 Play 應用程式簽署（Google 保管應用程式金鑰）；我們只保管**上傳金鑰**，用它簽 AAB 再上傳（ADR-0094 §4）。

### 1. 建立上傳金鑰（只做一次）

```powershell
cd tv-android
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\create-upload-key.ps1
```

- keytool 會請你自己輸入兩次密碼（RSA 2048、有效 10000 天、別名 `upload`、PKCS12，金鑰密碼＝金鑰庫密碼），產出 `tv-android\upload-keystore.jks`；檔案已存在時腳本拒絕覆寫。
- 接著腳本問要不要寫入 `tv-android\keystore.properties`：再輸入一次密碼，驗證正確才寫入。兩個檔都已 gitignore，絕不 commit。
- 不想把密碼存在檔案裡：跳過這一步，建置前改設環境變數 `VISTAKS_TV_UPLOAD_STORE_FILE`、`VISTAKS_TV_UPLOAD_STORE_PASSWORD`、`VISTAKS_TV_UPLOAD_KEY_ALIAS`（`upload`）、`VISTAKS_TV_UPLOAD_KEY_PASSWORD`。每個值先讀環境變數，沒有才讀 `keystore.properties`；`storeFile` 的相對路徑以 `tv-android\` 為基準。

`keystore.properties` 格式（Java properties：值裡的反斜線要寫兩個、非 ASCII 字元要寫成 `\uXXXX`；腳本會自動處理）：

```properties
storeFile=upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

> **立刻備份**：把 `upload-keystore.jks` 複製到 Google 雲端硬碟，密碼存進密碼管理器。弄丟 .jks 或忘記密碼就不能再上傳新版本，只能在 Play Console 申請重設上傳金鑰並等 Google 處理（已安裝的使用者不受影響，因為 App 是用 Google 的應用程式金鑰簽的）。

### 2. 建置 AAB

```powershell
cd tv-android
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat bundlePlayRelease
# 產出：app\build\outputs\bundle\playRelease\app-play-release.aab
```

沒有上傳金鑰（或設定不完整、`storeFile` 不存在）時，`bundlePlayRelease`（以及任何 `bundle*Release`／`sign*ReleaseBundle`）在開始建置前就失敗，列出缺哪一項與建立方法；永遠不會產出未簽章或用 debug 金鑰簽的 AAB。debug 建置不受影響。

### 3. 上傳

Play Console › VistaksTV › 測試 › 內部測試 › 建立新版本 › 上傳 `app-play-release.aab` › 填版本資訊 › 儲存並發布。第一次上傳的 AAB 用哪把金鑰簽，Play 就把那把登記成上傳金鑰。

### versionCode 規則

- release 的 `versionCode`＝建置當下距 `2026-01-01T00:00Z` 的整分鐘數（UTC），例如 2026-11-05 12:00 UTC 建置是 `444240`。只在 Gradle 設定階段計算，不寫回 repo；時間只會往前，所以每次建置都比上次大，離 Play 上限 2,100,000,000 還有約 3,990 年。
- 同一分鐘內建置兩次會得到同一個值，Play 會拒收重複的 versionCode：隔一分鐘再建即可。
- debug 固定 `1`（避免每分鐘讓增量建置失效）。
- 緊急覆寫（例如電腦時鐘錯了）：`.\gradlew.bat bundlePlayRelease -PvistaksTvVersionCode=<1–2100000000>`，必須大於 Play 上已有的最大值。
- `versionName` 維持 `0.1.0`（手動在 `app/build.gradle.kts` 改），只給人看。原生殼有變動才需要重傳 AAB；介面更新走網站部署。

## 指向區網開發伺服器

只有 debug 版會讀 Gradle 屬性 `vistaksTvUrl`；release 版永遠是 `https://vistaks.com/tv`。

```powershell
.\gradlew.bat assembleSideloadDebug -PvistaksTvUrl=http://192.168.x.x:5183/tv.html
```

也可以寫進 `gradle.properties`（`vistaksTvUrl=...`）。debug 版的 manifest 開啟 `usesCleartextTraffic`，所以區網 `http://` 可以載入；debug 版也允許主框架導向該開發主機。

## 側載到電視

電視先開啟「開發人員選項 → USB 偵錯／網路偵錯」，然後：

```powershell
adb connect <tv-ip>:5555
adb install -r app\build\outputs\apk\sideload\debug\app-sideload-debug.apk
```

debug 版可用 Chrome 的 `chrome://inspect` 遠端偵錯 WebView（`setWebContentsDebuggingEnabled` 只在 debug 開啟）。

## JS 橋接契約

### 原生提供：`window.VistaksTvHost`

| 方法 | 回傳 | 說明 |
| --- | --- | --- |
| `platform()` | `"android-tv"` / `"android"` | `UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION` 時為 `android-tv` |
| `appVersion()` | 例如 `"0.1.0"` | `BuildConfig.VERSION_NAME` |
| `deviceName()` | 字串 | `Build.MODEL`，去頭尾空白，最多 80 字元 |
| `exit()` | — | 在主執行緒 `finishAndRemoveTask()` |
| `keepAwake(on)` | — | `true`＝持有螢幕常亮（`FLAG_KEEP_SCREEN_ON`，只在 activity 位於前景時生效）並把 10 分鐘的租約重新起算；`false`＝立即放開。沒人續租的租約 10 分鐘後自己失效 |
| `launchReason()` | `"launch"` / `"render-gone"` / `"recycle"` | 這次頁面載入的原因；回答一次之後變回 `"launch"`。`render-gone`＝系統殺掉 renderer 後重建的 WebView，`recycle`＝網頁要求的計畫性回收；這兩種網頁會接著播上次的工作階段，`launch`（從啟動器開啟、重試）回首頁 |
| `recycle()` | — | 計畫性回收：結束目前的 renderer（`WebViewRenderProcess.terminate`，不支援時直接換一個新 WebView），新的 WebView 以 `launchReason() == "recycle"` 載入頁面 |
| `nativePlayerVersion()` | 整數 | 原生播放器的版本（ADR-0100）。`sideload` 版回 `1`，`play` 版回 `0`（網頁用 iframe 播 YouTube）。沒有這個方法的舊 APK 視同 `0` |

重建的 WebView 插在 `error_view` 正下方（`root.indexOfChild(errorView)`），不是 index 0：原生播放器的 SurfaceView 會放在 index 0，WebView 必須留在它上面。

網頁用 `window.VistaksTvHost` 是否存在判斷自己是否在原生殼內；User-Agent 也會多一段 ` VistaksTV/<versionName>`。

### 網頁提供：`window.VistaksTv`

```ts
window.VistaksTv = {
  back(): boolean,          // 處理了返回就回 true；回 false（或未定義）時原生殼把 App 退到背景
  media(cmd: string): boolean,
  lifecycle(event: 'pause' | 'resume' | 'trim', level?: number): boolean,
};
```

- **返回鍵**：原生殼執行 `window.VistaksTv.back()`，結果不是 `1`（true）就 `moveTaskToBack(true)`。WebView 自己的歷史紀錄永遠不處理返回，導覽由網頁負責。iframe 全螢幕時，返回鍵先退出全螢幕。網頁在沒有更深一層畫面時回 true：第一下顯示「再按一次返回鍵離開」2 秒，2 秒內再按一下就呼叫 `exit()`。
- **媒體鍵**：`KEYCODE_MEDIA_PLAY_PAUSE / PLAY / PAUSE / NEXT / PREVIOUS / REWIND / FAST_FORWARD / STOP` 在按下時分別呼叫 `media('playpause' | 'play' | 'pause' | 'next' | 'previous' | 'rewind' | 'fastforward' | 'stop')`，並吃掉該按鍵（按住只會重複送 `rewind` / `fastforward`）。
- **其他按鍵**（D-pad、Enter 等）原封不動交給 WebView，網頁收到的是一般的方向鍵／Enter 鍵盤事件。
- **生命週期**：`onPause` 先呼叫 `lifecycle('pause')`（網頁暫停播放器、關掉 revision socket、把隱藏內容上鎖）再 `webView.onPause()`；`onResume` 呼叫 `lifecycle('resume')`（重新訂閱並補讀一次）；`onTrimMemory(level)` 在 `TRIM_MEMORY_RUNNING_LOW`（10）以上呼叫 `lifecycle('trim', level)`，`RUNNING_CRITICAL`（15）且沒在播時網頁會計畫性回收。`onStop` 呼叫 `pauseTimers()`、`onStart` 呼叫 `resumeTimers()`（整個程序共用，只有一個 WebView 所以沒關係）。WebView 已經不在時一律不送。
- **renderer 消失**（`onRenderProcessGone`）：拆掉舊 WebView、在同一個位置建新的並載入頁面，`launchReason()` 回 `render-gone`，網頁接著播上次的工作階段，不回首頁。10 分鐘內非計畫性的 renderer 消失超過 3 次就停在原生錯誤畫面（「Vistaks 一再意外關閉」），按「重試」從首頁重新開始。

## 實機探測（SHIELD）

`scripts/device/` 是在實機上量測播放與合成層的腳本（Git Bash；adb 位址由環境變數 `VISTAKS_TV_ADB` 指定，例如 `<tv-ip>:5555`）：`tvconnect.sh` 把 App 叫到前景並轉發 WebView DevTools、`cdp_eval.mjs`／`cdp_frame_eval.mjs` 在網頁或 YouTube iframe 裡執行 JS、`ytstats.js` 讀嵌入的畫質與掉格、`caps.js` 讀解碼能力、`layers.sh` 列出合成層。WebView 的合成層是 1920×1080；4K 影像由原生播放器的 SurfaceView 輸出。

## 導覽與安全限制

- 主框架只允許 `https://vistaks.com` 與 `https://*.vistaks.com`（debug 版另允許 `vistaksTvUrl` 的主機）；其他主機的主框架導覽一律拒絕且不開外部瀏覽器。
- iframe（`youtube.com`、`youtube-nocookie.com`、`player.twitch.tv`、`clips.twitch.tv`、`twitch.tv`）不受上述限制。
- 不允許混合內容、檔案與 content:// 存取；不開新視窗；網頁的相機／麥克風等權限請求一律拒絕。
- 不做雲端備份與裝置轉移（配對狀態存在 WebView storage，不應被複製到別台裝置）。
- 主框架載入失敗時隱藏 WebView，顯示「無法連線到 Vistaks」與可聚焦的「重試」按鈕。

## Play Console TV 檢查項目（本殼已滿足）

- [x] `MAIN` + `android.intent.category.LEANBACK_LAUNCHER` intent-filter（同時保留一般 `LAUNCHER`，手機也能安裝測試）
- [x] `android:banner` 320×180（`res/drawable-xhdpi/banner.png`）
- [x] `android.hardware.touchscreen` 宣告為 `required="false"`
- [x] `android.software.leanback` 宣告為 `required="false"`（同一個 APK 可裝在手機與電視）
- [x] 不需要觸控即可完整操作（D-pad 由網頁處理，重試畫面可用遙控器聚焦）
- [x] 橫向、全螢幕、深色主題、無 Action Bar

仍需在 Play Console 另外準備：TV 截圖、TV 橫幅圖、加入 Android TV 發佈軌道並送審；網頁端也要確保每個畫面都能只用 D-pad 操作。
