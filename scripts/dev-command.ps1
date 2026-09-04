param(
    [Parameter(Mandatory = $true)]
    [string]$CommandId,

    [string]$ArgumentsJson = '{}'
)

$ErrorActionPreference = 'Stop'
$adb = 'D:\DevTools\Android\platform-tools\adb.exe'

$bytes = [System.Text.Encoding]::UTF8.GetBytes($ArgumentsJson)
$argumentsBase64 = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

& $adb shell run-as com.mcpocket.poc rm -f files/dev-last-command.json

& $adb shell am start-foreground-service `
    -n com.mcpocket.poc/.DevBridgeService `
    --es action mcp_command `
    --es commandId $CommandId `
    --es argumentsBase64 $argumentsBase64

if ($LASTEXITCODE -ne 0) {
    throw "Failed to request PickPico dev MCP command"
}

# DevBridgeService permits up to four minutes for interactive commands. Keep the
# host-side deadline slightly longer so HUMAN HELP can return its final result.
$deadline = [DateTime]::UtcNow.AddSeconds(250)
do {
    Start-Sleep -Milliseconds 200
    $result = & $adb shell run-as com.mcpocket.poc cat files/dev-last-command.json 2>$null
    if ($LASTEXITCODE -eq 0 -and $result) {
        $result
        exit 0
    }
} while ([DateTime]::UtcNow -lt $deadline)

throw "Timed out waiting for PickPico dev MCP command result"
