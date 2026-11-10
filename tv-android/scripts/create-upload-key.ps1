<#
.SYNOPSIS
  建立 Vistaks TV 的 Play 上傳金鑰（tv-android\upload-keystore.jks），可選擇寫入 keystore.properties。

.DESCRIPTION
  - 以 Android Studio JBR 的 keytool -genkeypair 建立 RSA 2048、有效 10000 天、別名 upload 的 PKCS12 金鑰庫。
  - 密碼由你在 keytool 的提示中自己輸入；本腳本不接受密碼參數、不顯示密碼。
  - 金鑰庫已存在時拒絕覆寫。
  - 之後可選擇再輸入一次密碼（Read-Host -AsSecureString），先用 keytool 驗證正確，再寫入 gitignored 的
    tv-android\keystore.properties，供 .\gradlew.bat bundlePlayRelease（與 assembleSideloadRelease）使用。
  - 不想把密碼存在檔案裡，就跳過這一步，改在建置前設定環境變數
    VISTAKS_TV_UPLOAD_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD。
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$tvRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keystorePath = Join-Path $tvRoot 'upload-keystore.jks'
$propertiesPath = Join-Path $tvRoot 'keystore.properties'
$alias = 'upload'

function Find-Keytool {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\keytool.exe') }
    $candidates += 'D:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
    $candidates += (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe')
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $candidate }
    }
    $onPath = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw '找不到 keytool.exe。請先設定 JAVA_HOME（例如 D:\Program Files\Android\Android Studio\jbr）再重跑。'
}

# Java .properties 是 ISO-8859-1：反斜線、開頭空白、控制字元與非 ASCII 都要跳脫，否則 Gradle 讀到的密碼會不同。
function ConvertTo-PropertiesValue([string]$value) {
    $builder = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $value.Length; $i++) {
        $ch = $value[$i]
        $code = [int]$ch
        if ($ch -eq [char]'\') { [void]$builder.Append('\\') }
        elseif ($i -eq 0 -and $ch -eq [char]' ') { [void]$builder.Append('\ ') }
        elseif ($code -lt 0x20 -or $code -gt 0x7E) { [void]$builder.Append(('\u{0:X4}' -f $code)) }
        else { [void]$builder.Append($ch) }
    }
    return $builder.ToString()
}

function ConvertFrom-SecureStringPlain([System.Security.SecureString]$secure) {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

if (Test-Path -LiteralPath $keystorePath) {
    Write-Host "已經有上傳金鑰：$keystorePath" -ForegroundColor Yellow
    Write-Host '不會覆寫。上傳金鑰換掉之後 Play 會拒收新的 AAB；真的要換，請在 Play Console 申請重設上傳金鑰。'
    exit 1
}

$keytool = Find-Keytool
Write-Host "keytool：$keytool"
Write-Host "建立：$keystorePath（PKCS12、RSA 2048、10000 天、別名 $alias）"
Write-Host ''
Write-Host '接下來 keytool 會請你輸入兩次金鑰庫密碼（至少 6 個字元，輸入時不會顯示）。' -ForegroundColor Cyan
Write-Host 'PKCS12 金鑰庫的金鑰密碼與金鑰庫密碼相同。' -ForegroundColor Cyan
Write-Host ''

& $keytool -genkeypair -v `
    -keystore $keystorePath `
    -storetype PKCS12 `
    -alias $alias `
    -keyalg RSA -keysize 2048 `
    -validity 10000 `
    -dname 'CN=Vistaks TV Upload, O=Vistaks'
$genExit = $LASTEXITCODE

if ($genExit -ne 0 -or -not (Test-Path -LiteralPath $keystorePath)) {
    Write-Host "keytool 失敗（exit $genExit），沒有建立金鑰庫。" -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host "已建立上傳金鑰：$keystorePath" -ForegroundColor Green
Write-Host ''

$writeProps = $true
if (Test-Path -LiteralPath $propertiesPath) {
    $answer = Read-Host "已經有 $propertiesPath（舊的設定）。要用新金鑰覆寫嗎？(y/N)"
    $writeProps = ($answer -match '^(y|yes)$')
} else {
    $answer = Read-Host '要把密碼寫進 tv-android\keystore.properties（已 gitignore）讓 bundlePlayRelease 直接使用嗎？(Y/n)'
    $writeProps = -not ($answer -match '^(n|no)$')
}

if ($writeProps) {
    $envName = 'VISTAKS_TV_KEYTOOL_VERIFY'
    $plain = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $secure = Read-Host '再輸入一次金鑰庫密碼（用來驗證並寫入 keystore.properties）' -AsSecureString
        $candidate = ConvertFrom-SecureStringPlain $secure
        # 只透過本程序的環境變數交給 keytool 驗證，不放在命令列參數上；驗證完立刻清掉。
        Set-Item -Path "Env:$envName" -Value $candidate
        # Windows PowerShell 5.1 把重新導向的 native stderr 當成錯誤；驗證失敗是預期情況，不能讓它中斷腳本。
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $keytool -list -keystore $keystorePath -storetype PKCS12 -alias $alias -storepass:env $envName *> $null
            $ok = ($LASTEXITCODE -eq 0)
        } finally {
            $ErrorActionPreference = $previousPreference
            Remove-Item -Path "Env:$envName" -ErrorAction SilentlyContinue
        }
        if ($ok) { $plain = $candidate; break }
        $candidate = $null
        Write-Host '密碼不對，請再試一次。' -ForegroundColor Yellow
    }

    if ($null -eq $plain) {
        Write-Host '三次都驗證失敗，沒有寫入 keystore.properties。金鑰庫已經建立；之後可改用環境變數，或手動建立 keystore.properties（格式見 README）。' -ForegroundColor Yellow
    } else {
        $escaped = ConvertTo-PropertiesValue $plain
        $plain = $null
        $content = @(
            '# Vistaks TV Play upload key. Gitignored. Never commit, never share.'
            'storeFile=upload-keystore.jks'
            "storePassword=$escaped"
            "keyAlias=$alias"
            "keyPassword=$escaped"
            ''
        ) -join "`n"
        $escaped = $null
        # 無 BOM 的 ASCII（值已全部跳脫成 ASCII）；BOM 會被 Java 當成第一個鍵的一部分。
        [System.IO.File]::WriteAllText($propertiesPath, $content, (New-Object System.Text.UTF8Encoding($false)))
        $content = $null
        Write-Host "已寫入：$propertiesPath" -ForegroundColor Green
    }
}

Write-Host ''
Write-Host '================ 請立刻備份 ================' -ForegroundColor Yellow
Write-Host "1. 把 $keystorePath 複製到你的 Google 雲端硬碟（或其他離線備份）。"
Write-Host '2. 把金鑰庫密碼記在密碼管理器（不要和 .jks 放在同一個地方的純文字檔）。'
Write-Host '3. 弄丟 .jks 或忘記密碼，就無法再上傳新版本，只能在 Play Console 申請重設上傳金鑰（要等 Google 處理）。'
Write-Host '   App 本身由 Google 保管的應用程式簽署金鑰簽署，所以重設上傳金鑰不會影響已安裝的使用者。'
Write-Host '============================================' -ForegroundColor Yellow
Write-Host ''
Write-Host '下一步：cd tv-android；.\gradlew.bat bundlePlayRelease'
