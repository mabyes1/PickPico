param(
    [ValidateRange(5, 60)]
    [int]$DurationSeconds = 10,
    [switch]$Stop,
    [string]$AdbPath = 'D:\DevTools\Android\platform-tools\adb.exe'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $AdbPath)) {
    $resolved = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -eq $resolved) {
        throw 'adb.exe was not found.'
    }
    $AdbPath = $resolved.Source
}

$devices = @(& $AdbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' })
if ($devices.Count -ne 1) {
    throw "Expected exactly one connected Android device, found $($devices.Count)."
}

$action = if ($Stop) { 'stop_ring' } else { 'ring' }
$args = @(
    'shell', 'am', 'start-foreground-service',
    '-n', 'com.mcpocket.poc/.DevBridgeService',
    '--es', 'action', $action
)
if (-not $Stop) {
    $args += @('--ei', 'durationSeconds', [string]$DurationSeconds)
}

& $AdbPath @args
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
