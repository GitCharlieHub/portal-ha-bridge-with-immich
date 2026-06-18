<#
.SYNOPSIS
    Provisions a Meta Portal for the Portal HA Bridge app over ADB.

.DESCRIPTION
    One command to set up a new Portal: installs the APK and grants every
    permission that needs ADB. The two permissions that gate optional features
    (screen sleep, Portal presence) are prompted for - say no and the rest of
    the app still works, exactly like the in-app setup flow.

    Broker/HA settings are NOT set here - Android won't let us write another
    app's settings - so finish those on-device after this runs.

.PARAMETER AdbPath
    Path to adb.exe. Defaults to the bundled platform-tools, then PATH.

.PARAMETER ApkPath
    Path to the APK. Defaults to the newest portal-ha-bridge-v*.apk found next
    to this script or one folder up.

.PARAMETER Serial
    Target device serial (from 'adb devices'). Only needed if more than one
    device is connected.

.PARAMETER AssumeYes
    Auto-answer the optional-feature prompts (yes) for unattended provisioning.

.EXAMPLE
    .\provision-portal.ps1

.EXAMPLE
    .\provision-portal.ps1 -ApkPath C:\downloads\portal-ha-bridge-v1.2.0.apk -AssumeYes
#>
param(
    [string]$AdbPath,
    [string]$ApkPath,
    [string]$Serial,
    [string]$Package = "com.aeonos.portalha",
    [Alias("y")][switch]$AssumeYes
)

$ErrorActionPreference = "Stop"

# --- helpers -----------------------------------------------------------------

function Write-Step($msg)  { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)    { Write-Host "    [ OK ] $msg" -ForegroundColor Green }
function Write-Warn2($msg) { Write-Host "    [WARN] $msg" -ForegroundColor Yellow }
function Write-Info($msg)  { Write-Host "    $msg" -ForegroundColor Gray }

function Read-YesNo($question, $defaultYes = $true) {
    if ($script:AssumeYes) { Write-Host "$question -> yes (auto)" -ForegroundColor DarkGray; return $defaultYes }
    $suffix = if ($defaultYes) { "[Y/n]" } else { "[y/N]" }
    while ($true) {
        $a = (Read-Host "$question $suffix").Trim().ToLower()
        if ($a -eq "")  { return $defaultYes }
        if ($a -in @("y","yes")) { return $true }
        if ($a -in @("n","no"))  { return $false }
    }
}

# adb wrapper that injects -s <serial> when targeting a specific device.
function Adb {
    if ($script:Serial) { & $script:AdbExe -s $script:Serial @args 2>&1 }
    else                { & $script:AdbExe @args 2>&1 }
}

function Grant-Runtime($perm, $label) {
    $out = Adb shell pm grant $Package $perm
    if ($LASTEXITCODE -eq 0 -and -not ("$out" -match "Exception|Failure|not allowed|not a")) {
        Write-Ok "$label ($perm)"
    } else {
        Write-Warn2 "$label ($perm) failed: $out"
    }
}

function Set-AppOp($op, $label) {
    $out = Adb shell appops set $Package $op allow
    if ($LASTEXITCODE -eq 0) { Write-Ok "$label ($op)" }
    else { Write-Warn2 "$label ($op) failed: $out" }
}

# --- resolve adb -------------------------------------------------------------

Write-Host "Portal HA Bridge - device provisioner" -ForegroundColor White

$candidates = @($AdbPath, "$PSScriptRoot\platform-tools\adb.exe",
                "F:\claude\platform-tools\adb.exe") | Where-Object { $_ }
$script:AdbExe = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $script:AdbExe) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $script:AdbExe = $cmd.Source }
}
if (-not $script:AdbExe) {
    Write-Host "Could not find adb.exe. Pass -AdbPath C:\path\to\adb.exe" -ForegroundColor Red
    exit 1
}
Write-Info "Using adb: $script:AdbExe"

# --- resolve apk -------------------------------------------------------------

if (-not $ApkPath) {
    $search = @($PSScriptRoot, (Split-Path $PSScriptRoot -Parent)) | Where-Object { $_ }
    $ApkPath = Get-ChildItem -Path $search -Filter "portal-ha-bridge-v*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $ApkPath -or -not (Test-Path $ApkPath)) {
    Write-Host "Could not find an APK. Pass -ApkPath C:\path\to\portal-ha-bridge.apk" -ForegroundColor Red
    exit 1
}
Write-Info "Using APK: $ApkPath"

