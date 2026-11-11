<#
.SYNOPSIS
  Runs the renderer spike (spec §12.3, M2) on a phone: set up once, then one run per renderer.

.EXAMPLE
  # Once: build both test apps, install them, copy the map and the themes (about 1 GB per app).
  ./spike/measure.ps1 -Setup -MapFile C:\maps\Switzerland_oam.osm.map -ThemeDir C:\themes\OAM

.EXAMPLE
  # One 30-minute battery run. The map loads, then the app waits until the cable is pulled.
  ./spike/measure.ps1 -Renderer vtm -Minutes 30

.EXAMPLE
  # Afterwards, with the phone plugged in again: copy every log to .\spike-logs and summarise.
  ./spike/measure.ps1 -Pull
#>
param(
    [string] $Serial,
    [switch] $Setup,
    [string] $MapFile,
    [string] $ThemeDir,
    [ValidateSet('mapsforge', 'vtm')] [string] $Renderer,
    [ValidateSet('smooth', 'step')] [string] $Mode = 'smooth',
    [int] $Minutes = 30,
    [string] $Theme = 'singletrail_mtb.xml',
    [switch] $NoWait,
    [switch] $Pull,
    [string] $OutDir = (Join-Path (Get-Location) 'spike-logs')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$adbExe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adbExe)) { $adbExe = 'C:\Android\sdk\platform-tools\adb.exe' }
$device = if ($Serial) { @('-s', $Serial) } else { @() }
function Adb { & $adbExe @device @args; if ($LASTEXITCODE -ne 0) { throw "adb $args failed" } }
$activity = 'io.github.lrosa2.singletrail.spike.SpikeActivity'
function Package([string] $name) { "io.github.lrosa2.singletrail.spike.$name" }
function Files([string] $name) { "/sdcard/Android/data/$(Package $name)/files" }

if ($Setup) {
    if (-not $MapFile -or -not $ThemeDir) { throw '-Setup needs -MapFile (the .map) and -ThemeDir (the OAM theme folder)' }
    $themeOut = Join-Path $ThemeDir 'singletrail_mtb.xml'
    & (Join-Path $root 'tools\theme\make-theme.ps1') -Source (Join-Path $ThemeDir 'andromaps_mtb.xml') -Out $themeOut
    Push-Location $root
    try { & .\gradlew.bat -Pspike :spike:assembleMapsforgeRelease :spike:assembleVtmRelease --console=plain -q; if ($LASTEXITCODE) { throw 'build failed' } }
    finally { Pop-Location }
    $dirs = @('') + (Get-ChildItem $ThemeDir -Recurse -Directory | ForEach-Object { $_.FullName.Substring((Resolve-Path $ThemeDir).Path.Length).Replace('\', '/') })
    foreach ($name in 'mapsforge', 'vtm') {
        Adb install -r (Join-Path $root "spike\build\outputs\apk\$name\release\spike-$name-release.apk")
        # Started once, the app creates its own files folder (adb may not create it).
        Adb shell am start -n "$(Package $name)/$activity"
        Start-Sleep -Seconds 3
        Adb shell am force-stop (Package $name)
        $files = Files $name
        Adb shell mkdir -p "$files/maps"
        Adb shell ("mkdir -p " + (($dirs | ForEach-Object { "'$files/themes/OAM$_'" }) -join ' '))
        "Copying the map to $name (takes a minute)…"
        Adb push $MapFile "$files/maps/"
        Adb push "$ThemeDir\." "$files/themes/OAM/"
    }
    'Setup done.'
}

if ($Renderer) {
    foreach ($name in 'mapsforge', 'vtm') { Adb shell am force-stop (Package $name) }
    $extras = @('--es', 'mode', $Mode, '--ei', 'minutes', $Minutes, '--es', 'theme', $Theme)
    if (-not $NoWait) { $extras += @('--ez', 'waitUnplug', 'true') }
    Adb shell am start -n "$(Package $Renderer)/$activity" @extras
    if ($NoWait) { "Running $Renderer ($Mode) for $Minutes min." }
    else { "Started $Renderer ($Mode). When the map has loaded, pull the cable: the $Minutes-minute run starts then. Don't touch the phone until the screen says done." }
}

if ($Pull) {
    New-Item -ItemType Directory -Force $OutDir | Out-Null
    foreach ($name in 'mapsforge', 'vtm') {
        $logs = (& $adbExe @device shell ls "$(Files $name)/logs" 2>$null) | Where-Object { $_ -like '*.log' }
        foreach ($log in $logs) { & $adbExe @device pull "$(Files $name)/logs/$($log.Trim())" $OutDir 2>&1 | Out-Null }
    }
    "Logs in $OutDir"
    Get-ChildItem $OutDir -Filter *.log | Sort-Object Name | ForEach-Object {
        $first = Select-String -Path $_.FullName -Pattern 'FIRST RENDER' | Select-Object -First 1
        $summary = Select-String -Path $_.FullName -Pattern 'SUMMARY|PAUSED' | Select-Object -Last 1
        "`n$($_.Name)"
        if ($first) { '  ' + $first.Line }
        if ($summary) { '  ' + $summary.Line } else { '  (no summary: run not finished)' }
    }
}
