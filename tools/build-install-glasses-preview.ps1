[CmdletBinding()]
param(
    [switch]$LaunchPreview,
    [switch]$LaunchApp
)

$ErrorActionPreference = 'Stop'

$PackageName = 'com.example.ai_assist.refactored'
$PreviewActivity = 'com.example.ai_assist.preview.MahjongUiPreviewLayoutTwoActivity'
$MainActivity = 'com.example.ai_assist.entry.MainActivity'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ApkPath = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'

function Find-Adb {
    $candidates = @(
        'C:\Users\yuqi.wang\AppData\Local\Android\Sdk\platform-tools\adb.exe'
    )

    if ($env:ANDROID_SDK_ROOT) {
        $candidates += Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
    }
    if ($env:ANDROID_HOME) {
        $candidates += Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }

    throw 'Cannot find adb.exe. Install Android SDK Platform-Tools or set ANDROID_SDK_ROOT.'
}

$AdbPath = Find-Adb
$deviceLines = & $AdbPath devices
if ($LASTEXITCODE -ne 0) {
    throw 'adb devices failed.'
}

$devices = @($deviceLines | Where-Object { $_ -match '^(\S+)\s+device$' } | ForEach-Object {
    if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
})

if ($devices.Count -eq 0) {
    throw 'No authorized device found. Connect the glasses and accept USB debugging if prompted.'
}
if ($devices.Count -gt 1) {
    throw "More than one device is connected: $($devices -join ', '). Disconnect other devices and retry."
}

$DeviceSerial = $devices[0]
Write-Host "Using device: $DeviceSerial"

Push-Location $ProjectRoot
try {
    Write-Host 'Building Debug APK...'
    & .\gradlew.bat --no-daemon :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "Debug APK was not generated: $ApkPath"
}

Write-Host "Removing previous preview package: $PackageName"
$uninstallOutput = (& $AdbPath -s $DeviceSerial uninstall $PackageName 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -and $uninstallOutput -notmatch 'Unknown package|not installed') {
    throw "Could not uninstall the previous preview package:`n$uninstallOutput"
}

Write-Host 'Installing Debug APK...'
& $AdbPath -s $DeviceSerial install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "APK installation failed with exit code $LASTEXITCODE."
}

if ($LaunchPreview) {
    if ($LaunchApp) {
        throw 'Use either -LaunchApp or -LaunchPreview, not both.'
    }
    Write-Host 'Opening Mahjong UI preview...'
    & $AdbPath -s $DeviceSerial shell am start -n "$PackageName/$PreviewActivity"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not start the preview activity (exit code $LASTEXITCODE)."
    }
}
elseif ($LaunchApp) {
    Write-Host 'Opening the Mahjong assistant...'
    & $AdbPath -s $DeviceSerial shell am start -n "$PackageName/$MainActivity"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not start the Mahjong assistant (exit code $LASTEXITCODE)."
    }
}

Write-Host 'Done.'
