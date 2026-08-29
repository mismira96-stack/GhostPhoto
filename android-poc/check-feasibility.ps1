#Requires -Version 5.1
<#
.SYNOPSIS
  Company-safe Android feasibility check:
  1) TOOLCHAIN — SDK / adb / emulator
  2) SELECT    — fixture app crown test (exactly 10 candidates)
#>
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$emulator = Join-Path $env:ANDROID_HOME "emulator\emulator.exe"
$avdName = (& $emulator -list-avds | Select-Object -First 1)
if (-not $avdName) { throw "No AVD found. Create one in Android Studio Device Manager." }
$gradlew = Join-Path $Root "gradlew.bat"

function Write-Step([string]$msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

Write-Step "TOOLCHAIN"
if (-not (Test-Path $env:JAVA_HOME)) { throw "JAVA_HOME missing: $env:JAVA_HOME" }
if (-not (Test-Path $adb)) { throw "adb missing: $adb" }
if (-not (Test-Path $emulator)) { throw "emulator missing: $emulator" }
if (-not (Test-Path $gradlew)) { throw "gradlew.bat missing — run once: gradle wrapper" }

& $adb version | Select-Object -First 1
$devices = & $adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] }
if (-not $devices) {
    Write-Host "No device. Starting AVD: $avdName"
    Start-Process -FilePath $emulator -ArgumentList "-avd", $avdName, "-netdelay", "none", "-netspeed", "full" -WindowStyle Minimized
    $deadline = (Get-Date).AddMinutes(4)
    do {
        Start-Sleep -Seconds 5
        $boot = & $adb shell getprop sys.boot_completed 2>$null
        $devices = & $adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] }
        Write-Host "waiting boot... devices=$($devices -join ',') boot=$boot"
        if ((Get-Date) -gt $deadline) { throw "Emulator boot timeout" }
    } while (-not $devices -or ($boot -as [string]).Trim() -ne "1")
}
$targetDevice = ($devices | Where-Object { $_ -match "emulator" } | Select-Object -First 1)
if (-not $targetDevice) { $targetDevice = $devices[0] }
Write-Host "TOOLCHAIN PASS — target device: $targetDevice"
$env:ANDROID_SERIAL = $targetDevice

Write-Step "SELECT (fixture crown test)"
& $gradlew "connectedDebugAndroidTest" "--info" 2>&1 | Tee-Object -Variable buildOut | Out-Host
$joined = ($buildOut | Out-String)
if ($LASTEXITCODE -ne 0) {
    Write-Host "SELECT FAIL — gradle exit $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}
if ($joined -match "SelectTenTest") {
    Write-Host "SELECT PASS — SelectTenTest completed" -ForegroundColor Green
} else {
    Write-Host "SELECT PASS (gradle OK) — check HTML report if needed" -ForegroundColor Green
}

Write-Host "`nFEASIBILITY: Android fixture select-10 pattern is runnable on this PC." -ForegroundColor Green
Write-Host "Next (home only): try the same pattern on real Photos UI — never delete from scripts."
