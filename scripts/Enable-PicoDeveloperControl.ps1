[CmdletBinding()]
param(
    [string] $Serial,
    [string] $AdbPath = 'adb',
    [switch] $Revoke
)
$ErrorActionPreference = 'Stop'
$permissionName = 'android.permission.WRITE_SECURE_SETTINGS'
$packageName = 'com.mcpocket.poc'
if (-not $Serial) {
    $deviceLines = @(& $AdbPath devices)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to list ADB devices.' }
    $phoneSerials = @($deviceLines | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    $mdnsSerials = @($phoneSerials | Where-Object { $_ -match '^adb-.*_adb-tls-connect\._tcp$' })
    if ($mdnsSerials.Count -eq 1) { $Serial = $mdnsSerials[0] }
    elseif ($phoneSerials.Count -eq 1) { $Serial = $phoneSerials[0] }
    else { throw 'Connect the phone to ADB once; if multiple phones are connected, specify -Serial.' }
}
$verb = if ($Revoke) { 'revoke' } else { 'grant' }
& $AdbPath -s $Serial shell pm $verb $packageName $permissionName
if ($LASTEXITCODE -ne 0) { throw "ADB permission $verb failed. Install PickPico 0.17.16 or later first." }
$permissionCheck = @(& $AdbPath -s $Serial shell dumpsys package $packageName)
if ($LASTEXITCODE -ne 0) { throw 'Unable to verify permission.' }
$permissionPattern = [regex]::Escape($permissionName)
if (-not ($permissionCheck -match $permissionPattern)) { throw 'Installed PickPico does not declare the required permission.' }
$permissionGranted = [bool]($permissionCheck -match ($permissionPattern + ':\s+granted=true'))
$expectedPermission = if ($Revoke) { 'denied' } else { 'granted' }
if ($permissionGranted -eq [bool]$Revoke) { throw 'Android permission verification failed.' }
Write-Output "PickPico developer control: $expectedPermission. Existing pairing and settings were retained."