# --- pick device -------------------------------------------------------------

Write-Step "Checking for a connected Portal"
& $script:AdbExe start-server | Out-Null
$devices = (& $script:AdbExe devices) | Select-Object -Skip 1 |
    Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] }

if (-not $devices) {
    Write-Host "No device detected. Connect the Portal by USB, enable USB debugging, accept the prompt, and retry." -ForegroundColor Red
    exit 1
}
if (-not $Serial) {
    if (@($devices).Count -gt 1) {
        Write-Host "Multiple devices connected:" -ForegroundColor Yellow
        for ($i = 0; $i -lt $devices.Count; $i++) { Write-Host "  [$i] $($devices[$i])" }
        $idx = [int](Read-Host "Pick a device number")
        $script:Serial = $devices[$idx]
    } else {
        $script:Serial = @($devices)[0]
    }
} else {
    $script:Serial = $Serial
}
Write-Ok "Target device: $script:Serial"

# --- install -----------------------------------------------------------------

Write-Step "Installing the app"
# -r keep data, -t allow test-only (Studio debug/release builds set this flag)
$out = Adb install -r -t "$ApkPath"
if ("$out" -match "Success") { Write-Ok "APK installed" }
else { Write-Warn2 "Install output: $out" }

# --- core permissions (always) -----------------------------------------------

Write-Step "Granting core permissions"
Grant-Runtime "android.permission.CAMERA"        "Camera"
Grant-Runtime "android.permission.RECORD_AUDIO"  "Microphone (sound level)"
Set-AppOp     "WRITE_SETTINGS"                    "Screen brightness"
Set-AppOp     "SYSTEM_ALERT_WINDOW"              "Overlay (keeps camera available)"

# --- optional, feature-gating permissions (prompted) -------------------------

Write-Step "Optional features (each needs one ADB permission)"

Write-Info "Screen sleep lets Home Assistant blank the Portal screen. Meta hides"
Write-Info "the accessibility toggle on Portal, so this is the only way to enable it."
if (Read-YesNo "  Enable screen sleep?") {
    Grant-Runtime "android.permission.WRITE_SECURE_SETTINGS" "Screen sleep"
} else { Write-Info "Skipped - re-run this script later to add it." }

Write-Info ""
Write-Info "Portal Presence publishes Meta's own person-detection to Home Assistant"
Write-Info "as an occupancy sensor (no app camera needed). Reads it from the system log."
if (Read-YesNo "  Enable Portal presence sensor?") {
    Grant-Runtime "android.permission.READ_LOGS" "Portal presence"
} else { Write-Info "Skipped - re-run this script later to add it." }

# --- verify ------------------------------------------------------------------

Write-Step "Verifying"
$dump = "$(Adb shell dumpsys package $Package)"
function Check-Granted($perm, $label) {
    if ($script:dump -match ([regex]::Escape($perm) + ": granted=true")) { Write-Ok "$label" }
    else { Write-Warn2 "$label not granted (optional, may be skipped)" }
}
Check-Granted "android.permission.CAMERA"                "Camera"
Check-Granted "android.permission.RECORD_AUDIO"          "Microphone"
Check-Granted "android.permission.WRITE_SECURE_SETTINGS" "Screen sleep"
Check-Granted "android.permission.READ_LOGS"             "Portal presence"
if ("$(Adb shell appops get $Package WRITE_SETTINGS)" -match "allow") { Write-Ok "Screen brightness" }
else { Write-Warn2 "Screen brightness (WRITE_SETTINGS) not allowed" }
if ("$(Adb shell appops get $Package SYSTEM_ALERT_WINDOW)" -match "allow") { Write-Ok "Overlay" }
else { Write-Warn2 "Overlay (SYSTEM_ALERT_WINDOW) not allowed" }

# --- launch + next steps -----------------------------------------------------

Write-Step "Launching the app"
Adb shell am start -n "$Package/.DashboardActivity" | Out-Null
Write-Ok "Started"

Write-Host "`nDone. On the Portal, open the app's settings and enter:" -ForegroundColor White
Write-Host "  - MQTT broker host / port / username / password" -ForegroundColor Gray
Write-Host "  - Device name and Home Assistant URL" -ForegroundColor Gray
Write-Host "Then tap Save and Restart Service. The device auto-discovers in Home Assistant.`n" -ForegroundColor Gray
