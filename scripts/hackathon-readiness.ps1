param(
    [string]$RelayBaseUrl = 'https://pickpico-relay.mcpocket.workers.dev',
    [switch]$SkipBuild,
    [switch]$Device,
    [switch]$HumanHelp
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = 'D:\DevTools\Android\platform-tools\adb.exe'
$results = [System.Collections.Generic.List[object]]::new()
$failureMessage = $null

function Add-Check {
    param([string]$Name, [bool]$Passed, [string]$Detail)
    $results.Add([pscustomobject]@{
        Check = $Name
        Result = if ($Passed) { 'PASS' } else { 'FAIL' }
        Detail = $Detail
    })
    if (-not $Passed) {
        throw "Readiness check failed: $Name - $Detail"
    }
}

try {
    if (-not $SkipBuild) {
        & (Join-Path $PSScriptRoot 'build-debug.ps1')
        Add-Check 'Build + unit tests' ($LASTEXITCODE -eq 0) 'Gradle testDebugUnitTest + assembleDebug'
    }

    $apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    Add-Check 'Debug APK' (Test-Path -LiteralPath $apk) $apk

    $healthUrl = $RelayBaseUrl.TrimEnd('/') + '/health'
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 10
        Add-Check 'Cloudflare relay health' ($health.ok -eq $true) $healthUrl
    } catch {
        Add-Check 'Cloudflare relay health' $false $_.Exception.Message
    }

    if ($Device -or $HumanHelp) {
        Add-Check 'ADB executable' (Test-Path -LiteralPath $adb) $adb
        $state = (& $adb get-state 2>$null | Out-String).Trim()
        Add-Check 'Android device' ($state -eq 'device') "adb state=$state"

        $package = (& $adb shell pm path com.mcpocket.poc 2>$null | Out-String).Trim()
        Add-Check 'PickPico installed' ($package -match '^package:') $package

        & (Join-Path $PSScriptRoot 'dev-node-start.ps1') | Out-Host
        Add-Check 'Start MCP node' ($LASTEXITCODE -eq 0) 'DevBridgeService start request sent'

        $phoneStatus = & (Join-Path $PSScriptRoot 'dev-command.ps1') -CommandId 'phone.status'
        $phoneStatusText = ($phoneStatus | Out-String)
        Add-Check 'phone.status' ($phoneStatusText -match 'battery|node|device|network') 'Real MCP command returned structured phone state'
    }

    if ($HumanHelp) {
        Write-Host ''
        Write-Host 'HUMAN HELP interactive check: respond on the phone when the task card appears.'
        $arguments = @{
            title = 'Hackathon readiness check'
            instruction = 'Please confirm that you can see this HUMAN HELP card. Optionally type a short reply or attach a photo.'
            actions = @('Looks good', 'Needs fixing')
            allowTextReply = $true
            allowImages = $true
            maxImages = 3
            idleTimeoutSeconds = 180
        } | ConvertTo-Json -Compress

        $human = & (Join-Path $PSScriptRoot 'dev-command.ps1') -CommandId 'human.help' -ArgumentsJson $arguments
        $humanText = ($human | Out-String)
        Add-Check 'HUMAN HELP round-trip' ($humanText -match 'completed|response|action') 'Human response returned to the Agent command runtime'
    }
} catch {
    $failureMessage = $_.Exception.Message
} finally {
    Write-Host ''
    Write-Host '=== PickPico Hackathon Readiness ==='
    $results | Format-Table -AutoSize | Out-Host
}

if ($failureMessage) {
    [Console]::Error.WriteLine($failureMessage)
    exit 1
}

Write-Host ''
Write-Host 'READY: automated checks passed.'
exit 0
